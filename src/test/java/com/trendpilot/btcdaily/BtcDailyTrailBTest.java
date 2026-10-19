package com.trendpilot.btcdaily;

import static com.trendpilot.btcdaily.BtcDailyRulesTest.bar;
import static com.trendpilot.btcdaily.BtcDailyRulesTest.daily;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.trendpilot.market.Kline;

/** 方案 B（用户 2026-10-20 选定）：第 1 档开盘 −0.1%，涨 3% 后跟踪、回落 3×ATR 卖，满 30 天成本 +0.3% 以上卖，绝不卖亏。 */
class BtcDailyTrailBTest {

    static final BtcDailyProperties P = BtcDailyProperties.defaults().withExitMode("TRAIL_B").withFirstOpenPct(0.1);
    static final LocalDate DAY = LocalDate.parse("2026-10-20");

    /** ATR = 1000 的计划。 */
    static DailyPlan plan(LocalDate day, Book book, double open) {
        return BtcDailyRules.plan(day, daily(day.minusDays(1), 60, 60_000, 1_000), open, book, P);
    }

    static Book bought() {
        Book book = new Book();
        DailyPlan p = plan(DAY, book, 60_000);
        List<Fill> f = BtcDailyRules.onBar(p, book, bar(DAY, 0, 60_000, 60_000, 59_900, 59_950), P);
        assertThat(f).extracting(Fill::side).containsExactly(Fill.Side.BUY);
        return book;
    }

    @Test
    void firstLevelIsOpenMinusPointOnePercentOnStrongAndWeakDays() {
        DailyPlan p = plan(DAY, new Book(), 60_000);
        assertThat(p.levels()).extracting(DailyPlan.Level::price).containsExactly(59_940.0, 59_440.0, 58_940.0);
        // 偏强日（昨收高于 EMA）也一样
        List<Kline> up = new java.util.ArrayList<>(daily(DAY.minusDays(2), 59, 60_000, 1_000));
        long t = BtcDailyRules.dayStart(DAY.minusDays(1));
        up.add(new Kline(t, 60_000, 63_000, 60_000, 62_500, 1e9, t + 86_399_999L));
        DailyPlan strong = BtcDailyRules.plan(DAY, up, 62_000, new Book(), P);
        assertThat(strong.strong()).isTrue();
        assertThat(strong.levels().getFirst().price()).isEqualTo(61_938.0);
    }

    @Test
    void noSellBeforeActivationEvenAfterARise() {
        Book book = bought();
        double avg = book.avgCost();
        DailyPlan p = plan(DAY, book, 60_000);
        // 涨到均价 +2.5%（没到 +3%）再回落：不卖
        assertThat(BtcDailyRules.onBar(p, book, bar(DAY, 1, 59_950, avg * 1.025, 59_950, avg * 1.02), P)).isEmpty();
        assertThat(BtcDailyRules.onBar(p, book, bar(DAY, 2, avg * 1.02, avg * 1.02, avg * 1.004, avg * 1.005), P)).isEmpty();
        assertThat(book.holding()).isTrue();
    }

    @Test
    void sellsWhenPriceFallsThreeAtrFromPeakAfterActivation() {
        Book book = bought();
        DailyPlan p = plan(DAY, book, 60_000);
        assertThat(BtcDailyRules.onBar(p, book, bar(DAY, 1, 60_000, 66_000, 60_000, 65_800), P)).isEmpty();
        // 跟踪线 = 66000 − 3×1000 = 63000：没跌到不卖
        assertThat(BtcDailyRules.onBar(p, book, bar(DAY, 2, 65_800, 65_900, 63_100, 63_200), P)).isEmpty();
        List<Fill> f = BtcDailyRules.onBar(p, book, bar(DAY, 3, 63_200, 63_300, 62_500, 62_600), P);
        assertThat(f).extracting(Fill::side).containsExactly(Fill.Side.SELL);
        assertThat(f.getFirst().price()).isCloseTo(63_000 * (1 - 0.0002), within(1e-6));
        assertThat(f.getFirst().pnl()).isGreaterThan(0);
        assertThat(book.holding()).isFalse();
    }

    @Test
    void carriedPositionDoesNotDropTargetToPointThreePercentBeforeThirtyDays() {
        Book book = bought();
        double avg = book.avgCost();
        LocalDate next = DAY.plusDays(1);
        DailyPlan p = plan(next, book, 60_000);
        // 旧规则跨天到均价 +0.3% 就卖；方案 B 不卖
        assertThat(BtcDailyRules.onBar(p, book, bar(next, 0, 60_000, avg * 1.01, 59_800, avg * 1.005), P)).isEmpty();
        assertThat(book.holding()).isTrue();
    }

    @Test
    void afterThirtyDaysSellsAtCostPlusFeesAndPointThreePercent() {
        Book book = bought();
        double avg = book.avgCost();
        double floor = BtcDailyRules.floorB(book, P);
        LocalDate d29 = DAY.plusDays(29);
        assertThat(BtcDailyRules.onBar(plan(d29, book, 60_000), book, bar(d29, 4, 60_000, floor + 50, 59_700, 60_000), P)).isEmpty();
        LocalDate d30 = DAY.plusDays(30);
        List<Fill> f = BtcDailyRules.onBar(plan(d30, book, 60_000), book, bar(d30, 4, 59_800, floor + 50, 59_700, 60_000), P);
        assertThat(f).extracting(Fill::side).containsExactly(Fill.Side.SELL);
        assertThat(f.getFirst().price()).isGreaterThanOrEqualTo(avg * (1 + P.feeRate() + 0.003) * (1 - 1e-9));
        assertThat(f.getFirst().pnl()).isGreaterThan(0);
    }

    @Test
    void gapBelowFloorDoesNotSell() {
        Book book = bought();
        DailyPlan p = plan(DAY, book, 60_000);
        BtcDailyRules.onBar(p, book, bar(DAY, 1, 60_000, 66_000, 60_000, 65_800), P);
        double floor = BtcDailyRules.floorB(book, P);
        // 一根 K 线从 65800 直接跳到保底价下方：按研究引擎同一规则不卖，等回到保底价以上
        assertThat(BtcDailyRules.onBar(p, book, bar(DAY, 2, floor - 500, floor - 400, floor - 600, floor - 500), P)).isEmpty();
        assertThat(book.holding()).isTrue();
    }

    @Test
    void addsKeepLoweringAverageWhileTracking() {
        Book book = bought();
        DailyPlan p = plan(DAY, book, 60_000);
        List<Fill> f = BtcDailyRules.onBar(p, book, bar(DAY, 1, 59_900, 59_900, 58_900, 59_000), P);
        assertThat(f).extracting(Fill::side).containsExactly(Fill.Side.BUY, Fill.Side.BUY);
        assertThat(book.cost()).isCloseTo(10_000, within(1e-6));
    }

    /** 随机行情跑很多天：任何一笔卖出的成交价都不低于含费均价 × (1 + 手续费 + 0.3%)，且都盈利。 */
    @Test
    void randomPathsNeverSellBelowFloor() {
        Random rnd = new Random(42);
        int sells = 0;
        for (int run = 0; run < 40; run++) {
            Book book = new Book();
            double price = 60_000;
            for (int d = 0; d < 120; d++) {
                LocalDate day = DAY.plusDays(d);
                DailyPlan p = plan(day, book, price);
                for (int q = 0; q < 96; q++) {
                    double o = price;
                    double c = o * (1 + rnd.nextGaussian() * 0.006);
                    double h = Math.max(o, c) * (1 + Math.abs(rnd.nextGaussian()) * 0.003);
                    double l = Math.min(o, c) * (1 - Math.abs(rnd.nextGaussian()) * 0.003);
                    double avgBefore = book.avgCost();
                    for (Fill f : BtcDailyRules.onBar(p, book, bar(day, q, o, h, l, c), P)) {
                        if (f.side() == Fill.Side.SELL) {
                            sells++;
                            assertThat(f.pnl()).isGreaterThan(0);
                            assertThat(f.price()).isGreaterThanOrEqualTo(avgBefore * (1 + P.feeRate() + 0.003) * (1 - 1e-9));
                        }
                    }
                    price = c;
                }
            }
        }
        assertThat(sells).isGreaterThan(10);
    }

    @Test
    void liveConfigIsOptionB() throws Exception {
        var env = new org.springframework.core.env.StandardEnvironment();
        for (var ps : new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("app", new org.springframework.core.io.ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(ps);
        }
        BtcDailyProperties live = org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("trendpilot.btc-daily", BtcDailyProperties.class).get();
        assertThat(live.firstOpenPct()).isEqualTo(0.0);
        assertThat(live.kStrong()).isEqualTo(0.25);
        assertThat(live.kWeak()).isEqualTo(0.4);
        assertThat(live.exit().timeoutDays()).isEqualTo(30);
        assertThat(live.exit().timeoutTargetPct()).isEqualTo(3.0);
        assertThat(live.exit().trailB()).isTrue();
        assertThat(live.exit().requireBacktest()).isFalse();
        assertThat(live.exit().activatePct()).isEqualTo(3.0);
        assertThat(live.exit().giveAtr()).isEqualTo(3.0);
        assertThat(live.exit().floorPct()).isEqualTo(0.3);
        assertThat(live.tranches()).containsExactly(3000.0, 3000.0, 4000.0);
        assertThat(live.stepAtr()).isEqualTo(0.5);
    }

    @Test
    void noTimeoutMeansOnlyTrailingSells() {
        BtcDailyProperties never = P.withExitTimeout(-1, 0.3);
        Book book = new Book();
        DailyPlan p0 = BtcDailyRules.plan(DAY, daily(DAY.minusDays(1), 60, 60_000, 1_000), 60_000, book, never);
        BtcDailyRules.onBar(p0, book, bar(DAY, 0, 60_000, 60_000, 59_900, 59_950), never);
        double floor = BtcDailyRules.floorB(book, never);
        LocalDate d90 = DAY.plusDays(90);
        DailyPlan p = BtcDailyRules.plan(d90, daily(d90.minusDays(1), 60, 60_000, 1_000), 60_000, book, never);
        assertThat(BtcDailyRules.onBar(p, book, bar(d90, 0, 59_800, floor + 100, 59_700, 60_000), never)).isEmpty();
    }

    @Test
    void timeoutTargetIsConfigurable() {
        BtcDailyProperties t3 = P.withExitTimeout(30, 3.0);
        Book book = new Book();
        DailyPlan p0 = BtcDailyRules.plan(DAY, daily(DAY.minusDays(1), 60, 60_000, 1_000), 60_000, book, t3);
        BtcDailyRules.onBar(p0, book, bar(DAY, 0, 60_000, 60_000, 59_900, 59_950), t3);
        double avg = book.avgCost();
        LocalDate d31 = DAY.plusDays(31);
        DailyPlan p = BtcDailyRules.plan(d31, daily(d31.minusDays(1), 60, 60_000, 1_000), 60_000, book, t3);
        // 到 +1% 不卖（目标是 +3%，且没到跟踪激活）
        assertThat(BtcDailyRules.onBar(p, book, bar(d31, 0, 59_800, avg * 1.01, 59_700, avg * 1.005), t3)).isEmpty();
        assertThat(BtcDailyRules.timeoutTargetB(book, t3)).isGreaterThan(avg * 1.03);
    }

    @Test
    void fixedModeNeverSellsAtLossEither() {
        BtcDailyProperties fixed = BtcDailyProperties.defaults().withExitMode("FIXED");
        Book book = new Book();
        DailyPlan p = BtcDailyRules.plan(DAY, daily(DAY.minusDays(1), 60, 60_000, 1_000), 60_000, book, fixed);
        BtcDailyRules.onBar(p, book, bar(DAY, 0, 60_000, 60_000, 59_300, 59_400), fixed);
        assertThat(book.holding()).isTrue();
        BtcDailyRules.Exit loss = new BtcDailyRules.Exit(book.avgCost() * 0.99, "测试");
        assertThat(BtcDailyRules.profitable(book, loss, 0.0002, fixed)).isFalse();
    }
}
