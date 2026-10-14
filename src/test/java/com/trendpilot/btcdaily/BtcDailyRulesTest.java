package com.trendpilot.btcdaily;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.trendpilot.market.Kline;

class BtcDailyRulesTest {

    static final BtcDailyProperties P = BtcDailyProperties.defaults();
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
        DailyPlan p = BtcDailyRules.plan(DAY, daily(DAY.minusDays(1), 30, 60_000, 2_000), 60_000, new Book(), P);
        assertThat(p.atr()).isCloseTo(2_000, within(1e-6));
        assertThat(p.strong()).isFalse(); // 昨收 = EMA，不算偏强
        assertThat(p.levels()).extracting(DailyPlan.Level::price).containsExactly(58_800.0, 57_800.0, 56_800.0);
        assertThat(p.levels()).extracting(DailyPlan.Level::usdt).containsExactly(3000.0, 3000.0, 4000.0);
        assertThat(p.tpPct()).isCloseTo(Math.max(0.5 * 2000 / 60000 * 100, 0.8), within(1e-9));
    }

    @Test
    void buysAddsAndTakesProfitWithoutStopLoss() {
        Book book = new Book();
        DailyPlan plan = BtcDailyRules.plan(DAY, daily(DAY.minusDays(1), 30, 60_000, 2_000), 60_000, book, P);

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

        // 当天已完成一轮：不再开新仓
        assertThat(BtcDailyRules.onBar(plan, book, bar(DAY, 4, 58_000, 58_000, 50_000, 51_000), P)).isEmpty();
    }

    @Test
    void carriedPositionUsesLowerTargetAndContinuesLevels() {
        Book book = new Book();
        DailyPlan d1 = BtcDailyRules.plan(DAY, daily(DAY.minusDays(1), 30, 60_000, 2_000), 60_000, book, P);
        BtcDailyRules.onBar(d1, book, bar(DAY, 0, 60_000, 60_000, 58_700, 58_750), P);
        assertThat(book.buys()).isEqualTo(1);

        LocalDate d2day = DAY.plusDays(1);
        DailyPlan d2 = BtcDailyRules.plan(d2day, daily(DAY, 30, 58_750, 2_000), 58_750, book, P);
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
        DailyPlan plan = BtcDailyRules.plan(DAY, daily(DAY.minusDays(1), 30, 60_000, 2_000), 60_000, book, P);
        BtcDailyRules.onBar(plan, book, bar(DAY, 0, 60_000, 60_000, 58_700, 58_750), P);
        double tp = BtcDailyRules.takeProfitPrice(plan, book, P);
        List<Fill> f = BtcDailyRules.onBar(plan, book, bar(DAY, 1, 58_750, tp + 10, 57_000, 58_000), P);
        assertThat(f).extracting(Fill::side).containsExactly(Fill.Side.SELL);
    }

    @Test
    void backtestReportsDrawdownAndHoldDays() {
        LocalDate start = LocalDate.parse("2026-01-01");
        List<Kline> d = new ArrayList<>(daily(start.minusDays(1), 30, 60_000, 2_000));
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
        BtcDailyBacktest.Result r = BtcDailyBacktest.run(d, hourly, P);
        assertThat(r.days()).isEqualTo(25);
        assertThat(r.maxFloatLoss()).isNegative();
        assertThat(r.maxHoldDays()).isGreaterThan(0);
        assertThat(r.rounds()).isGreaterThan(0);
        assertThat(r.realized()).isPositive();
    }
}
