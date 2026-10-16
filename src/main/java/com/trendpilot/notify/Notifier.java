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
                WHERE (status = 'FAILED' OR (status = 'PREPARED' AND created_at < :stale))
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
        List<String> parts = split(text, MAX_PART);
        int done = fromPart;
        try {
            for (int i = fromPart; i < parts.size(); i++) {
                String part = parts.size() == 1 ? parts.get(i) : "(" + (i + 1) + "/" + parts.size() + ")\n" + parts.get(i);
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
