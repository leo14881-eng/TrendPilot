package com.trendpilot.market.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RawFrameLogTest {

    @TempDir
    Path dir;

    @Test
    void sampledTruncatedAndOffByDefault() throws Exception {
        Path file = dir.resolve("logs/ws-raw.log");
        new RawFrameLog(false, file).record("BINANCE", "{}", false);
        assertThat(file).doesNotExist();

        RawFrameLog raw = new RawFrameLog(true, file);
        raw.record("BINANCE", "{\"stream\":\"abcusdt@kline_15m\"}", false);
        for (int i = 0; i < RawFrameLog.QUOTE_SAMPLE * 2; i++) raw.record("BINANCE", "{\"q\":" + i + "}", true);
        raw.record("BYBIT", "x".repeat(800), false);
        var lines = Files.readAllLines(file);
        assertThat(lines).hasSize(1 + 2 + 1);
        assertThat(lines.get(0)).contains("BINANCE", "kline_15m");
        assertThat(lines.get(3)).contains("…(800)").hasSizeLessThan(600);
    }

    @Test
    void detectsSubscriptionErrors() {
        assertThat(RawFrameLog.subscriptionError("{\"error\":{\"code\":2,\"msg\":\"Invalid request\"},\"id\":3}")).isTrue();
        assertThat(RawFrameLog.subscriptionError("{\"success\":false,\"ret_msg\":\"error:handler not found\",\"op\":\"subscribe\"}")).isTrue();
        assertThat(RawFrameLog.subscriptionError("{\"result\":null,\"id\":3}")).isFalse();
        assertThat(RawFrameLog.subscriptionError("{\"success\":true,\"op\":\"subscribe\"}")).isFalse();
    }
}
