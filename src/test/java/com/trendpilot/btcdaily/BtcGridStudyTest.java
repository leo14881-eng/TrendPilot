package com.trendpilot.btcdaily;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.trendpilot.market.Kline;

class BtcGridStudyTest {

    @Test
    void gridAndRandomBaselineRunOnSyntheticPrices() {
        long day0 = LocalDate.of(2023, 1, 1).toEpochDay() * 86_400_000L;
        Random rnd = new Random(7);
        List<Kline> bars = new ArrayList<>();
        List<Kline> daily = new ArrayList<>();
        double price = 30_000;
        for (int d = 0; d < 400; d++) {
            double o = price, h = price, l = price;
            for (int b = 0; b < 96; b++) {
                long t = day0 + d * 86_400_000L + b * 900_000L;
                double open = price;
                price *= 1 + rnd.nextGaussian() * 0.003;
                double hi = Math.max(open, price) * 1.0005, lo = Math.min(open, price) * 0.9995;
                bars.add(new Kline(t, open, hi, lo, price, 1, t + 899_999));
                h = Math.max(h, hi);
                l = Math.min(l, lo);
            }
            long t = day0 + d * 86_400_000L;
            daily.add(new Kline(t, o, h, l, price, 1, t + 86_399_999));
        }
        LocalDate from = LocalDate.of(2023, 6, 1);
        BtcDailyProperties cur = BtcGridStudy.params(BtcDailyProperties.defaults(), 0.25, 0.6, 0.5, 0.5, true);
        BtcGridStudy.Run r = BtcGridStudy.run("cur", daily, bars, from, cur, BtcGridStudy.filterContext(cur));
        assertThat(r.days()).isGreaterThan(200);
        assertThat(r.rounds()).isGreaterThan(0);
        assertThat(r.cat().values().stream().mapToDouble(v -> v[0]).sum()).isEqualTo(r.rounds());

        BtcDailyProperties atOpen = BtcGridStudy.params(cur, 0, 0, 0.5, 0.5, true);
        BtcGridStudy.Run none = BtcGridStudy.run("rnd0", daily, bars, from, atOpen, BtcGridStudy.filterContext(atOpen), BtcGridStudy.randomDays(0, 1));
        assertThat(none.tradeDays()).isZero();
        BtcGridStudy.Run all = BtcGridStudy.run("rnd1", daily, bars, from, atOpen, null, BtcGridStudy.randomDays(1, 1));
        assertThat(all.tradeDays()).isPositive();

        double[] t = BtcGridStudy.touchRate(daily, from, 0.25, cur);
        double[] t2 = BtcGridStudy.touchRate(daily, from, 0.6, cur);
        assertThat(t[0]).isGreaterThan(t2[0]);
    }
}
