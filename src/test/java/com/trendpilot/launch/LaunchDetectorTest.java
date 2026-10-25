package com.trendpilot.launch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trendpilot.support.TestData;

/** 启动事件写库、后续收益补算（含随机基准、Discovery 晚多少分钟）、K 线不够时标 MISSING、周报一节。不碰任何账本。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class LaunchDetectorTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
        r.add("trendpilot.telegram.bot-token", () -> "");
        r.add("trendpilot.telegram.chat-id", () -> "");
    }

    @Autowired LaunchDetector detector;
    @Autowired JdbcClient jdbc;

    private void bars(String sym, long from, int n, double start, double step) {
        for (int i = 0; i < n; i++) {
            double c = start + step * i;
            jdbc.sql("""
                    INSERT OR REPLACE INTO kline_15m(venue, symbol, open_time, open, high, low, close, quote_volume, close_time)
                    VALUES ('BINANCE', :s, :t, :o, :h, :l, :c, 1000, :ct)
                    """).param("s", sym).param("t", from + i * 900_000L).param("o", c).param("h", c * 1.01).param("l", c * 0.99)
                    .param("c", c).param("ct", from + (i + 1) * 900_000L - 1).update();
        }
    }

    @Test
    void recordsLaunchesAndFillsOutcomesWithoutTouchingBooks() {
        Instant at = Instant.parse("2026-10-20T10:05:00Z");
        long from = Instant.parse("2026-10-20T09:00:00Z").toEpochMilli();
        bars("ABCUSDT", from, 130, 100, 0.1);   // 后来一路涨
        bars("XYZUSDT", from, 130, 50, 0);      // 随机基准的候选：横盘
        jdbc.sql("""
                INSERT INTO signal_log(generation, venue, symbol, price, score, hits, rel1h, rel4h, accel, volume_accel, compression, created_at)
                VALUES ('D-1', 'BINANCE', 'ABCUSDT', 101, 9, 'REL_1H,ACCEL', 3, 4, 2, 2, 1, '2026-10-20T11:17:00Z')
                """).update();
        detector.record("BINANCE", "ABCUSDT", at, new LaunchWindows.Launch(100.5, 1, 3.5, 4, 6));
        detector.record("BINANCE", "NOKLINEUSDT", at, new LaunchWindows.Launch(1, 1, 2.5, 3, 4));
        assertThat(detector.fillOutcomes(Instant.parse("2026-10-21T10:00:00Z"))).isZero(); // 还没满 24 小时
        assertThat(detector.fillOutcomes(Instant.parse("2026-10-21T12:00:00Z"))).isEqualTo(2);

        var row = jdbc.sql("SELECT outcome, had_signal, signal_after_min, max_up_24h, ret_24h, base_symbol, base_ret_24h FROM launch_event WHERE symbol = 'ABCUSDT'")
                .query().singleRow();
        assertThat(row.get("outcome")).isEqualTo("DONE");
        assertThat(((Number) row.get("had_signal")).intValue()).isZero();
        assertThat(((Number) row.get("signal_after_min")).doubleValue()).isEqualTo(72.0);
        assertThat(((Number) row.get("max_up_24h")).doubleValue()).isGreaterThan(5);
        assertThat(row.get("base_symbol")).isEqualTo("XYZUSDT");
        assertThat(((Number) row.get("base_ret_24h")).doubleValue()).isCloseTo(0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(jdbc.sql("SELECT outcome FROM launch_event WHERE symbol = 'NOKLINEUSDT'").query(String.class).single())
                .isEqualTo("MISSING");
        assertThat(detector.weeklyLines(Instant.parse("2026-10-22T00:00:00Z")))
                .anyMatch(l -> l.contains("最近 7 天 2 次")).anyMatch(l -> l.contains("5 分钟涨 ≥ 3%、放量 ≥ 5 倍：1 次"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM shadow_event").query(Integer.class).single()).isZero();
    }
}
