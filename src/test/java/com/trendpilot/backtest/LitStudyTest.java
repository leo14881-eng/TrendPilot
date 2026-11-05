package com.trendpilot.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

/** Literature alt strategies (research backtest only, never traded live). */
class LitStudyTest {

    static final LocalDate SUNDAY = LocalDate.of(2023, 1, 1); // a Sunday

    /** n coins + BTC, each with its own constant daily growth; volume decreasing with index so pool order = index order. */
    static LitStudy.Panel panel(int days, double... growth) {
        int n = growth.length + 1;
        String[] syms = new String[n];
        double[][] c = new double[n][days], v = new double[n][days];
        syms[0] = "BTCUSDT";
        for (int i = 1; i < n; i++) syms[i] = "C" + (char) ('A' + i) + "USDT";
        for (int i = 0; i < n; i++) {
            double g = i == 0 ? 0.001 : growth[i - 1];
            for (int d = 0; d < days; d++) {
                // small alternating wiggle so volatility is never zero
                c[i][d] = 100 * Math.pow(1 + g, d) * (d % 2 == 0 ? 1 : 1 + 0.002 * (i + 1));
                v[i][d] = 1e6 * (n - i);
            }
        }
        return new LitStudy.Panel(SUNDAY, syms, c, v, Set.of());
    }

    @Test
    void capWeightsRedistributesExcessAndLeavesCashWhenEverythingIsCapped() {
        double[] w = LitStudy.capWeights(new double[]{10, 1, 1, 1, 1}, 0.25);
        assertThat(w[0]).isCloseTo(0.25, offset(1e-9));
        assertThat(Arrays.stream(w).sum()).isCloseTo(1.0, offset(1e-9));
        assertThat(w[1]).isCloseTo(0.1875, offset(1e-9));
        double[] two = LitStudy.capWeights(new double[]{1, 1}, 0.25);
        assertThat(Arrays.stream(two).sum()).isCloseTo(0.5, offset(1e-9));
    }

    @Test
    void poolExcludesBtcStablecoinsAndLeveragedTokens() {
        String[] syms = {"BTCUSDT", "ETHUSDT", "USDCUSDT", "ETHUPUSDT", "FDUSDUSDT"};
        double[][] c = new double[5][40], v = new double[5][40];
        for (double[] x : c) Arrays.fill(x, 1);
        for (double[] x : v) Arrays.fill(x, 1e6);
        LitStudy.Panel p = new LitStudy.Panel(SUNDAY, syms, c, v, Set.of());
        assertThat(p.pool(35, 40)).containsExactly(1);
    }

    @Test
    void rotationBuysTheStrongestRiskAdjustedCoinsAndTheFilterMovesToCash() {
        LitStudy.Panel p = panel(260, 0.01, 0.005, -0.002, 0.003, 0.0, 0.008, -0.01);
        Map<Integer, LitStudy.Choice> m = LitStudy.rotation(p, 220, 250, 21, false);
        LitStudy.Choice c = m.values().iterator().next();
        int[] top = c.top();
        assertThat(top).hasSize(5);
        assertThat(p.syms[c.cand()[top[0]]]).isEqualTo(p.syms[1]);
        double[] w = c.weights(top);
        assertThat(Arrays.stream(w).sum()).isCloseTo(1.0, offset(1e-9));
        assertThat(Arrays.stream(w).max().getAsDouble()).isLessThanOrEqualTo(0.25 + 1e-9);

        // falling BTC: below its 200-day average -> no picks
        double[][] cl = new double[2][260], v = new double[2][260];
        for (int d = 0; d < 260; d++) {
            cl[0][d] = 100 * Math.pow(0.995, d) * (d % 2 == 0 ? 1 : 1.002);
            cl[1][d] = 100 * Math.pow(1.01, d) * (d % 2 == 0 ? 1 : 1.003);
            v[0][d] = v[1][d] = 1e6;
        }
        LitStudy.Panel down = new LitStudy.Panel(SUNDAY, new String[]{"BTCUSDT", "XYZUSDT"}, cl, v, Set.of());
        assertThat(LitStudy.rotation(down, 220, 250, 21, true).values()).allMatch(x -> x.k() == 0);
    }

    @Test
    void weeklyBookChargesCostsOnTurnoverOnly() {
        LitStudy.Panel p = panel(60, 0, 0, 0, 0, 0, 0);
        Map<Integer, LitStudy.Choice> m = LitStudy.equalPool(p, 35, 49, 40);
        LitStudy.Curve c = LitStudy.runWeekly(p, 35, 49, 1, m, null);
        // flat prices (wiggle aside): first rebalance costs 0.15% of the book, later rebalances only trade the drift
        assertThat(c.nav()[0]).isCloseTo(1 - LitStudy.COST, offset(1e-3));
        assertThat(c.opens()).isEqualTo(6);
    }

    @Test
    void donchianEntersOnBreakoutAndExitsWhenCloseFallsBelowTheRatchetedMidpoint() {
        int days = 120;
        double[][] c = new double[2][days], v = new double[2][days];
        for (int d = 0; d < days; d++) {
            c[0][d] = 100;
            // flat with wiggle, breakout up from day 60, collapse from day 90
            double base = d < 60 ? 100 * (d % 2 == 0 ? 1 : 1.01) : d < 90 ? 100 * Math.pow(1.02, d - 59) : 100 * Math.pow(1.02, 30) * Math.pow(0.9, d - 89);
            c[1][d] = base;
            v[0][d] = v[1][d] = 1e6;
        }
        LitStudy.Panel p = new LitStudy.Panel(SUNDAY, new String[]{"BTCUSDT", "XYZUSDT"}, c, v, Set.of());
        LitStudy.DResult r = LitStudy.runDonchian(p, 40, 119, new int[]{20});
        List<LitStudy.DTrade> t = r.trades();
        assertThat(t).isNotEmpty();
        assertThat(t.get(0).entry()).isEqualTo(60);
        assertThat(t.get(0).exit()).isBetween(90, 100);
        // vol-target size never exceeds the whole book
        assertThat(t.get(0).frac()).isLessThanOrEqualTo(1.0);
        LitStudy.Curve rnd = LitStudy.randomDonchian(p, 40, 119, 1, t, new SplittableRandom(1));
        assertThat(rnd.nav()).hasSize(80);
    }

    @Test
    void ctrendRanksTrendingCoinsHighest() {
        LitStudy.Panel p = panel(260, 0.01, -0.01, 0.0, 0.002, -0.003, 0.004, 0.006, -0.005, 0.001, 0.0);
        LitStudy.Choice c = LitStudy.ctrend(p, 220, 230).values().iterator().next();
        assertThat(c.k()).isEqualTo(2);
        assertThat(p.syms[c.cand()[c.top()[0]]]).isEqualTo(p.syms[1]);
    }

    @Test
    void blockBootstrapIntervalContainsTheMean() {
        double[] x = new double[200];
        SplittableRandom r = new SplittableRandom(3);
        for (int i = 0; i < x.length; i++) x[i] = 0.01 + 0.02 * (r.nextDouble() - 0.5);
        double[] ci = LitStudy.blockCi(x, 4, 2000, 1);
        assertThat(ci[0]).isLessThan(LitStudy.mean(x));
        assertThat(ci[1]).isGreaterThan(LitStudy.mean(x));
        assertThat(LitStudy.upper(1.6449)).isCloseTo(0.05, offset(1e-3));
    }
}
