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
 * 一条自动重连的 WebSocket 连接。断线后有界指数退避重连（最长 5 分钟），重连后由 onOpen 重新订阅；
 * 重连成功不代表数据已经完整，K 线缺口仍由 REST 补齐。
 */
final class StreamConnection {

    private static final Logger log = LoggerFactory.getLogger(StreamConnection.class);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final String name;
    private final URI uri;
    private final Consumer<String> onMessage;
    private final Consumer<StreamConnection> onOpen;
    private final ScheduledExecutorService scheduler;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private volatile WebSocket socket;
    private volatile boolean closed;
    private volatile long connectedAtMs;
    private volatile long lastMessageMs;
    private int failures;

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
        if (closed) return;
        http.newWebSocketBuilder().buildAsync(uri, new Listener()).whenComplete((ws, err) -> {
            if (err != null) {
                log.warn("{} WebSocket 连接失败：{}", name, err.getMessage());
                scheduleReconnect();
                return;
            }
            socket = ws;
            failures = 0;
            connectedAtMs = System.currentTimeMillis();
            log.info("{} WebSocket 已连接", name);
            onOpen.accept(this);
        });
    }

    /** 已连接且最近 90 秒内收到过消息。 */
    boolean healthy() {
        return socket != null && System.currentTimeMillis() - lastMessageMs < 90_000;
    }

    long connectedAtMs() {
        return connectedAtMs;
    }

    void send(String text) {
        WebSocket ws = socket;
        if (ws != null) {
            ws.sendText(text, true);
        }
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
        connect();
    }

    void close() {
        closed = true;
        WebSocket ws = socket;
        if (ws != null) ws.abort();
    }

    private void scheduleReconnect() {
        socket = null;
        if (closed) return;
        failures++;
        long delay = Math.min(MAX_BACKOFF.toMillis(), 1000L << Math.min(failures, 9));
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
