package com.trendpilot.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.util.List;

import org.junit.jupiter.api.Test;

class ExitReplayTest {

    final ExitReplay replay = new ExitReplay(null, null, PathCalculatorTest.P, null, "Asia/Ho_Chi_Minh");

    ExitReplay.Ep ep(double... closes) {
        return new ExitReplay.Ep(1, "SIGNAL", PathCalculatorTest.T0, PathCalculatorTest.COST, 2.0, "2026-10-14",
                PathCalculatorTest.bars(closes));
    }

    @Test
    void stopLossBeatsBaselineOnASteadyDecline() {
        double[] path = new double[300];
        for (int i = 0; i < path.length; i++) path[i] = 100 - i * 0.1; // 一路跌到 70
        ExitReplay.Ep e = ep(path);
        ExitReplay.Trade b0 = replay.simulate(e, null, "CALIB");
        ExitReplay.Trade s1 = replay.simulate(e, ExitRule.byId("S1_L10"), "CALIB");
        assertThat(b0.reason()).isEqualTo("WINDOW_END");
        assertThat(s1.reason()).isEqualTo("S1_L10");
        assertThat(s1.netReturn()).isCloseTo(-10, offset(0.2));
        assertThat(s1.netReturn()).isGreaterThan(b0.netReturn());
    }

    @Test
    void lowTriggerFillsAtOpenWhenGappingThroughTheStop() {
        double[] path = {100, 100, 100, 80, 80};
        List<PathCalculator.Bar> bars = PathCalculatorTest.bars(path);
        ExitReplay.Ep e = new ExitReplay.Ep(1, "SIGNAL", PathCalculatorTest.T0, PathCalculatorTest.COST, 2.0,
                "2026-10-14", bars);
        ExitReplay.Trade t = replay.simulate(e, ExitRule.byId("S1_L10@L"), "VALID");
        assertThat(t.exitPrice()).isEqualTo(80); // 跳空：按开盘价，不是止损价
    }

    @Test
    void timeStopAndProtection() {
        double[] flat = new double[100];
        java.util.Arrays.fill(flat, 99.5);
        ExitReplay.Trade t1 = replay.simulate(ep(flat), ExitRule.byId("T1_T12_g0"), "CALIB");
        assertThat(t1.reason()).isEqualTo("T1_T12_g0");
        assertThat(t1.holdingMin()).isBetween(12 * 60, 12 * 60 + 15);

        // 涨 5% 后回落到 +2%：现行利润保护在底线（最高净收益 − 2%）卖出
        double[] up = {101, 103, 105, 105, 104, 103, 102.5};
        ExitReplay.Trade b0 = replay.simulate(ep(up), null, "CALIB");
        assertThat(b0.reason()).isEqualTo("PROFIT_PROTECTION");
        assertThat(ExitRule.grid()).hasSize(52);
        assertThat(ExitRule.byId("M1_D5").check(new ExitRule.State(-3, -3, 2, 0, 3, 1, -5.5))).isEqualTo("M1_D5");
        assertThat(ExitRule.byId("M1_D5").check(new ExitRule.State(-3, -3, 2, 0, 3, 1, -4))).isNull();
        assertThat(ExitRule.byId("M1_D5").check(new ExitRule.State(-3, -3, 2, 0, 3, 1))).isNull();
    }
}
