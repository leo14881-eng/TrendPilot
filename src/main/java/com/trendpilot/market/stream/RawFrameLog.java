package com.trendpilot.market.stream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket 原始消息日志（排查用，默认关闭）：在 config/application-local.yml 写
 * {@code trendpilot.stream.debug-log: true} 后重启生效，写到 logs/ws-raw.log。
 * <ul>
 * <li>每条最多 500 字符；最优价推送非常频繁，每个交易所每 50 条只记 1 条，K 线和订阅回执全部记录。</li>
 * <li>文件超过 20MB 改名为 ws-raw.log.1（只保留一份旧文件），不会把硬盘写满。</li>
 * </ul>
 * 订阅失败的回执不受开关影响，总是在主日志里 WARN（见 {@link #subscriptionError}）。
 */
final class RawFrameLog {

    private static final Logger log = LoggerFactory.getLogger(RawFrameLog.class);
    static final int MAX_CHARS = 500;
    static final int QUOTE_SAMPLE = 50;
    static final long MAX_BYTES = 20L * 1024 * 1024;

    private final boolean enabled;
    private final Path file;
    private final Map<String, AtomicLong> quoteCounts = new ConcurrentHashMap<>();

    RawFrameLog(boolean enabled, Path file) {
        this.enabled = enabled;
        this.file = file;
    }

    boolean enabled() {
        return enabled;
    }

    /** 记录一条原始消息；isQuote = 解析结果是最优价推送。 */
    void record(String venue, String message, boolean isQuote) {
        if (!enabled) return;
        if (isQuote && quoteCounts.computeIfAbsent(venue, v -> new AtomicLong()).getAndIncrement() % QUOTE_SAMPLE != 0) return;
        String text = message.length() > MAX_CHARS ? message.substring(0, MAX_CHARS) + "…(" + message.length() + ")" : message;
        String line = Instant.now() + " " + venue + " " + text.replace('\n', ' ') + System.lineSeparator();
        synchronized (this) {
            try {
                Files.createDirectories(file.getParent());
                if (Files.exists(file) && Files.size(file) > MAX_BYTES) {
                    Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.debug("写 ws-raw.log 失败：{}", e.getMessage());
            }
        }
    }

    /**
     * 订阅失败的回执：Binance 返回 {"error":{...},"id":n}，Bybit 返回 {"success":false,...}。
     * 只看短消息开头，不做完整 JSON 解析，避免影响行情处理速度。
     */
    static boolean subscriptionError(String message) {
        if (message.length() > 2000) return false;
        String m = message.replace(" ", "");
        return m.startsWith("{\"error\"") || m.contains("\"error\":{") || m.contains("\"success\":false");
    }
}
