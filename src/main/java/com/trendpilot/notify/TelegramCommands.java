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
 * Telegram 指令：在和机器人的聊天里发 /holdings（或 /v2），几秒内收到当前持仓、盈亏和资金情况（与 /api/holdings 同一份内容）；
 * /status 看运行状态（版本、运行机器、最近一次持仓检查、各账本持仓数）；/help 看指令列表。
 * <ul>
 * <li>只回应配置的那个聊天（chat-id），别人发的忽略。</li>
 * <li>长轮询 getUpdates（服务器最多挂 25 秒），没有消息时约每 25 秒一次请求，不额外耗费接口额度。</li>
 * <li>只有负责运行的那台电脑回应（见 ActiveLease），两台同时开也不会回两遍。</li>
 * <li>这个机器人如果同时被别的程序用 getUpdates 读取（Telegram 返回 409），暂停 10 分钟再试，不和它抢。</li>
 * <li>出错（例如数据库正忙、网络断开）最多等 15 秒就重试；处理失败的指令不跳过，下次重试，同一条最多试 5 次，
 *     仍失败就回复"暂时取不到"。</li>
 * </ul>
 * 指令菜单：负责运行的那台启动后用 setMyCommands 注册一次（内容没变就跳过），在 Telegram 输入"/"或点"菜单"就能看到指令和说明；
 * 菜单和 /help 用同一份定义 {@link #MENU}，不会对不上。注册失败只记日志，稍后再试，不影响指令本身。
 * trendpilot.telegram-commands.enabled=false 可关闭。
 */
@Component
public class TelegramCommands {

    private static final Logger log = LoggerFactory.getLogger(TelegramCommands.class);
    static final String OFFSET_KEY = "telegram:update-offset";
    /** 出错后重试的最长等待：指令要尽快恢复响应，不能像后台任务一样退避到几分钟。 */
    static final long MAX_BACKOFF_MS = 15_000;
    static final int MAX_TRIES = 5;
    static final String MENU_KEY = "telegram:menu";

    /** 一条指令：名字（不带 /）和说明。 */
    record Cmd(String name, String description) {
    }

    /** 指令菜单和 /help 的唯一来源。 */
    static final java.util.List<Cmd> MENU = java.util.List.of(
            new Cmd("holdings", "当前持仓、当天盈亏和资金情况"),
            new Cmd("v2", "同 /holdings"),
            new Cmd("status", "运行状态：版本、运行机器、最近一次持仓检查、各账本持仓数"),
            new Cmd("trend", "BTC 均线趋势（4 条均线 × 4 份，单独账本）：各份状态、均线和买卖线、总价值"),
            new Cmd("rollback", "V2 参数集回滚到上一个（撤销最近一次自动转正，也可以直接回复「回滚」）"),
            new Cmd("help", "指令列表"));

    static final String HELP = "可用指令：\n" + String.join("\n",
            MENU.stream().map(c -> "/" + c.name() + " " + c.description()).toList());

    private final TelegramProperties props;
    private final ObjectMapper json;
    private final Notifier notifier;
    private final AppState state;
    private final HoldingsReport report;
    private final StatusText status;
    private final ObjectProvider<ActiveLease> lease;
    private final boolean enabled;
    /** BTC 均线趋势是单独的程序：只通过 /trend 查看，不并入 /holdings 和 /status。 */
    private ObjectProvider<com.trendpilot.btctrend.BtcTrendService> trend;
    private RestClient http;
    /** 下一条要读的消息编号；内存里为准，数据库只做重启后的恢复（写失败不影响读指令）。 */
    private Long offset;
    private final Map<Long, Integer> tries = new java.util.HashMap<>();
    /** 下次可以尝试注册菜单的时间（毫秒）；注册成功或内容没变后设为 Long.MAX_VALUE。 */
    private long nextMenuTry = 0;

    public TelegramCommands(TelegramProperties props, ObjectMapper json, Notifier notifier, AppState state,
                            HoldingsReport report, StatusText status, ObjectProvider<ActiveLease> lease,
                            @Value("${trendpilot.telegram-commands.enabled:true}") boolean enabled,
                            @Value("${trendpilot.scheduling.enabled:true}") boolean scheduling) {
        this.props = props;
        this.json = json;
        this.notifier = notifier;
        this.state = state;
        this.report = report;
        this.status = status;
        this.lease = lease;
        this.enabled = enabled && scheduling && props.configured();
    }

    /** 自动转正的参数集（回滚用）。 */
    private ObjectProvider<com.trendpilot.variant.ParamSets> paramSets;

    @org.springframework.beans.factory.annotation.Autowired
    void setParamSets(ObjectProvider<com.trendpilot.variant.ParamSets> paramSets) {
        this.paramSets = paramSets;
    }

    /** /rollback 或文字"回滚"：撤销最近一次自动转正，恢复上一个参数集。 */
    String rollbackText(Instant now) {
        return rollbackText(now, null);
    }

    /** @param updateId 触发的 Telegram 消息编号：同一条消息只回滚一次（重复处理时回复同样的结果） */
    String rollbackText(Instant now, Long updateId) {
        var ps = paramSets == null ? null : paramSets.getIfAvailable();
        if (ps == null) return "参数集暂时取不到，请稍后再试。";
        return ps.rollback(now, updateId).map(s -> "↩️ 已回滚：撤销 " + s.variant() + " 的自动转正，V2 恢复为"
                        + (s.from().isBlank() ? "基准参数（application.yml）" : "上一个参数集（" + com.trendpilot.variant.VariantOverrides.parseInline(s.from()).describe() + "）")
                        + "。下一次 Discovery 起生效，已有持仓照常管理。")
                .orElse("现在没有自动转正过的参数集可以回滚（V2 用的是基准参数）。如果要回滚某次代码发布，请在对话里说明。");
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setTrend(ObjectProvider<com.trendpilot.btctrend.BtcTrendService> trend) {
        this.trend = trend;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!enabled) return;
        var f = com.trendpilot.health.ApiStatsConfig.requestFactory(10_000, 40_000); // 长轮询要比 25 秒长
        http = RestClient.builder().requestFactory(f).baseUrl(props.apiBase()).build();
        Thread t = new Thread(this::loop, "telegram-commands");
        t.setDaemon(true);
        t.start();
        log.info("Telegram 指令已开启（/holdings、/status、/trend、/help）");
    }

    private void loop() {
        long backoff = 2_000;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (!shouldPoll()) {
                    Thread.sleep(30_000); // 待命机器不读指令，由运行的那台回应
                    continue;
                }
                registerMenuIfDue(System.currentTimeMillis());
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

    /** 只有负责运行的那台读指令；待命机器不调用 getUpdates，免得两台抢同一条消息。 */
    boolean shouldPoll() {
        ActiveLease l = lease.getIfAvailable();
        return l == null || l.active();
    }

    /** setMyCommands 的请求内容。 */
    static Map<String, Object> menuRequest() {
        return Map.of("commands", MENU.stream()
                .map(c -> Map.of("command", c.name(), "description", c.description())).toList());
    }

    private static String menuFingerprint() {
        return MENU.stream().map(c -> c.name() + "=" + c.description()).collect(java.util.stream.Collectors.joining("|"));
    }

    /** 注册指令菜单（到时间才试；内容和上次注册的一样就跳过）。失败只记日志，10 分钟后再试；429 按 retry_after 等。 */
    void registerMenuIfDue(long nowMs) {
        if (nowMs < nextMenuTry) return;
        String fp = menuFingerprint();
        try {
            if (state.get(MENU_KEY).filter(fp::equals).isPresent()) {
                nextMenuTry = Long.MAX_VALUE;
                return;
            }
        } catch (RuntimeException e) {
            log.debug("读取指令菜单记录失败，照常注册：{}", e.toString());
        }
        try {
            http.post().uri("/bot{token}/setMyCommands", props.botToken()).body(menuRequest()).retrieve().toBodilessEntity();
            nextMenuTry = Long.MAX_VALUE;
            log.info("Telegram 指令菜单已注册（{} 条）", MENU.size());
            try {
                state.put(MENU_KEY, fp);
            } catch (RuntimeException e) {
                log.debug("保存指令菜单记录失败（下次启动会再注册一次）：{}", e.toString());
            }
        } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
            long wait = retryAfterSeconds(e.getResponseBodyAsString()) * 1000L;
            nextMenuTry = nowMs + Math.max(wait, 60_000);
            log.warn("注册 Telegram 指令菜单遇到 429，{} 秒后再试", Math.max(wait, 60_000) / 1000);
        } catch (RuntimeException e) {
            nextMenuTry = nowMs + 600_000;
            log.warn("注册 Telegram 指令菜单没有成功，10 分钟后再试：{}", Notifier.maskText(e.getMessage()));
        }
    }

    private long retryAfterSeconds(String body) {
        try {
            return json.readTree(body).path("parameters").path("retry_after").asLong(60);
        } catch (Exception e) {
            return 60;
        }
    }

    void useClient(RestClient client) {
        this.http = client;
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
                    try {
                        notifier.send("cmd:" + id, "暂时取不到，请稍后再试。");
                    } catch (RuntimeException ignored) {
                        // 回复也发不出去：跳过这条，不影响后面的指令
                    }
                }
            }
            tries.remove(id);
            advance(id + 1);
        }
    }

    String trendText() {
        try {
            var t = trend == null ? null : trend.getIfAvailable();
            return t == null ? "BTC 均线趋势暂时取不到。" : t.text(false, java.util.List.of());
        } catch (RuntimeException e) {
            return "BTC 均线趋势暂时取不到。";
        }
    }

    private void answer(long id, String raw, Instant now) {
        String text = raw.trim().toLowerCase(Locale.ROOT);
        String cmd = text.split("[\\s@]", 2)[0];
        switch (cmd) {
            case "/holdings", "/v2", "v2" -> notifier.send("cmd:" + id, report.telegramText(now));
            case "/status" -> notifier.send("cmd:" + id, status.text(now));
            case "/trend" -> notifier.send("cmd:" + id, trendText());
            case "/rollback", "回滚" -> notifier.send("cmd:" + id, rollbackText(now, id));
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
