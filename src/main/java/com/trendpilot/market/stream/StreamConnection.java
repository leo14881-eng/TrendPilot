package com.trendpilot.market.stream;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一条自动重连的 WebSocket 连接：
 * <ul>
 * <li>断线或连接失败：指数退避重连（2 秒起翻倍，最长 5 分钟，加 ±20% 随机抖动，避免多条连接同时重连）；
 *     重连后由 onOpen 重新订阅。</li>
 * <li>连上后 60 秒内又断（例如被交易所限流拒绝），不清零失败次数，退避继续加长，不会快速循环重连。</li>
 * <li>僵死检测：连接看似正常但超过 {@link #STALE_MS} 没有任何消息或 ping（半开 TCP），主动断开重连。</li>
 * <li>同一时间只排一个重连，断开、出错、僵死检测同时触发也不会重复连接。</li>
 * </ul>
 * 重连成功不代表数据已经完整，K 线缺口仍由 REST 补齐。
 */
final class StreamConnection {

    private static final Logger log = LoggerFactory.getLogger(StreamConnection.class);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);
    /** 没有任何消息（含 ping/pong）超过这个时间视为僵死；Binance 服务器约 20 秒 ping 一次，Bybit 由我们每 20 秒 ping。 */
    static final long STALE_MS = 200_000;
    /** 连接保持超过这个时间才算稳定，清零失败次数。 */
    static final long STABLE_MS = 60_000;

    private final String name;
    private final URI uri;
    private final Consumer<String> onMessage;
    private final Consumer<StreamConnection> onOpen;
    private final ScheduledExecutorService scheduler;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private volatile WebSocket socket;
    private CompletableFuture<?> sendTail = CompletableFuture.completedFuture(null);
    private volatile boolean closed;
    private volatile long connectedAtMs;
    private volatile long lastMessageMs;
    private int failures;
    private final java.util.concurrent.atomic.AtomicBoolean reconnectPending = new java.util.concurrent.atomic.AtomicBoolean();

    StreamConnection(String name, URI uri, Consumer<String> onMessage, Consumer<StreamConnection> onOpen,
                     ScheduledExecutorService scheduler) {
        this.name = name;
        this.uri = uri;
        this.onMessage = onMessage;
        this.onOpen = onOpen;
        this.scheduler = scheduler;
    }

    static ScheduledExecutorService newScheduler() {
        return Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("stream-", 0).factory());
    }

    void connect() {
        reconnectPending.set(false);
        if (closed) return;
        // 握手卡住（TCP 连上但不回应）也要在 30 秒内放弃并按退避重连，不能一直停在"连接中"
        http.newWebSocketBuilder().buildAsync(uri, new Listener()).orTimeout(30, TimeUnit.SECONDS).whenComplete((ws, err) -> {
            if (err != null) {
                log.warn("{} WebSocket 连接失败：{}", name, err.getMessage());
                scheduleReconnect();
                return;
            }
            socket = ws;
            sendTail = CompletableFuture.completedFuture(null);
            connectedAtMs = System.currentTimeMillis();
            lastMessageMs = connectedAtMs;
            log.info("{} WebSocket 已连接", name);
            onOpen.accept(this);
        });
    }

    /** 僵死检测（StreamHub 每 30 秒调用）：连着但太久没消息就主动重连。 */
    void reconnectIfStale() {
        if (stale(socket != null, lastMessageMs, System.currentTimeMillis())) {
            log.warn("{} WebSocket {} 秒没有收到任何消息，判定僵死，重连", name,
                    (System.currentTimeMillis() - lastMessageMs) / 1000);
            reconnect();
        }
    }

    static boolean stale(boolean connected, long lastMessageMs, long nowMs) {
        return connected && nowMs - lastMessageMs > STALE_MS;
    }

    /** 第 failures 次失败后的等待：2^failures 秒（上限 5 分钟），乘以 0.8~1.2 的随机抖动。 */
    static long backoffMs(int failures, double random) {
        long base = Math.min(MAX_BACKOFF.toMillis(), 1000L << Math.min(failures, 9));
        return Math.round(base * (0.8 + 0.4 * random));
    }

    /** 已连接且最近 90 秒内收到过消息。 */
    boolean healthy() {
        return socket != null && System.currentTimeMillis() - lastMessageMs < 90_000;
    }

    long connectedAtMs() {
        return connectedAtMs;
    }

    /**
     * 发送（订阅、ping）：Java WebSocket 上一条没发完时再发会直接失败，所以一条接一条串起来发。
     * 发送失败说明连接已坏或订阅可能丢了：断开重连，重连后会重新订阅全部。
     */
    synchronized void send(String text) {
        WebSocket ws = socket;
        if (ws == null) return;
        sendTail = sendTail.thenCompose(x -> ws.sendText(text, true)).handle((r, err) -> {
            if (err != null && ws == socket) {
                log.warn("{} WebSocket 发送失败，重连并重新订阅：{}", name, err.getMessage());
                scheduler.execute(this::reconnect);
            }
            return null;
        });
    }

    /** 分批发送订阅消息，每批间隔 gapMs，避免超过交易所的消息频率限制。 */
    void sendPaced(List<String> messages, long gapMs) {
        for (int i = 0; i < messages.size(); i++) {
            String m = messages.get(i);
            scheduler.schedule(() -> send(m), i * gapMs, TimeUnit.MILLISECONDS);
        }
    }

    /** 主动断开并重连（例如交易所 24 小时强制断线前）。 */
    void reconnect() {
        WebSocket ws = socket;
        socket = null;
        if (ws != null) ws.abort();
        if (reconnectPending.compareAndSet(false, true)) connect();
    }

    void close() {
        closed = true;
        WebSocket ws = socket;
        if (ws != null) ws.abort();
    }

    private void scheduleReconnect() {
        socket = null;
        if (closed || !reconnectPending.compareAndSet(false, true)) return;
        if (connectedAtMs > 0 && System.currentTimeMillis() - connectedAtMs >= STABLE_MS) failures = 0; // 上次连接稳定过
        connectedAtMs = 0;
        failures++;
        long delay = backoffMs(failures, java.util.concurrent.ThreadLocalRandom.current().nextDouble());
        log.warn("{} WebSocket {} 毫秒后重连（第 {} 次）", name, delay, failures);
        scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String msg = buffer.toString();
                buffer.setLength(0);
                lastMessageMs = System.currentTimeMillis();
                try {
                    onMessage.accept(msg);
                } catch (RuntimeException e) {
                    log.warn("{} 消息处理失败：{}", name, e.getMessage());
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket ws, java.nio.ByteBuffer message) {
            lastMessageMs = System.currentTimeMillis();
            ws.sendPong(message);
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.warn("{} WebSocket 断开 {} {}", name, statusCode, reason);
            if (ws == socket) scheduleReconnect();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("{} WebSocket 出错：{}", name, error.getMessage());
            if (ws == socket) scheduleReconnect();
        }
    }
}
