package com.trendpilot.variant;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trendpilot.market.ExecutionSimulator.Fill;
import com.trendpilot.market.Venue;
import com.trendpilot.monitor.ExitDecision;
import com.trendpilot.portfolio.Position;
import com.trendpilot.support.TestData;

/** 候选退出 X1（日线 ATR×1.5 止损 + 3 天等反弹）：规则口径与 4 年回测一致，影子并行只写 variant_trade。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AtrStaleExitTest {

    static final Path dataDir = TestData.dataDir();
    static final long H = 3_600_000L;
    static final Instant OPEN = Instant.parse("2026-10-10T00:00:00Z");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
    }

    @Autowired JdbcClient jdbc;
    @Autowired ParallelExits parallel;

    @Test
    void distanceIsAtrTimesOneAndHalfWithinBounds() {
        assertThat(AtrStaleExit.distancePct(8)).isEqualTo(12);
        assertThat(AtrStaleExit.distancePct(2)).isEqualTo(6);
        assertThat(AtrStaleExit.distancePct(30)).isEqualTo(25);
        assertThat(AtrStaleExit.distancePct(Double.NaN)).isEqualTo(15.5);
        // 14 根日线，每根高低差 10、收盘 100 → ATR 10%
        List<double[]> d = java.util.stream.IntStream.range(0, 15).mapToObj(i -> new double[]{105, 95, 100}).toList();
        assertThat(AtrStaleExit.atrDailyPct(d, 100)).isEqualTo(10.0);
        assertThat(AtrStaleExit.atrDailyPct(d.subList(0, 13), 100)).isNaN();
    }

    @Test
    void stopOnHourCloseBelowDistanceOnlyOncePerHour() {
        AtrStaleExit.State st = AtrStaleExit.State.fresh(12.0);
        Instant now = OPEN.plus(Duration.ofHours(5));
        assertThat(AtrStaleExit.step(st, OPEN, now, false, 1.0, 0.87, 4 * H, -130).exit()).isEqualTo("X1_STOP");
        AtrStaleExit.Step ok = AtrStaleExit.step(st, OPEN, now, false, 1.0, 0.90, 4 * H, -100);
        assertThat(ok.exit()).isNull();
        // 同一根 1h K 线不重复判断
        assertThat(AtrStaleExit.step(ok.next(), OPEN, now, false, 1.0, 0.5, 4 * H, -500).exit()).isNull();
        assertThat(AtrStaleExit.step(ok.next(), OPEN, now, false, 1.0, 0.69, 5 * H, -310).exit()).isEqualTo("X1_FLOOR");
    }

    @Test
    void afterThreeDaysLosingWaitsForBounceHigh() {
        AtrStaleExit.State st = AtrStaleExit.State.fresh(20.0);
        Instant t71 = OPEN.plus(Duration.ofHours(71));
        // 71 小时还没到
        st = AtrStaleExit.step(st, OPEN, t71, false, 1.0, 0.95, 70 * H, -50).next();
        assertThat(st.waitSince()).isNull();
        // 72 小时仍亏损 → 开始等反弹
        Instant t72 = OPEN.plus(Duration.ofHours(72));
        st = AtrStaleExit.step(st, OPEN, t72, false, 1.0, 0.95, 71 * H, -50).next();
        assertThat(st.waitSince()).isEqualTo(t72);
        // 回落、反弹、再回落 → 在反弹后的第一根回落收盘卖
        AtrStaleExit.Step s1 = AtrStaleExit.step(st, OPEN, t72.plus(Duration.ofHours(1)), false, 1.0, 0.94, 72 * H, -60);
        assertThat(s1.exit()).isNull();
        AtrStaleExit.Step s2 = AtrStaleExit.step(s1.next(), OPEN, t72.plus(Duration.ofHours(2)), false, 1.0, 0.97, 73 * H, -30);
        assertThat(s2.exit()).isNull();
        assertThat(AtrStaleExit.step(s2.next(), OPEN, t72.plus(Duration.ofHours(3)), false, 1.0, 0.96, 74 * H, -40).exit())
                .isEqualTo("X1_STALE_BOUNCE");
        // 等待中回本 → 卖；利润保护已启动的不进入等待
        assertThat(AtrStaleExit.step(st, OPEN, t72.plus(Duration.ofHours(1)), false, 1.0, 1.01, 72 * H, 2).exit())
                .isEqualTo("X1_STALE_EVEN");
        assertThat(AtrStaleExit.step(AtrStaleExit.State.fresh(20.0), OPEN, t72, true, 1.0, 0.95, 71 * H, -50).next().waitSince())
                .isNull();
        // 一直不反弹：最多等 24 小时
        assertThat(AtrStaleExit.step(st, OPEN, t72.plus(Duration.ofHours(24)), false, 1.0, 0.93, 95 * H, -70).exit())
                .isEqualTo("X1_STALE_TIMEOUT");
    }

    @Test
    void shadowVariantRecordsEarlyExitWithoutTouchingTheBook() {
        Position pos = new Position(Venue.BINANCE, "XONEUSDT", new BigDecimal("1000"), new BigDecimal("1001"),
                new BigDecimal("1000"), 1, new BigDecimal("1"), OPEN, "G", OPEN, false, BigDecimal.ZERO, BigDecimal.ZERO, 0, false);
        assertThat(parallel.needDailyAtr(List.of(pos))).containsExactly(pos);
        parallel.provideDailyAtr(pos, 8); // 止损距离 12%
        Fill fill = new Fill(new BigDecimal("1000"), new BigDecimal("950"), new BigDecimal("0.95"), new BigDecimal("1"), 0, true);
        ExitDecision hold = new ExitDecision("XONEUSDT", false, "HOLD", pos, List.of(), new BigDecimal("-52"), -5, fill);
        parallel.observe(pos, hold, Double.NaN, 0.95, OPEN.toEpochMilli() + 3 * H, OPEN.plus(Duration.ofHours(4)));
        assertThat(parallel.needDailyAtr(List.of(pos))).isEmpty();
        assertThat(jdbc.sql("SELECT x_dist_pct FROM variant_trade WHERE rule_id = 'X1_ATR1.5_W72' AND symbol = 'XONEUSDT'")
                .query(Double.class).single()).isEqualTo(12.0);
        ExitDecision deep = new ExitDecision("XONEUSDT", false, "HOLD", pos, List.of(), new BigDecimal("-122"), -12, fill);
        parallel.observe(pos, deep, Double.NaN, 0.875, OPEN.toEpochMilli() + 4 * H, OPEN.plus(Duration.ofHours(5)));
        assertThat(jdbc.sql("SELECT exit_reason FROM variant_trade WHERE rule_id = 'X1_ATR1.5_W72' AND symbol = 'XONEUSDT'")
                .query(String.class).single()).isEqualTo("X1_STOP");
        // V2 之后正式卖出：记录同一笔的正式结果，用于配对比较
        parallel.closeWithV2(pos, new ExitDecision("XONEUSDT", true, "PROFIT_PROTECTION", pos, List.of(), new BigDecimal("20"), 2, fill),
                OPEN.plus(Duration.ofHours(30)));
        ParallelExits.Comparison c = parallel.compare().stream().filter(x -> x.ruleId().equals("X1_ATR1.5_W72")).findFirst().orElseThrow();
        assertThat(c.pairs()).isEqualTo(1);
        assertThat(c.variantPnl()).isEqualTo(-122.0);
        assertThat(c.v2Pnl()).isEqualTo(20.0);
        assertThat(c.earlyExits()).isEqualTo(1);
    }
}
