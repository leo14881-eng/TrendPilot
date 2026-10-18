package com.trendpilot.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SkipAlertsTest {

    @Test
    void alertsOnStartEverySixHoursAndOnRecovery() {
        SkipAlerts a = new SkipAlerts();
        Instant t = Instant.parse("2026-10-15T10:00:00Z");
        Map<String, String> why = Map.of("WINUSDT", "BINANCE 交易状态：HTTP 429", "GPSUSDT", "BINANCE 交易状态：HTTP 429");
        assertThat(a.onRound(Map.of(), t)).isNull();
        String first = a.onRound(why, t);
        assertThat(first).contains("跳过 2 个持仓").contains("HTTP 429").contains("WINUSDT");
        assertThat(a.onRound(why, t.plus(Duration.ofMinutes(5)))).isNull();
        assertThat(a.onRound(Map.of("WINUSDT", "BINANCE 盘口：超时"), t.plus(Duration.ofHours(5)))).isNull();
        assertThat(a.onRound(why, t.plus(Duration.ofHours(6)))).contains("仍在跳过");
        assertThat(a.onRound(why, t.plus(Duration.ofHours(7)))).isNull();
        assertThat(a.onRound(Map.of(), t.plus(Duration.ofHours(8)))).startsWith("✅").contains("480 分钟");
        assertThat(a.onRound(Map.of(), t.plus(Duration.ofHours(9)))).isNull();
        assertThat(a.onRound(why, t.plus(Duration.ofHours(10)))).contains("跳过 2 个持仓");
    }

    @Test
    void causeUsesRootMessageOnOneLine() {
        Exception e = new RuntimeException("wrap", new java.io.IOException("HTTP 429\n  too many"));
        assertThat(MonitorJob.cause(e)).isEqualTo("HTTP 429 too many");
        Exception dns = new RuntimeException("io", new java.nio.channels.UnresolvedAddressException());
        assertThat(MonitorJob.cause(dns)).isEqualTo("网络不通（域名解析失败）");
    }
}
