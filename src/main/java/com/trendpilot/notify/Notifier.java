package com.trendpilot.notify;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 通知发送：先写 Outbox（PREPARED），再发 Telegram，成功记 SENT，失败记 FAILED。
 * 同一个 eventKey 只发一次。PREPARED 不代表用户已收到。
 * 超过 Telegram 单条上限的消息按行分段发送；失败的消息每 2 分钟重试，24 小时内最多 6 次，
 * 已发出的分段不重发。写入后没来得及发送就重启的（超过 5 分钟仍是 PREPARED）也按同样规则补发。
 */
@Service
public class Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);
    /** Telegram 单条上限 4096 字符，留余量。 */
    static final int MAX_PART = 4000;
    static final int MAX_ATTEMPTS = 6;
    /** 每条 Telegram 开头带上项目名（用户 2026-10-18 要求），多个项目共用一个聊天时能分清。Outbox 里存原文。 */
    public static final String PROJECT_TAG = "【TrendPilot】";
    /** 写入 Outbox 后超过这么久仍是 PREPARED，说明发送前服务被重启了。 */
    static final Duration STRANDED_AFTER = Duration.ofMinutes(5);
    static final String RESENT_PREFIX = "（补发：服务重启前没来得及发出）\n";

    private final JdbcClient jdbc;
    private final TelegramProperties props;
    private final RestClient http;
    /** 本机是否是运行实例（跨机器保护，见 lease.ActiveLease）；待命时只写 Outbox（STANDBY），不发。 */
    private volatile java.util.function.BooleanSupplier sendGate = () -> true;
    /** 组合锁内产生的通知：先记下，解锁后再发。 */
    private final ThreadLocal<List<String[]>> deferred = new ThreadLocal<>();

    public Notifier(JdbcClient jdbc, TelegramProperties props, RestClient.Builder builder) {
        this.jdbc = jdbc;
        this.props = props;
        this.http = builder.baseUrl(props.apiBase()).build();
    }

    public void gate(java.util.function.BooleanSupplier gate) {
        this.sendGate = gate;
    }

    /** @return 本次是否真正发出（重复事件、未配置或本机待命时返回 false） */
    public boolean send(String eventKey, String text) {
        return send(eventKey, text, false);
    }

    /** 不受待命限制（只用于"本机待命 / 接管"这类实例切换通知）。 */
    public boolean sendAlways(String eventKey, String text) {
        return send(eventKey, text, true);
    }

    private boolean send(String eventKey, String text, boolean always) {
        int inserted = jdbc.sql("""
                INSERT OR IGNORE INTO notification_outbox(event_key, channel, text, status, created_at)
                VALUES (:key, 'TELEGRAM', :text, 'PREPARED', :now)
                """)
                .param("key", eventKey)
                .param("text", text)
                .param("now", Instant.now().toString())
                .update();
        if (inserted == 0) {
            log.debug("通知已存在，跳过：{}", eventKey);
            return false;
        }
        if (!props.configured()) {
            log.warn("Telegram 未配置，通知只记录在 Outbox：{}", eventKey);
            return false;
        }
        if (!always && !sendGate.getAsBoolean()) {
            mark(eventKey, "STANDBY", null, 0);
            log.info("本机待命，不发通知：{}", eventKey);
            return false;
        }
        List<String[]> queue = deferred.get();
        if (queue != null) {
            queue.add(new String[] {eventKey, text});
            return true;
        }
        return deliver(eventKey, text, 0);
    }

    /** 本线程之后的通知先排队；@return 是否由本次调用开启（嵌套时只有最外层负责发送）。 */
    public boolean deferOnThisThread() {
        if (deferred.get() != null) return false;
        deferred.set(new ArrayList<>());
        return true;
    }

    /** 发出本线程排队的通知并结束排队。 */
    public void flushDeferred() {
        List<String[]> queue = deferred.get();
        deferred.remove();
        if (queue == null) return;
        for (String[] n : queue) deliver(n[0], n[1], 0);
    }

    /** 重发失败的通知（只发还没发出的分段）。 */
    @Scheduled(fixedDelay = 120_000, initialDelay = 90_000)
    public void retryFailed() {
        retryFailed(Instant.now());
    }

    public int retryFailed(Instant now) {
        if (!props.configured() || !sendGate.getAsBoolean()) return 0;
        record Pending(String key, String text, int sentParts, boolean stranded) { }
        // PREPARED 超过 STRANDED_AFTER 还没发：写入 Outbox 后服务被重启/停止，来不及发出，一并补发
        List<Pending> pending = jdbc.sql("""
                SELECT event_key, text, sent_parts, status FROM notification_outbox
                WHERE channel = 'TELEGRAM' AND (status = 'FAILED' OR (status = 'PREPARED' AND created_at < :stale))
                  AND attempts < :max AND created_at >= :since ORDER BY id
                """)
                .param("max", MAX_ATTEMPTS).param("since", now.minus(Duration.ofHours(24)).toString())
                .param("stale", now.minus(STRANDED_AFTER).toString())
                .query((rs, i) -> new Pending(rs.getString(1), rs.getString(2), rs.getInt(3),
                        "PREPARED".equals(rs.getString(4)))).list();
        int sent = 0;
        for (Pending p : pending) {
            String text = p.stranded() ? RESENT_PREFIX + p.text() : p.text();
            if (deliver(p.key(), text, p.sentParts())) sent++;
            else break; // 网络还没恢复，下一轮再试
        }
        return sent;
    }

    private boolean deliver(String eventKey, String text, int fromPart) {
        List<String> parts = split(text, MAX_PART - PROJECT_TAG.length());
        int done = fromPart;
        try {
            for (int i = fromPart; i < parts.size(); i++) {
                String part = PROJECT_TAG + (parts.size() == 1 ? parts.get(i) : "(" + (i + 1) + "/" + parts.size() + ")\n" + parts.get(i));
                http.post()
                        .uri("/bot{token}/sendMessage", props.botToken())
                        .body(Map.of("chat_id", props.chatId(), "text", part))
                        .retrieve()
                        .toBodilessEntity();
                done = i + 1;
            }
            mark(eventKey, "SENT", null, done);
            return true;
        } catch (RestClientException e) {
            String msg = mask(e.getMessage());
            log.error("Telegram 发送失败：{}：{}", eventKey, msg);
            mark(eventKey, "FAILED", msg, done);
            return false;
        }
    }

    /** 文件附件的最长等待（Telegram 429 的 retry_after），超过就放弃这次附件。 */
    static final long MAX_DOC_WAIT_SECONDS = 60;

    /**
     * 以附件发送一个文本文件（例如完整日报 .md）。同一个 eventKey 只发一次；Outbox 只记文件名（channel = TELEGRAM_DOC），
     * 不进入正文的失败重试。遇到 429 按 retry_after 等一次（最多 60 秒）再试；仍失败只记日志，不影响其他通知。
     *
     * @return 是否发出
     */
    public boolean sendDocument(String eventKey, String fileName, String content, String caption) {
        int inserted = jdbc.sql("""
                INSERT OR IGNORE INTO notification_outbox(event_key, channel, text, status, created_at)
                VALUES (:key, 'TELEGRAM_DOC', :text, 'PREPARED', :now)
                """)
                .param("key", eventKey).param("text", fileName).param("now", Instant.now().toString()).update();
        if (inserted == 0 || !props.configured()) return false;
        if (!sendGate.getAsBoolean()) {
            mark(eventKey, "STANDBY", null, 0);
            return false;
        }
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                http.post()
                        .uri("/bot{token}/sendDocument", props.botToken())
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(documentForm(fileName, bytes, caption))
                        .retrieve()
                        .toBodilessEntity();
                mark(eventKey, "SENT", null, 1);
                return true;
            } catch (HttpClientErrorException e) {
                long wait = e.getStatusCode().value() == 429 ? retryAfterSeconds(e.getResponseBodyAsString()) : -1;
                if (attempt == 1 && wait >= 0 && wait <= MAX_DOC_WAIT_SECONDS) {
                    log.warn("Telegram 附件限流，{} 秒后再试：{}", wait, eventKey);
                    sleepSeconds(wait);
                    continue;
                }
                return docFailed(eventKey, e);
            } catch (RestClientException e) {
                return docFailed(eventKey, e);
            }
        }
        return false;
    }

    MultiValueMap<String, Object> documentForm(String fileName, byte[] bytes, String caption) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("chat_id", props.chatId());
        form.add("caption", PROJECT_TAG + caption);
        form.add("document", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        return form;
    }

    private boolean docFailed(String eventKey, Exception e) {
        String msg = mask(e.getMessage());
        log.error("Telegram 附件发送失败：{}：{}", eventKey, msg);
        mark(eventKey, "FAILED", msg, 0);
        return false;
    }

    /** Telegram 429 响应体里的 parameters.retry_after；读不到按 5 秒。 */
    static long retryAfterSeconds(String body) {
        var m = java.util.regex.Pattern.compile("\"retry_after\"\\s*:\\s*(\\d+)").matcher(body == null ? "" : body);
        return m.find() ? Long.parseLong(m.group(1)) : 5;
    }

    private static void sleepSeconds(long s) {
        try {
            Thread.sleep(Duration.ofSeconds(s));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 按行切分，单行过长时硬切。 */
    static List<String> split(String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            while (line.length() > max) {
                if (!cur.isEmpty()) { out.add(cur.toString()); cur.setLength(0); }
                out.add(line.substring(0, max));
                line = line.substring(max);
            }
            if (cur.length() + line.length() + 1 > max && !cur.isEmpty()) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (!cur.isEmpty()) cur.append('\n');
            cur.append(line);
        }
        if (!cur.isEmpty() || out.isEmpty()) out.add(cur.toString());
        return out;
    }

    /** 同 mask，供其他直接调用 Telegram 的类使用。 */
    public static String maskText(String s) {
        return mask(s);
    }

    /** 错误信息里可能带请求地址（含 Token），记录前遮住。 */
    static String mask(String s) {
        return s == null ? null : s.replaceAll("bot[0-9]+(:|%3A|%3a)[A-Za-z0-9_-]+", "bot***");
    }

    private void mark(String eventKey, String status, String error, int sentParts) {
        jdbc.sql("""
                UPDATE notification_outbox SET status = :s, last_error = :e, updated_at = :now,
                       attempts = attempts + 1, sent_parts = :p WHERE event_key = :key
                """)
                .param("s", status)
                .param("e", error)
                .param("now", Instant.now().toString())
                .param("p", sentParts)
                .param("key", eventKey)
                .update();
    }
}
