package com.trendpilot.btcdaily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.trendpilot.market.Kline;

class BtcDailyRulesTest {

    static final BtcDailyProperties P = BtcDailyProperties.defaults().withExitMode("FIXED");
    static final LocalDate DAY = LocalDate.parse("2026-10-15");
    static final long Q = 15 * 60_000L;

    /** n 根日线：收盘 close，振幅固定 range（ATR = range）。 */
    static List<Kline> daily(LocalDate lastDay, int n, double close, double range) {
        List<Kline> out = new ArrayList<>();
        for (int i = n - 1; i >= 0; i--) {
            long t = BtcDailyRules.dayStart(lastDay.minusDays(i));
            out.add(new Kline(t, close, close + range / 2, close - range / 2, close, 1e9, t + 86_399_999L));
        }
        return out;
    }

    static Kline bar(LocalDate day, int quarter, double open, double high, double low, double close) {
        long t = BtcDailyRules.dayStart(day) + quarter * Q;
        return new Kline(t, open, high, low, close, 1e6, t + Q - 1);
    }

    @Test
    void newRoundPlanHasThreeLevelsBelowOpen() {
        DailyPlan p = BtcDailyRules.plan(DAY, daily(DAY.minusDays(1), 60, 60_000, 2_000), 60_000, new Book(), P);
        assertThat(p.atr()).isCloseTo(2_000, within(1e-6));
        assertThat(p.strong()).isFalse(); // 昨收 = EMA，不算偏强
        assertThat(p.levels()).extracting(DailyPlan.Level::price).containsExactly(58_800.0, 57_800.0, 56_800.0);
        assertThat(p.levels()).extracting(DailyPlan.Level::usdt).containsExactly(3000.0, 3000.0, 4000.0);
        assertThat(p.tpPct()).isCloseTo(Math.max(0.5 * 2000 / 60000 * 100, 0.8), within(1e-9));
    }

    @Test
    void buysAddsAndTakesProfitWithoutStopLoss() {
        Book book = new Book();
        DailyPlan plan = BtcDailyRules.plan(DAY, daily(DAY.minusDays(1), 60, 60_000, 2_000), 60_000, book, P);

        // 跌穿第 1、2 档：同一根 K 线里依次买入
        List<Fill> f = BtcDailyRules.onBar(plan, book, bar(DAY, 0, 60_000, 60_000, 57_700, 57_900), P);
        assertThat(f).extracting(Fill::side).containsExactly(Fill.Side.BUY, Fill.Side.BUY);
        assertThat(book.buys()).isEqualTo(2);
        assertThat(book.cost()).isCloseTo(6000, within(1e-6));

        // 继续深跌到第 3 档以下：只加仓，绝不卖出
        f = BtcDailyRules.onBar(plan, book, bar(DAY, 1, 57_900, 57_900, 50_000, 50_500), P);
        assertThat(f).extracting(Fill::side).containsExactly(Fill.Side.BUY);
        assertThat(book.cost()).isCloseTo(10_000, within(1e-6));
        assertThat(book.maxFloatLoss()).isLessThan(-1000);

        // 再跌：资金已满，不买也不卖
        assertThat(BtcDailyRules.onBar(plan, book, bar(DAY, 2, 50_500, 50_600, 45_000, 45_500), P)).isEmpty();

        // 涨到止盈价：全部卖出，盈利
        double tp = BtcDailyRules.takeProfitPrice(plan, book, P);
        f = BtcDailyRules.onBar(plan, book, bar(DAY, 3, 56_000, tp + 1, 55_000, tp), P);
        assertThat(f).singleElement().satisfies(s -> {
            assertThat(s.side()).isEqualTo(Fill.Side.SELL);
            assertThat(s.pnl()).isPositive();
        });
        assertThat(book.holding()).isFalse();
        assertThat(book.rounds()).isEqualTo(1);
        Book.Round round = book.lastRound;
        assertThat(round.buys()).isEqualTo(3);
        assertThat(round.holdMinutes()).isEqualTo(60);
        assertThat(round.maePct()).isLessThan(-20);
        assertThat(round.mfePct()).isPositive();
        // 不止损，但四个候选止损都被记录为"会触发"，假设亏损越来越大
        assertThat(round.stopHits()).containsOnlyKeys("3.0", "5.0", "8.0", "12.0");
        assertThat(round.stopHits().get("3.0").pnl()).isNegative();
        assertThat(round.stopHits().get("12.0").pnl()).isLessThan(round.stopHits().get("3.0").pnl());
        assertThat(book.stopHits()).isEmpty();

        // 当天已完成一轮：不再开新仓
        assertThat(BtcDailyRules.onBar(plan, book, bar(DAY, 4, 58_000, 58_000, 50_000, 51_000), P)).isEmpty();
    }

    @Test
    void carriedPositionUsesLowerTargetAndContinuesLevels() {
        Book book = new Book();
        DailyPlan d1 = BtcDailyRules.plan(DAY, daily(DAY.minusDays(1), 60, 60_000, 2_000), 60_000, book, P);
        BtcDailyRules.onBar(d1, book, bar(DAY, 0, 60_000, 60_000, 58_700, 58_750), P);
        assertThat(book.buys()).isEqualTo(1);

        LocalDate d2day = DAY.plusDays(1);
        DailyPlan d2 = BtcDailyRules.plan(d2day, daily(DAY, 60, 58_750, 2_000), 58_750, book, P);
        assertThat(d2.carried()).isTrue();
        assertThat(d2.levels()).extracting(DailyPlan.Level::index).containsExactly(1, 2);
        assertThat(d2.levels().getFirst().price()).isCloseTo(book.lastBuyPrice() - 1000, within(0.01));
        assertThat(BtcDailyRules.takeProfitPct(d2, book, P)).isEqualTo(P.carryTpPct());

        double tp = BtcDailyRules.takeProfitPrice(d2, book, P);
        List<Fill> f = BtcDailyRules.onBar(d2, book, bar(d2day, 0, 58_750, tp, 58_600, tp), P);
        assertThat(f).singleElement().satisfies(s -> {
            assertThat(s.reason()).isEqualTo("跨天回本止盈");
            assertThat(s.pnl()).isPositive();
        });
    }

    @Test
    void sameBarTakeProfitBlocksNewBuys() {
        Book book = new Book();
        DailyPlan plan = BtcDailyRules.plan(DAY, daily(DAY.minusDays(1), 60, 60_000, 2_000), 60_000, book, P);
        BtcDailyRules.onBar(plan, book, bar(DAY, 0, 60_000, 60_000, 58_700, 58_750), P);
        double tp = BtcDailyRules.takeProfitPrice(plan, book, P);
        List<Fill> f = BtcDailyRules.onBar(plan, book, bar(DAY, 1, 58_750, tp + 10, 57_000, 58_000), P);
        assertThat(f).extracting(Fill::side).containsExactly(Fill.Side.SELL);
    }

    @Test
    void backtestReportsDrawdownAndHoldDays() {
        LocalDate start = LocalDate.parse("2026-01-01");
        List<Kline> d = new ArrayList<>(daily(start.minusDays(1), 60, 60_000, 2_000));
        List<Kline> hourly = new ArrayList<>();
        double price = 60_000;
        for (int day = 0; day < 25; day++) {
            LocalDate dd = start.plusDays(day);
            long t = BtcDailyRules.dayStart(dd);
            // 前 10 天每天跌 3%，后 15 天每天涨 3%（被套后回本卖出）
            double change = day < 10 ? -0.03 : 0.03;
            double open = price;
            double close = price * (1 + change);
            d.add(new Kline(t, open, Math.max(open, close) * 1.002, Math.min(open, close) * 0.998, close, 1e9, t + 86_399_999L));
            for (int h = 0; h < 24; h++) {
                double o = open + (close - open) * h / 24, c = open + (close - open) * (h + 1) / 24;
                hourly.add(new Kline(t + h * 3_600_000L, o, Math.max(o, c) * 1.001, Math.min(o, c) * 0.999, c, 1e8,
                        t + (h + 1) * 3_600_000L - 1));
            }
            price = close;
        }
        BtcDailyBacktest.Result r = BtcDailyBacktest.run(d, hourly, P, null);
        assertThat(r.days()).isEqualTo(25);
        assertThat(r.maxFloatLoss()).isNegative();
        assertThat(r.maxHoldDays()).isGreaterThan(0);
        assertThat(r.rounds()).isGreaterThan(0);
        assertThat(r.realized()).isPositive();
        assertThat(r.maxDrawdown()).isPositive();
        assertThat(r.winRate()).isEqualTo(100.0);
        assertThat(r.exitMode()).isEqualTo("FIXED");
        // 同一段行情：8% 止损会在下跌中卖出（亏损轮），移动止盈不会
        BtcDailyBacktest.Result stopped = BtcDailyBacktest.run(d, hourly, P.withExitMode("STOP"), null);
        assertThat(stopped.losses()).isPositive();
        assertThat(BtcDailyBacktest.run(d, hourly, P.withExitMode("TRAIL"), null).losses()).isZero();
        // V3：下跌行情不挂单，浮亏比 V2 小，不卖亏；按行情统计
        BtcDailyBacktest.Result v3 = BtcDailyBacktest.run(d, hourly, hourly, V3, null);
        assertThat(v3.strategy()).isEqualTo("V3");
        assertThat(v3.regimeDays()).containsKey("DOWN");
        assertThat(v3.losses()).isZero();
        assertThat(v3.maxFloatLoss()).isGreaterThan(r.maxFloatLoss());
        java.util.Map<String, BtcDailyBacktest.Result> runs = new java.util.LinkedHashMap<>();
        runs.put("V2", r);
        runs.put("V3", v3);
        assertThat(BtcDailyMessages.strategies(runs, BtcDailyProperties.defaults(), "V3"))
                .contains("现行 V2", "新 V3", "按开仓行情", "行情天数", "改用 V3");
    }

    @Test
    void filtersSkipOrShrinkNewRounds() {
        List<Kline> d = daily(DAY.minusDays(1), 60, 60_000, 2_000);
        MarketContext calm = MarketContext.empty(DAY);
        assertThat(BtcDailyRules.plan(DAY, d, 60_000, new Book(), P, calm).multiplier()).isEqualTo(1.0);

        MarketContext greedy = new MarketContext(DAY, null, 0.05, 85.0, DAY.minusDays(2), -400.0, -500.0, null, null,
                null, List.of());
        DailyPlan shrunk = BtcDailyRules.plan(DAY, d, 60_000, new Book(), P, greedy);
        assertThat(shrunk.multiplier()).isEqualTo(0.25); // 三条减半，封底 1/4
        assertThat(shrunk.levels()).extracting(DailyPlan.Level::usdt).containsExactly(750.0, 750.0, 1000.0);
        assertThat(shrunk.reasons()).contains("资金费率", "恐惧贪婪", "ETF");

        MarketContext fomc = new MarketContext(DAY, "美联储议息", null, null, null, null, null, null, null, null,
                List.of("资金费率"));
        DailyPlan skipped = BtcDailyRules.plan(DAY, d, 60_000, new Book(), P, fomc);
        assertThat(skipped.skipNew()).isTrue();
        assertThat(skipped.levels()).isEmpty();
        assertThat(skipped.reasons()).contains("美联储议息日", "数据缺失：资金费率");
    }

    @Test
    void liveMissingDataNeverMeansFullSize() {
        List<Kline> d = daily(DAY.minusDays(1), 60, 60_000, 2_000);
        BtcDailyProperties.Filters f = P.filters();
        // ETF 资金流拿不到：3 日大额流出这条检查做不了 → 不开新一轮
        MarketContext noEtf = new MarketContext(DAY, null, 0.01, 50.0, null, null, null, null, null, null,
                List.of(MarketContext.GAP_ETF, MarketContext.GAP_NO_FRED));
        DailyPlan p1 = BtcDailyRules.plan(DAY, d, 60_000, new Book(), P, noEtf);
        assertThat(p1.levels()).isNotEmpty(); // 原规则（回测口径）会开
        DailyPlan g1 = BtcDailyRules.guardMissingData(p1, noEtf.missingDataGate(f), f.minMultiplier());
        assertThat(g1.skipNew()).isTrue();
        assertThat(g1.levels()).isEmpty();
        assertThat(g1.reasons()).contains("数据缺失（ETF 资金流）：不开新一轮");

        // 资金费率缺失：减半；没配 FRED 但 CPI 日程已知不算缺数据
        MarketContext noFunding = new MarketContext(DAY, null, null, 50.0, DAY.minusDays(2), 10.0, 20.0, null, null,
                null, List.of(MarketContext.GAP_FUNDING, MarketContext.GAP_NO_FRED));
        DailyPlan p2 = BtcDailyRules.plan(DAY, d, 60_000, new Book(), P, noFunding);
        DailyPlan g2 = BtcDailyRules.guardMissingData(p2, noFunding.missingDataGate(f), f.minMultiplier());
        assertThat(g2.multiplier()).isEqualTo(0.5);
        assertThat(g2.levels()).extracting(DailyPlan.Level::usdt)
                .containsExactlyElementsOf(p2.levels().stream().map(l -> l.usdt() / 2).toList());

        // 数据齐全：不变
        MarketContext full = new MarketContext(DAY, null, 0.01, 50.0, DAY.minusDays(2), 10.0, 20.0, null, null, null,
                List.of(MarketContext.GAP_NO_FRED));
        DailyPlan p3 = BtcDailyRules.plan(DAY, d, 60_000, new Book(), P, full);
        assertThat(BtcDailyRules.guardMissingData(p3, full.missingDataGate(f), f.minMultiplier())).isSameAs(p3);
    }

    @Test
    void cpiCalendarFromConfigWithoutFred() {
        BtcDailyProperties.Filters f = new BtcDailyProperties.Filters(true, 50, 5, 0.03, 80, 300, 1000, 0.2, 1.5, 0.25,
                List.of(), List.of("2026-10-14"), "", null, null, null, null);
        assertThat(f.cpiCalendarKnown(LocalDate.parse("2026-11-01"))).isTrue();
        assertThat(f.cpiCalendarKnown(LocalDate.parse("2027-01-05"))).isFalse();
    }

    @Test
    void downtrendBlocksNewRoundButNotCarriedAdds() {
        List<Kline> d = new ArrayList<>();
        for (int i = 0; i < 60; i++) { // 每天跌 1%
            long t = BtcDailyRules.dayStart(DAY.minusDays(60 - i));
            double c = 80_000 * Math.pow(0.99, i);
            d.add(new Kline(t, c * 1.01, c * 1.02, c * 0.99, c, 1e9, t + 86_399_999L));
        }
        MarketContext calm = MarketContext.empty(DAY);
        DailyPlan fresh = BtcDailyRules.plan(DAY, d, d.getLast().close(), new Book(), P, calm);
        assertThat(fresh.skipNew()).isTrue();
        assertThat(fresh.levels()).isEmpty();

        Book holding = new Book();
        holding.qty = 0.05;
        holding.cost = 3000;
        holding.buys = 1;
        holding.lastBuyPrice = 60_000;
        holding.openedDay = DAY.minusDays(3);
        DailyPlan carried = BtcDailyRules.plan(DAY, d, d.getLast().close(), holding, P, calm);
        assertThat(carried.levels()).extracting(DailyPlan.Level::index).containsExactly(1, 2);
    }

    @Test
    void parsesFarsideTable() {
        String html = """
                <table><tr><th>Date</th><th>IBIT</th><th>Total</th></tr>
                <tr><td><span class="tabletext">11 Jan 2024</span></td><td>111.7</td><td>655.3</td></tr>
                <tr><td>12 Jan 2024</td><td>(10.0)</td><td>(1,234.5)</td></tr>
                <tr><td>15 Oct 2026</td><td>-</td><td>-</td></tr>
                <tr><td>Total</td><td>1</td><td>2</td></tr></table>""";
        assertThat(ContextSources.parseFarside(html)).containsExactly(
                java.util.Map.entry(LocalDate.parse("2024-01-11"), 655.3),
                java.util.Map.entry(LocalDate.parse("2024-01-12"), -1234.5));
    }

    @Test
    void gapBelowFirstLevelKeepsHalfAtrBetweenActualFills() {
        // 复现 2026-10-14：开盘已跌到第 1 档下方、离第 2 档只差约 200，同一根 K 线又碰到第 2 档计划价
        Book book = new Book();
        DailyPlan plan = BtcDailyRules.plan(DAY, daily(DAY.minusDays(1), 60, 60_000, 2_000), 60_000, book, P);
        double l2 = plan.levels().get(1).price(); // 57,800（第 1 档 58,800）
        List<Fill> f = BtcDailyRules.onBar(plan, book, bar(DAY, 10, l2 + 200, l2 + 250, l2 - 10, l2 + 100), P);
        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.level()).isZero();
            assertThat(x.reason()).contains("按开盘价成交");
            assertThat(x.posCost()).isEqualTo(3000.0);
        });
        // 第 2 档要比实际成交价再低 0.5 × ATR（1,000）
        double next = book.lastBuyPrice() - 1000;
        assertThat(BtcDailyRules.onBar(plan, book, bar(DAY, 11, l2, l2, next + 5, next + 50), P)).isEmpty();
        f = BtcDailyRules.onBar(plan, book, bar(DAY, 12, next + 50, next + 60, next - 5, next), P);
        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.level()).isEqualTo(1);
            assertThat(x.posCost()).isEqualTo(6000.0);
        });
    }

    @Test
    void fillMessageShowsPositionAfterThatFill() {
        Book book = new Book();
        DailyPlan plan = BtcDailyRules.plan(DAY, daily(DAY.minusDays(1), 60, 60_000, 2_000), 60_000, book, P);
        List<Fill> f = BtcDailyRules.onBar(plan, book, bar(DAY, 0, 60_000, 60_000, 57_700, 57_900), P);
        assertThat(f).hasSize(2);
        assertThat(BtcDailyMessages.fill(f.get(0), book, plan, null, P)).contains("成本 3,000.00");
        assertThat(BtcDailyMessages.fill(f.get(1), book, plan, null, P)).contains("成本 6,000.00");
    }

    @Test
    void trailingExitLetsWinnersRunButNeverBelowFloor() {
        BtcDailyProperties trail = P.withExitMode("TRAIL");
        Book book = new Book();
        DailyPlan plan = BtcDailyRules.plan(DAY, daily(DAY.minusDays(1), 60, 60_000, 2_000), 60_000, book, trail);
        BtcDailyRules.onBar(plan, book, bar(DAY, 0, 60_000, 60_000, 58_700, 58_750), trail);
        double arm = BtcDailyRules.takeProfitPrice(plan, book, trail);
        // 碰到止盈线：不卖，开始跟踪
        assertThat(BtcDailyRules.onBar(plan, book, bar(DAY, 1, 58_750, arm + 10, 58_700, arm), trail)).isEmpty();
        // 继续涨：仍不卖
        assertThat(BtcDailyRules.onBar(plan, book, bar(DAY, 2, arm + 100, arm + 1500, arm + 100, arm + 1400), trail)).isEmpty();
        // 从最高价回落超过 0.5 × ATR（1,000）：卖出，赚得比固定止盈多
        double peak = arm + 1500;
        List<Fill> f = BtcDailyRules.onBar(plan, book, bar(DAY, 3, peak - 500, peak - 400, peak - 1200, peak - 1100), trail);
        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.reason()).isEqualTo("移动止盈");
            assertThat(x.price()).isGreaterThan(arm);
        });
    }

    @Test
    void trailingNeverSellsBelowTakeProfitEvenOnGap() {
        BtcDailyProperties trail = P.withExitMode("TRAIL");
        Book book = new Book();
        DailyPlan plan = BtcDailyRules.plan(DAY, daily(DAY.minusDays(1), 60, 60_000, 2_000), 60_000, book, trail);
        BtcDailyRules.onBar(plan, book, bar(DAY, 0, 60_000, 60_000, 58_700, 58_750), trail);
        double arm = BtcDailyRules.takeProfitPrice(plan, book, trail);
        assertThat(BtcDailyRules.onBar(plan, book, bar(DAY, 1, 58_750, arm + 10, 58_700, arm), trail)).isEmpty();
        // 跳空到止盈价下方且没回到止盈价：不卖亏
        assertThat(BtcDailyRules.onBar(plan, book, bar(DAY, 2, arm - 800, arm - 700, arm - 900, arm - 750), trail)).isEmpty();
        // 回到止盈价：按止盈价卖出，不比 FIXED 低
        List<Fill> f = BtcDailyRules.onBar(plan, book, bar(DAY, 3, arm - 300, arm + 50, arm - 400, arm), trail);
        assertThat(f).singleElement().satisfies(x -> assertThat(x.price()).isGreaterThan(arm * 0.999).isLessThan(arm + 1));
        assertThat(f.getFirst().pnl()).isPositive();
    }

    @Test
    void carriedPositionTrailsAfterBreakEven() {
        BtcDailyProperties trail = P.withExitMode("TRAIL");
        Book book = new Book();
        DailyPlan plan = BtcDailyRules.plan(DAY, daily(DAY.minusDays(1), 60, 60_000, 2_000), 60_000, book, trail);
        BtcDailyRules.onBar(plan, book, bar(DAY, 0, 60_000, 60_000, 58_700, 58_750), trail);
        LocalDate next = DAY.plusDays(1);
        DailyPlan plan2 = BtcDailyRules.plan(next, daily(DAY, 60, 60_000, 2_000), 58_800, book, trail);
        double be = book.avgCost() * (1 + P.carryTpPct() / 100);
        assertThat(BtcDailyRules.takeProfitPrice(plan2, book, trail)).isEqualTo(be);
        // 回本（+0.3%）后不卖，继续涨
        assertThat(BtcDailyRules.onBar(plan2, book, bar(next, 0, 58_800, be + 5, 58_790, be), trail)).isEmpty();
        assertThat(BtcDailyRules.onBar(plan2, book, bar(next, 1, be + 100, be + 2000, be + 100, be + 1900), trail)).isEmpty();
        List<Fill> f = BtcDailyRules.onBar(plan2, book, bar(next, 2, be + 1900, be + 1900, be + 500, be + 600), trail);
        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.reason()).isEqualTo("回本后跟踪止盈");
            assertThat(x.price()).isGreaterThan(be + 500);
        });
    }

    @Test
    void partialSellsHalfThenTrailsRestAcrossDays() {
        BtcDailyProperties part = P.withExitMode("PARTIAL");
        Book book = new Book();
        DailyPlan plan = BtcDailyRules.plan(DAY, daily(DAY.minusDays(1), 60, 60_000, 2_000), 60_000, book, part);
        BtcDailyRules.onBar(plan, book, bar(DAY, 0, 60_000, 60_000, 58_700, 58_750), part);
        double qty = book.qty();
        double arm = BtcDailyRules.takeProfitPrice(plan, book, part);
        List<Fill> f = BtcDailyRules.onBar(plan, book, bar(DAY, 1, 58_750, arm + 10, 58_700, arm), part);
        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.side()).isEqualTo(Fill.Side.SELL);
            assertThat(x.qty()).isCloseTo(qty / 2, within(1e-9));
            assertThat(x.pnl()).isPositive();
        });
        assertThat(book.qty()).isCloseTo(qty / 2, within(1e-9));
        assertThat(book.rounds()).isZero();
        assertThat(BtcDailyMessages.fill(f.getFirst(), book, plan, null, part)).contains("部分卖出", "继续跟踪");
        // 次日：跟踪中不加仓，大涨 10% 继续拿着
        LocalDate next = DAY.plusDays(1);
        DailyPlan plan2 = BtcDailyRules.plan(next, daily(DAY, 60, 60_000, 2_000), arm, book, part);
        assertThat(plan2.levels()).isEmpty();
        double high = arm * 1.10;
        assertThat(BtcDailyRules.onBar(plan2, book, bar(next, 0, arm, arm, 59_100, arm), part)).isEmpty(); // 跟踪中不买
        assertThat(BtcDailyRules.onBar(plan2, book, bar(next, 1, arm + 100, high, arm + 100, high - 100), part)).isEmpty();
        // 从最高价回落 1 × ATR（2,000）：剩下的全卖，整轮盈亏 = 两次合计
        List<Fill> g = BtcDailyRules.onBar(plan2, book, bar(next, 2, high - 100, high - 100, high - 2500, high - 2400), part);
        assertThat(g).singleElement().satisfies(x -> assertThat(x.price()).isGreaterThan(high - 2100));
        assertThat(book.holding()).isFalse();
        assertThat(book.lastRound.pnl()).isCloseTo(f.getFirst().pnl() + g.getFirst().pnl(), within(1e-6));
        assertThat(book.lastRound.cost()).isCloseTo(3000, within(1e-6));
        assertThat(book.realized()).isCloseTo(book.lastRound.pnl(), within(1e-6));
    }

    static final BtcDailyProperties V3 = BtcDailyProperties.defaults().withStrategy("V3");

    /** n 根日线：收盘从 start 起每天变化 step，振幅 range。 */
    static List<Kline> dailyTrend(LocalDate lastDay, int n, double start, double step, double range) {
        List<Kline> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long t = BtcDailyRules.dayStart(lastDay.minusDays(n - 1 - i));
            double c = start + step * i;
            out.add(new Kline(t, c - step, c + range / 2, c - range / 2, c, 1e9, t + 86_399_999L));
        }
        return out;
    }

    /** day 之前 n 根 1h K 线：收盘 close，振幅 range。 */
    static List<Kline> hourlyFlat(LocalDate day, int n, double close, double range) {
        long start = BtcDailyRules.dayStart(day);
        List<Kline> out = new ArrayList<>();
        for (int i = n; i > 0; i--) {
            long t = start - i * 3_600_000L;
            out.add(new Kline(t, close, close + range / 2, close - range / 2, close, 1e8, t + 3_599_999L));
        }
        return out;
    }

    @Test
    void v3UptrendPlacesSupportLevelsFullSize() {
        List<Kline> d = dailyTrend(DAY.minusDays(1), 60, 50_000, 200, 2_000);
        double c = d.getLast().close();
        DailyPlan p = BtcDailyRules.planV3(DAY, d, hourlyFlat(DAY, 72, c, 400), c + 500, new Book(), V3, null);
        assertThat(p.regime()).isEqualTo("UP");
        assertThat(p.hourAtr()).isCloseTo(400, within(1e-6));
        assertThat(p.levels()).extracting(DailyPlan.Level::usdt).containsExactly(3000.0, 3000.0, 4000.0);
        List<Double> prices = p.levels().stream().map(DailyPlan.Level::price).toList();
        assertThat(prices.getFirst()).isCloseTo(c, within(1.0)); // 1h EMA20 / VWAP
        assertThat(prices.get(0) - prices.get(1)).isGreaterThanOrEqualTo(0.3 * p.atr() - 0.01);
        assertThat(prices.get(1) - prices.get(2)).isGreaterThanOrEqualTo(0.3 * p.atr() - 0.01);
        assertThat(p.addFloor()).isCloseTo(p.ema(), within(0.01));
    }

    @Test
    void v3SidewaysDeepHalfAndDowntrendNone() {
        DailyPlan side = BtcDailyRules.planV3(DAY, daily(DAY.minusDays(1), 60, 60_000, 2_000),
                hourlyFlat(DAY, 72, 60_000, 400), 60_000, new Book(), V3, null);
        assertThat(side.regime()).isEqualTo("SIDE");
        assertThat(side.levels()).extracting(DailyPlan.Level::price).containsExactly(57_000.0, 56_000.0, 55_000.0);
        assertThat(side.levels()).extracting(DailyPlan.Level::usdt).containsExactly(1500.0, 1500.0, 2000.0);
        List<Kline> down = dailyTrend(DAY.minusDays(1), 60, 70_000, -200, 2_000);
        DailyPlan p = BtcDailyRules.planV3(DAY, down, hourlyFlat(DAY, 72, down.getLast().close(), 400),
                down.getLast().close(), new Book(), V3, null);
        assertThat(p.regime()).isEqualTo("DOWN");
        assertThat(p.levels()).isEmpty();
        assertThat(p.skipNew()).isTrue();
        assertThat(BtcDailyMessages.plan(p, new Book(), java.util.Map.of("buys", 0, "sells", 0, "pnl", 0), V3))
                .contains("行情下跌", "不挂单");
    }

    @Test
    void v3SellsThirdThenTrailsAndAddsOnlyWhileTrendHolds() {
        DailyPlan plan = BtcDailyRules.planV3(DAY, daily(DAY.minusDays(1), 60, 60_000, 2_000),
                hourlyFlat(DAY, 72, 60_000, 400), 60_000, new Book(), V3, null);
        Book book = new Book();
        // 跌穿第 1、2 档：只买第 1 档（震荡行情加仓须在 EMA50 = 60,000 上方）
        List<Fill> buys = BtcDailyRules.onBar(plan, book, bar(DAY, 0, 60_000, 60_000, 55_900, 56_500), V3);
        assertThat(buys).singleElement().satisfies(x -> assertThat(x.level()).isZero());
        double avg = book.avgCost();
        double qty = book.qty();
        // 涨 1.5%：卖 1/3
        List<Fill> f = BtcDailyRules.onBar(plan, book, bar(DAY, 1, 56_500, avg * 1.016, 56_400, avg * 1.015), V3);
        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.qty()).isCloseTo(qty * 0.3333, within(1e-6));
            assertThat(x.reason()).contains("1/3");
        });
        // 继续涨 5%，没回落 1.5 × 400 = 600：不卖
        double high = avg * 1.06;
        assertThat(BtcDailyRules.onBar(plan, book, bar(DAY, 2, avg * 1.016, high, avg * 1.016, high - 100), V3)).isEmpty();
        // 回落超过 600：剩下的全卖，价格接近最高价 − 600
        List<Fill> g = BtcDailyRules.onBar(plan, book, bar(DAY, 3, high - 100, high - 100, high - 900, high - 800), V3);
        assertThat(g).singleElement().satisfies(x -> {
            assertThat(x.reason()).isEqualTo("跟踪止盈");
            assertThat(x.price()).isGreaterThan(high - 620);
        });
        assertThat(book.holding()).isFalse();
        assertThat(book.lastRound.regime()).isEqualTo("SIDE");
        assertThat(book.lastRound.pnl()).isCloseTo(f.getFirst().pnl() + g.getFirst().pnl(), within(1e-6));
    }

    @Test
    void v3CarriedPositionNoAddsAndTrailsAfterBreakEven() {
        DailyPlan plan = BtcDailyRules.planV3(DAY, daily(DAY.minusDays(1), 60, 60_000, 2_000),
                hourlyFlat(DAY, 72, 60_000, 400), 60_000, new Book(), V3, null);
        Book book = new Book();
        BtcDailyRules.onBar(plan, book, bar(DAY, 0, 60_000, 60_000, 56_900, 56_900), V3);
        LocalDate next = DAY.plusDays(1);
        DailyPlan p2 = BtcDailyRules.planV3(next, daily(DAY, 60, 60_000, 2_000), hourlyFlat(next, 72, 56_000, 400),
                56_000, book, V3, null);
        assertThat(p2.levels()).isEmpty();
        assertThat(BtcDailyRules.onBar(p2, book, bar(next, 0, 56_000, 56_000, 50_000, 51_000), V3)).isEmpty(); // 不止损、不加仓
        double floor = book.avgCost() * 1.003;
        // 回到保底价：启动跟踪，不立即卖
        assertThat(BtcDailyRules.onBar(p2, book, bar(next, 1, 51_000, floor + 10, 51_000, floor), V3)).isEmpty();
        assertThat(book.trailPeak()).isPositive();
        // 回落：按保底价卖，不亏
        List<Fill> f = BtcDailyRules.onBar(p2, book, bar(next, 2, floor, floor, floor - 300, floor - 200), V3);
        assertThat(f).singleElement().satisfies(x -> assertThat(x.pnl()).isPositive());
    }

    @Test
    void v3GuardsSellStuckPositionsOnlyInBacktestVariants() {
        DailyPlan plan = BtcDailyRules.planV3(DAY, daily(DAY.minusDays(1), 60, 60_000, 2_000),
                hourlyFlat(DAY, 72, 60_000, 400), 60_000, new Book(), V3, null);
        LocalDate next = DAY.plusDays(1);
        List<Kline> down = dailyTrend(DAY, 60, 70_000, -200, 2_000);
        for (String g : List.of("NONE", "TREND", "STOP", "TIME")) {
            BtcDailyProperties p = V3.withGuard(g);
            Book book = new Book();
            BtcDailyRules.onBar(plan, book, bar(DAY, 0, 60_000, 60_000, 56_900, 56_900), p);
            DailyPlan p2 = BtcDailyRules.planV3(next, down, hourlyFlat(next, 72, 52_000, 400), 52_000, book, p, null);
            assertThat(p2.regime()).isEqualTo("DOWN");
            List<Fill> f = BtcDailyRules.onBar(p2, book, bar(next, 0, 52_000, 52_100, 51_000, 51_500), p);
            switch (g) {
                case "TREND" -> assertThat(f).singleElement().satisfies(x -> assertThat(x.reason()).isEqualTo("趋势转跌离场"));
                case "STOP" -> assertThat(f).singleElement().satisfies(x -> assertThat(x.reason()).isEqualTo("止损 8%"));
                default -> assertThat(f).isEmpty(); // NONE 不止损；TIME 未满 10 天
            }
        }
    }

    @Test
    void wideStopSellsAtEightPercentLoss() {
        BtcDailyProperties stop = P.withExitMode("STOP");
        Book book = new Book();
        DailyPlan plan = BtcDailyRules.plan(DAY, daily(DAY.minusDays(1), 60, 60_000, 2_000), 60_000, book, stop);
        BtcDailyRules.onBar(plan, book, bar(DAY, 0, 60_000, 60_000, 58_700, 58_750), stop);
        double avg = book.avgCost();
        List<Fill> f = BtcDailyRules.onBar(plan, book, bar(DAY, 1, 58_000, 58_000, avg * 0.91, avg * 0.915), stop);
        assertThat(f).extracting(Fill::side).contains(Fill.Side.SELL);
        assertThat(f.stream().filter(x -> x.side() == Fill.Side.SELL).findFirst().orElseThrow().reason()).isEqualTo("止损 8%");
    }

    @Test
    void brokenBarIsTreatedAsMissingAndNeverFills() {
        Book book = new Book();
        DailyPlan plan = BtcDailyRules.plan(DAY, daily(DAY.minusDays(1), 60, 60_000, 2_000), 60_000, book, P);
        // 缺字段的 K 线（价格都是 0）：不成交、账本不变
        assertThat(BtcDailyRules.onBar(plan, book, bar(DAY, 0, 0, 0, 0, 0), P)).isEmpty();
        assertThat(book.holding()).isFalse();
        assertThat(book.cost()).isZero();
        // 最低价 0、开盘正常：同样不成交
        assertThat(BtcDailyRules.onBar(plan, book, bar(DAY, 1, 60_000, 60_000, 0, 59_000), P)).isEmpty();
        assertThat(book.holding()).isFalse();

        // 持仓中遇到错误的最高价：不卖、不改跟踪状态
        List<Fill> f = BtcDailyRules.onBar(plan, book, bar(DAY, 2, 60_000, 60_000, 58_700, 58_900), P);
        assertThat(f).extracting(Fill::side).containsExactly(Fill.Side.BUY);
        double qty = book.qty();
        assertThat(BtcDailyRules.onBar(plan, book, bar(DAY, 3, 58_900, Double.NaN, 58_800, 58_900), P)).isEmpty();
        assertThat(book.qty()).isEqualTo(qty);
        assertThat(book.trailPeak).isEqualTo(0);
    }
}
