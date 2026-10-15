package com.trendpilot.notify;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trendpilot.job.AppState;
import com.trendpilot.lease.ActiveLease;
import com.trendpilot.report.HoldingsReport;

/**
 * Telegram 指令：在和机器人的聊天里发 /v2，几秒内收到当前 V2 持仓、盈亏和资金情况（与日报同一份内容）。
 * <ul>
 * <li>只回应配置的那个聊天（chat-id），别人发的忽略。</li>
 * <li>长轮询 getUpdates（服务器最多挂 25 秒），没有消息时约每 25 秒一次请求，不额外耗费接口额度。</li>
 * <li>只有负责运行的那台电脑回应（见 ActiveLease），两台同时开也不会回两遍。</li>
 * <li>这个机器人如果同时被别的程序用 getUpdates 读取（Telegram 返回 409），暂停 10 分钟再试，不和它抢。</li>
 * <li>出错（例如数据库正忙、网络断开）最多等 15 秒就重试；处理失败的指令不跳过，下次重试，同一条最多试 5 次。</li>
 * </ul>
 * trendpilot.telegram-commands.enabled=false 可关闭。
 */
@Component
public class TelegramCommands {

    private static final Logger log = LoggerFactory.getLogger(TelegramCommands.class);
    static final String OFFSET_KEY = "telegram:update-offset";
    /** 出错后重试的最长等待：指令要尽快恢复响应，不能像后台任务一样退避到几分钟。 */
    static final long MAX_BACKOFF_MS = 15_000;
    static final int MAX_TRIES = 5;
    static final String HELP = "可用指令：\n/v2 当前 V2 持仓、当天盈亏和资金情况\n/help 指令列表";

    private final TelegramProperties props;
    private final ObjectMapper json;
    private final Notifier notifier;
    private final AppState state;
    private final HoldingsReport report;
    private final ObjectProvider<ActiveLease> lease;
    private final boolean enabled;
    private RestClient http;
    /** 下一条要读的消息编号；内存里为准，数据库只做重启后的恢复（写失败不影响读指令）。 */
    private Long offset;
    private final Map<Long, Integer> tries = new java.util.HashMap<>();

    public TelegramCommands(TelegramProperties props, ObjectMapper json, Notifier notifier, AppState state,
                            HoldingsReport report, ObjectProvider<ActiveLease> lease,
                            @Value("${trendpilot.telegram-commands.enabled:true}") boolean enabled,
                            @Value("${trendpilot.scheduling.enabled:true}") boolean scheduling) {
        this.props = props;
        this.json = json;
        this.notifier = notifier;
        this.state = state;
        this.report = report;
        this.lease = lease;
        this.enabled = enabled && scheduling && props.configured();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) return;
        var f = com.trendpilot.health.ApiStatsConfig.requestFactory(10_000, 40_000); // 长轮询要比 25 秒长
        http = RestClient.builder().requestFactory(f).baseUrl(props.apiBase()).build();
        Thread t = new Thread(this::loop, "telegram-commands");
        t.setDaemon(true);
        t.start();
        log.info("Telegram 指令已开启（/v2、/help）");
    }

    private void loop() {
        long backoff = 2_000;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ActiveLease l = lease.getIfAvailable();
                if (l != null && !l.active()) {
                    Thread.sleep(30_000); // 待命机器不读指令，由运行的那台回应
                    continue;
                }
                poll();
                backoff = 2_000;
            } catch (InterruptedException e) {
                return;
            } catch (org.springframework.web.client.HttpClientErrorException.Conflict e) {
                log.warn("Telegram 机器人正被别的程序读取消息（409），指令功能暂停 10 分钟");
                sleep(600_000);
            } catch (RuntimeException e) {
                log.warn("读取 Telegram 指令失败：{}", Notifier.maskText(e.getMessage()));
                sleep(backoff);
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
    }

    void poll() {
        if (offset == null) offset = state.get(OFFSET_KEY).map(Long::parseLong).orElse(0L);
        String raw = http.post().uri("/bot{token}/getUpdates", props.botToken())
                .body(Map.of("offset", offset, "timeout", 25, "allowed_updates", java.util.List.of("message")))
                .retrieve().body(String.class);
        handle(raw, Instant.now());
    }

    /** 处理一批 getUpdates 结果（单独拿出来便于测试）。 */
    void handle(String raw, Instant now) {
        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("getUpdates 返回无法解析", e);
        }
        for (JsonNode u : root.path("result")) {
            long id = u.path("update_id").asLong();
            JsonNode m = u.path("message");
            if (m.path("chat").path("id").asText().equals(props.chatId())) {
                try {
                    answer(id, m.path("text").asText(""), now);
                } catch (RuntimeException e) {
                    // 处理失败（多半是数据库正忙）：不推进编号，下次重新读到这条再试；同一条最多 5 次，避免一条坏消息卡住后面所有指令
                    int n = tries.merge(id, 1, Integer::sum);
                    if (n < MAX_TRIES) throw e;
                    log.warn("Telegram 指令 {} 连续 {} 次处理失败，跳过：{}", id, n, e.toString());
                }
            }
            tries.remove(id);
            advance(id + 1);
        }
    }

    private void answer(long id, String raw, Instant now) {
        String text = raw.trim().toLowerCase(Locale.ROOT);
        String cmd = text.split("[\\s@]", 2)[0];
        switch (cmd) {
            case "/v2", "v2" -> notifier.send("cmd:" + id, HoldingsReport.telegram(report.markdown(now)));
            case "/help", "/start" -> notifier.send("cmd:" + id, HELP);
            default -> {
                if (cmd.startsWith("/")) notifier.send("cmd:" + id, "不认识的指令。" + HELP);
            }
        }
    }

    private void advance(long next) {
        offset = next;
        try {
            state.put(OFFSET_KEY, String.valueOf(next));
        } catch (RuntimeException e) {
            log.debug("保存 Telegram 指令编号失败（内存里已记下）：{}", e.toString());
        }
    }

    Long offset() {
        return offset;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
