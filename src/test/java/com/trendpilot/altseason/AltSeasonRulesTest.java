package com.trendpilot.altseason;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Alt-season reminder: indicators from closed daily candles only; missing data never changes the state. */
class AltSeasonRulesTest {

    static double[] line(int n, double start, double dailyPct) {
        double[] c = new double[n];
        c[0] = start;
        for (int i = 1; i < n; i++) c[i] = c[i - 1] * (1 + dailyPct / 100);
        return c;
    }

    private static Map<String, double[]> pool(int n, double dailyPct) {
        Map<String, double[]> m = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) m.put("C" + i + "USDT", line(210, 1 + i, dailyPct));
        return m;
    }

    @Test
    void risingAltsBeatingBtcAreBroadAndStrong() {
        var m = AltSeasonRules.compute(pool(60, 1.0), line(210, 50_000, 0.1), line(210, 3_000, 0.4));
        assertThat(m.pool()).isEqualTo(60);
        assertThat(m.breadth50()).isEqualTo(100);
        assertThat(m.breadth200()).isEqualTo(100);
        assertThat(m.altVsBtc30()).isGreaterThan(20);
        assertThat(m.ethBtcVs50()).isPositive();
        assertThat(m.v3Open()).isTrue();
    }

    @Test
    void entersOnlyWhenEveryConditionHoldsAndLeavesOnWeakBreadth() {
        var strong = AltSeasonRules.compute(pool(60, 1.0), line(210, 50_000, 0.1), line(210, 3_000, 0.4));
        assertThat(AltSeasonRules.next(AltSeasonRules.State.OFF, strong, 13, true)).isEqualTo(AltSeasonRules.State.OFF);
        assertThat(AltSeasonRules.next(AltSeasonRules.State.OFF, strong, 14, false)).isEqualTo(AltSeasonRules.State.OFF);
        assertThat(AltSeasonRules.next(AltSeasonRules.State.OFF, strong, 14, true)).isEqualTo(AltSeasonRules.State.EARLY);
        var weak = AltSeasonRules.compute(pool(60, -1.0), line(210, 50_000, 0.1), line(210, 3_000, 0.0));
        assertThat(AltSeasonRules.next(AltSeasonRules.State.EARLY, weak, 0, true)).isEqualTo(AltSeasonRules.State.OFF);
    }

    @Test
    void missingDataKeepsThePreviousState() {
        var thin = AltSeasonRules.compute(pool(10, 1.0), line(210, 50_000, 0.1), line(210, 3_000, 0.4));
        assertThat(AltSeasonRules.next(AltSeasonRules.State.EARLY, thin, 30, true)).isEqualTo(AltSeasonRules.State.EARLY);
        var noEth = AltSeasonRules.compute(pool(60, 1.0), line(210, 50_000, 0.1), null);
        assertThat(AltSeasonRules.next(AltSeasonRules.State.OFF, noEth, 30, true)).isEqualTo(AltSeasonRules.State.OFF);
    }
}
