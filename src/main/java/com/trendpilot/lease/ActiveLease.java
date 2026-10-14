package com.trendpilot.lease;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trendpilot.InstanceLock;
import com.trendpilot.notify.Notifier;
import com.trendpilot.notify.TelegramProperties;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 跨机器保护：同一个 Telegram 机器人下同时只有一台电脑"运行"，其他电脑"待命"。
 * <p>
 * 两台电脑没有共享数据库，唯一共享的是 Telegram 聊天，所以租约放在聊天的置顶消息里：
 * 运行实例置顶一条"🟢 TrendPilot 运行中：机器名"，每 2 分钟改一次心跳时间（编辑消息不提醒）。
 * <ul>
 * <li>启动或每次心跳时读置顶（getChat.pinned_message）：是自己的就续租；是别人的且 10 分钟内有心跳就待命；
 *     没有或已过期就发一条新的并置顶，3 秒后再读一次确认。</li>
 * <li>两台同时抢：Telegram 的 pinned_message 是"发送时间最新的置顶消息"，两边再读看到的是同一条，
 *     输的一方取消自己的置顶并待命，所以只会剩一台运行。</li>
 * <li>待命时所有定时任务（扫描、持仓检查、BTC 短线、报告等）都跳过，不记账、不发通知，只继续读租约；
 *     运行的那台停止超过 10 分钟，待命机器自动接管。</li>
 * <li>Telegram 读不到：已经在运行的继续运行，已经待命的继续待命；启动时就读不到按单机运行（目前是影子模式）。
 *     接真实下单后，读不到租约时必须停止新开仓（见 docs/规则说明.md「多实例」）。</li>
 * </ul>
 * Telegram 未配置或 trendpilot.instance.lease-enabled=false 时不启用，本机始终运行。
 */
@Component
public class ActiveLease {

    private static final Logger log = LoggerFactory.getLogger(ActiveLease.class);
    static final String TAG = "#tp-lease ";
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    /** 置顶消息：id、文本、最后一次发送或编辑的时间（Telegram 服务器时间）。 */
    public record Pin(long id, String text, Instant at) {
    }

    /** 读写置顶消息（生产用 Telegram，测试用假实现）。 */
    public interface Board {
        Optional<Pin> pinned();

        long post(String text);

        void pin(long id);

        void unpin(long id);

        void edit(long id, String text);
    }

    private final Board board;
    private final Notifier notifier;
    private final String me;
    private final Duration takeoverAfter;
    private final long heartbeatSeconds;
    private final long confirmDelayMs;
    private final ZoneId zone;
    private ScheduledExecutorService timer;

    private volatile boolean active;
    private volatile boolean decided;
    private volatile String owner;
    private volatile Instant ownerHeartbeat;

    @org.springframework.beans.factory.annotation.Autowired
    public ActiveLease(TelegramProperties props, ObjectMapper json, Notifier notifier,
                       @Value("${trendpilot.instance.name:}") String name,
                       @Value("${trendpilot.instance.lease-enabled:true}") boolean enabled,
                       @Value("${trendpilot.instance.heartbeat-seconds:120}") long heartbeatSeconds,
                       @Value("${trendpilot.instance.takeover-minutes:10}") long takeoverMinutes,
                       @Value("${trendpilot.zone}") String zone) {
        this(enabled && props.configured() ? new TelegramBoard(props, json) : null, notifier,
                name == null || name.isBlank() ? InstanceLock.hostName() : name,
                Duration.ofMinutes(takeoverMinutes), heartbeatSeconds, 3000, ZoneId.of(zone));
    }

    ActiveLease(Board board, Notifier notifier, String me, Duration takeoverAfter, long heartbeatSeconds,
                long confirmDelayMs, ZoneId zone) {
        this.board = board;
        this.notifier = notifier;
        this.me = me;
        this.takeoverAfter = takeoverAfter;
        this.heartbeatSeconds = heartbeatSeconds;
        this.confirmDelayMs = confirmDelayMs;
        this.zone = zone;
        this.active = board == null;
        this.decided = board == null;
        this.owner = board == null ? me : null;
        notifier.gate(this::active);
    }

    @PostConstruct
    void start() {
        if (board == null) {
            log.info("跨机器保护未启用（Telegram 未配置或已关闭），本机按单机运行");
            return;
        }
        tick(Instant.now());
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lease");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleWithFixedDelay(() -> {
            try {
                tick(Instant.now());
            } catch (RuntimeException e) {
                log.warn("租约检查出错：{}", e.getMessage());
            }
        }, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (timer != null) timer.shutdownNow();
    }

    /** 本机现在是否负责运行（定时任务和通知只在运行时执行）。 */
    public boolean active() {
        return active;
    }

    public String me() {
        return me;
    }

    /** 当前运行的机器（未知时为空）。 */
    public Optional<String> owner() {
        return Optional.ofNullable(owner);
    }

    public Optional<Instant> ownerHeartbeat() {
        return Optional.ofNullable(ownerHeartbeat);
    }

    public boolean enabled() {
        return board != null;
    }

    /** 读一次租约并决定本机角色。 */
    synchronized void tick(Instant now) {
        Optional<Pin> pin;
        try {
            pin = board.pinned();
        } catch (RuntimeException e) {
            log.warn("读不到 Telegram 置顶租约：{}", Notifier.maskText(e.getMessage()));
            if (!decided) become(true, me, now, "读不到 Telegram 租约，按单机运行");
            return;
        }
        Optional<String> holder = pin.flatMap(p -> holder(p.text()));
        if (holder.isPresent() && holder.get().equals(me)) {
            try {
                board.edit(pin.get().id(), text(now));
            } catch (RuntimeException e) {
                log.warn("续租失败：{}", Notifier.maskText(e.getMessage()));
            }
            become(true, me, now, null);
            return;
        }
        if (holder.isPresent() && Duration.between(pin.get().at(), now).compareTo(takeoverAfter) < 0) {
            become(false, holder.get(), pin.get().at(), null);
            return;
        }
        claim(now, pin, holder);
    }

    private void claim(Instant now, Optional<Pin> old, Optional<String> oldHolder) {
        long mine;
        try {
            mine = board.post(text(now));
            board.pin(mine);
        } catch (RuntimeException e) {
            log.warn("抢占租约失败：{}", Notifier.maskText(e.getMessage()));
            if (!decided) become(true, me, now, "写不了 Telegram 租约，按单机运行");
            return;
        }
        sleep(confirmDelayMs);
        Optional<Pin> after;
        try {
            after = board.pinned();
        } catch (RuntimeException e) {
            after = Optional.empty();
        }
        Optional<String> winner = after.flatMap(p -> holder(p.text()));
        if (winner.isEmpty() || winner.get().equals(me)) {
            old.filter(p -> oldHolder.isPresent()).ifPresent(p -> quietly(() -> board.unpin(p.id())));
            String why = oldHolder.map(h -> "原运行机器 " + h + " 已 " + Duration.between(old.get().at(), now).toMinutes()
                    + " 分钟没有心跳").orElse(null);
            become(true, me, now, why);
        } else {
            quietly(() -> board.unpin(mine));
            become(false, winner.get(), after.get().at(), null);
        }
    }

    private void become(boolean nowActive, String holder, Instant heartbeat, String why) {
        boolean wasDecided = decided;
        boolean wasActive = active;
        active = nowActive;
        decided = true;
        owner = holder;
        ownerHeartbeat = heartbeat;
        if (wasDecided && wasActive == nowActive) return;
        String stamp = Instant.now().toString();
        if (nowActive) {
            log.info("本机 {} 负责运行{}", me, why == null ? "" : "（" + why + "）");
            if (wasDecided) notifier.sendAlways("lease-active:" + me + ":" + stamp,
                    "▶️ 本机 " + me + " 接管运行" + (why == null ? "" : "：" + why));
        } else {
            log.warn("另一台机器 {} 正在运行，本机 {} 待命", holder, me);
            notifier.sendAlways("lease-standby:" + me + ":" + stamp, "⏸ 本机 " + me + " 进入待命：" + holder
                    + " 正在运行（心跳 " + HHMM.format(heartbeat.atZone(zone)) + "）。待命时不扫描、不记账、不发通知；"
                    + "对方停止超过 " + takeoverAfter.toMinutes() + " 分钟会自动接管。");
        }
    }

    String text(Instant now) {
        return "🟢 TrendPilot 运行中\n机器：" + me + "\n心跳：" + HHMM.format(now.atZone(zone))
                + "（每 " + heartbeatSeconds / 60 + " 分钟更新；请保留这条置顶，它防止两台电脑同时运行）\n" + TAG + me;
    }

    static Optional<String> holder(String text) {
        if (text == null) return Optional.empty();
        for (String line : text.split("\n")) {
            if (line.startsWith(TAG)) {
                String h = line.substring(TAG.length()).trim();
                return h.isEmpty() ? Optional.empty() : Optional.of(h);
            }
        }
        return Optional.empty();
    }

    private static void quietly(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.debug("忽略：{}", e.getMessage());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Telegram Bot API：getChat / sendMessage / pinChatMessage / unpinChatMessage / editMessageText。 */
    static final class TelegramBoard implements Board {
        private final RestClient http;
        private final TelegramProperties props;
        private final ObjectMapper json;

        TelegramBoard(TelegramProperties props, ObjectMapper json) {
            var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(10_000);
            factory.setReadTimeout(15_000);
            this.http = RestClient.builder().requestFactory(factory).baseUrl(props.apiBase()).build();
            this.props = props;
            this.json = json;
        }

        private com.fasterxml.jackson.databind.JsonNode call(String method, java.util.Map<String, Object> body) {
            String raw = http.post().uri("/bot{token}/" + method, props.botToken()).body(body).retrieve().body(String.class);
            try {
                var node = json.readTree(raw);
                if (!node.path("ok").asBoolean()) throw new IllegalStateException(method + "：" + node.path("description").asText());
                return node.path("result");
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(method + " 返回无法解析", e);
            }
        }

        @Override
        public Optional<Pin> pinned() {
            var m = call("getChat", java.util.Map.of("chat_id", props.chatId())).path("pinned_message");
            if (m.isMissingNode() || m.isNull()) return Optional.empty();
            long at = Math.max(m.path("date").asLong(), m.path("edit_date").asLong());
            return Optional.of(new Pin(m.path("message_id").asLong(), m.path("text").asText(""), Instant.ofEpochSecond(at)));
        }

        @Override
        public long post(String text) {
            return call("sendMessage", java.util.Map.of("chat_id", props.chatId(), "text", text,
                    "disable_notification", true)).path("message_id").asLong();
        }

        @Override
        public void pin(long id) {
            call("pinChatMessage", java.util.Map.of("chat_id", props.chatId(), "message_id", id, "disable_notification", true));
        }

        @Override
        public void unpin(long id) {
            call("unpinChatMessage", java.util.Map.of("chat_id", props.chatId(), "message_id", id));
        }

        @Override
        public void edit(long id, String text) {
            call("editMessageText", java.util.Map.of("chat_id", props.chatId(), "message_id", id, "text", text));
        }
    }
}
