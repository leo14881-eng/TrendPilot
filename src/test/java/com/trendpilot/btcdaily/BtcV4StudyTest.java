package com.trendpilot.btcdaily;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.trendpilot.market.Kline;

class BtcV4StudyTest {

    private static List<List<Kline>> synthetic(long seed, double drift) {
        long day0 = LocalDate.of(2023, 1, 1).toEpochDay() * 86_400_000L;
        Random rnd = new Random(seed);
        List<Kline> bars = new ArrayList<>();
        List<Kline> daily = new ArrayList<>();
        double price = 30_000;
        for (int d = 0; d < 400; d++) {
            double o = price, h = price, l = price;
            for (int b = 0; b < 96; b++) {
                long t = day0 + d * 86_400_000L + b * 900_000L;
                double open = price;
                price *= 1 + drift + rnd.nextGaussian() * 0.003;
                double hi = Math.max(open, price) * 1.0005, lo = Math.min(open, price) * 0.9995;
                bars.add(new Kline(t, open, hi, lo, price, 1, t + 899_999));
                h = Math.max(h, hi);
                l = Math.min(l, lo);
            }
            long t = day0 + d * 86_400_000L;
            daily.add(new Kline(t, o, h, l, price, 1, t + 86_399_999));
        }
        return List.of(daily, bars);
    }

    @Test
    void ladderRoundsCloseOnlyInProfitAndTrackMonths() {
        List<List<Kline>> data = synthetic(7, 0);
        LocalDate from = LocalDate.of(2023, 6, 1);
        BtcDailyProperties p = BtcDailyProperties.defaults();
        BtcV4Study.Params v = new BtcV4Study.Params(0.1, 0.75, new double[]{2000, 2000, 3000, 3000}, "ATR", 7, false);
        BtcV4Study.Run r = BtcV4Study.run("t", data.get(0), data.get(1), from, v, p, null, d -> true);
        assertThat(r.rounds()).isPositive();
        assertThat(r.wins()).isEqualTo(r.rounds());           // 不止损：完成的轮都在均价以上卖出
        for (double pnl : r.roundPnls()) assertThat(pnl).isPositive();
        assertThat(r.winMonths()).isNotEmpty();
        assertThat(r.cat().values().stream().mapToDouble(c -> c[0]).sum()).isEqualTo(r.rounds());
        assertThat(BtcV4Study.monthCoverage(r.winMonths(), from, LocalDate.of(2024, 1, 31))).isBetween(0.0, 100.0);
    }

    @Test
    void neverBuysMoreThanTheLadderAndRandomZeroOpensNothing() {
        List<List<Kline>> data = synthetic(3, -0.0004); // 一路下跌：加仓用完后只能等，不卖亏
        LocalDate from = LocalDate.of(2023, 6, 1);
        BtcDailyProperties p = BtcDailyProperties.defaults();
        BtcV4Study.Params v = new BtcV4Study.Params(0, 0.5, new double[]{2000, 3000, 5000}, "FIX", 14, false);
        BtcV4Study.Run r = BtcV4Study.run("down", data.get(0), data.get(1), from, v, p, null, d -> true);
        assertThat(r.endUnrealized()).isNegative();
        assertThat(r.maxFloatLoss()).isGreaterThan(-10_000);    // 最多投入 10000 U
        BtcV4Study.Run none = BtcV4Study.run("rnd0", data.get(0), data.get(1), from, v, p, null, BtcGridStudy.randomDays(0, 1));
        assertThat(none.rounds()).isZero();
        assertThat(none.total()).isZero();
    }

    @Test
    void planStudyFamiliesNeverSellBelowCostAndRespectCap() {
        List<List<Kline>> data = synthetic(11, 0);
        BtcDailyProperties p = BtcDailyProperties.defaults();
        BtcPlanStudy.Days dd = new BtcPlanStudy.Days(data.get(0), p);
        LocalDate from = LocalDate.of(2023, 6, 1), to = LocalDate.of(2024, 1, 31);
        for (String fam : new String[]{"F2", "F3", "F4", "F5"}) {
            double e1 = switch (fam) { case "F3" -> 20; case "F4" -> 50; case "F5" -> 5; default -> 0.1; };
            for (String exit : new String[]{"BASE", "FIX", "TRAIL"}) {
                BtcPlanStudy.Cfg c = new BtcPlanStudy.Cfg(fam, e1, fam.equals("F3") ? 2 : 0.5, 6, "INC", "INC", "PROFIT",
                        exit, exit.equals("FIX") ? 5 : -1, 1, 14, 10_000);
                BtcPlanStudy.Run r = BtcPlanStudy.run(c, dd, data.get(1), from, to, p, d -> true);
                for (double pnl : r.roundPnls()) assertThat(pnl).isPositive();
                assertThat(r.utilization()).isBetween(0.0, 1.0);
                assertThat(r.cat().values().stream().mapToDouble(v -> v[0]).sum()).isEqualTo(r.rounds());
            }
        }
        BtcPlanStudy.Cfg rnd = new BtcPlanStudy.Cfg("RND", 0, 0, 10, "C1", "EQ", "STOP", "BASE", 0, 0, 0, 10_000);
        assertThat(BtcPlanStudy.run(rnd, dd, data.get(1), from, to, p, BtcGridStudy.randomDays(0, 1)).opened()).isZero();
        assertThat(BtcPlanStudy.run(rnd, dd, data.get(1), from, to, p, d -> true).opened()).isPositive();
    }

    @Test
    void minimumSellPriceCoversFeeAndMargin() {
        double avg = 80_000, fee = 0.001, slip = 0.0002;
        double floor = BtcPlanStudy.minSellPrice(avg, fee, slip);
        assertThat(floor * (1 - slip)).isGreaterThanOrEqualTo(avg * (1 + fee + 0.003) - 1e-6);
        assertThat(floor * (1 - slip) * (1 - fee)).isGreaterThan(avg);   // 扣完卖出手续费仍高于含费均价
    }

    @Test
    void deepDownTrendWithAllModesNeverSellsAtLoss() {
        List<List<Kline>> data = synthetic(5, -0.0006);
        BtcDailyProperties p = BtcDailyProperties.defaults();
        BtcPlanStudy.Days dd = new BtcPlanStudy.Days(data.get(0), p);
        LocalDate from = LocalDate.of(2023, 6, 1), to = LocalDate.of(2024, 2, 1);
        for (String exit : new String[]{"BASE", "ATR", "FIX", "HH", "TRAIL"})
            for (int d : new int[]{0, 14, 30})
                for (String after : new String[]{"STOP", "PROFIT"}) {
                    BtcPlanStudy.Cfg c = new BtcPlanStudy.Cfg("F3", 20, 2, 10, "INC", "EQ", after, exit,
                            exit.equals("TRAIL") ? 3 : 1, 2, d, 50_000);
                    BtcPlanStudy.Run r = BtcPlanStudy.run(c, dd, data.get(1), from, to, p, x -> true); // 亏损卖出会直接抛异常
                    for (double pct : r.roundPcts()) assertThat(pct).isGreaterThan(0.1);
                }
    }
}
