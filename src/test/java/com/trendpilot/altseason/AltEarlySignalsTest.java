package com.trendpilot.altseason;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Shared alt-season indicators must match the research implementation (research/altseason-early-2026-11-05,
 * AltEarlyStudy.signals / pick). The expected values below were produced by that research code on {@link AltEarlySample}
 * (scratch harness 2026-11-08: all 400 days identical for pools, basket, R, ETH/BTC, breadth, stablecoin growth, BTC high,
 * the five flags, S2 and the rotation top 5).
 */
class AltEarlySignalsTest {

    static AltEarlySignals.Panel full = AltEarlySample.panel(0, AltEarlySample.DAYS - 1);
    static AltEarlySignals.Signals sig = AltEarlySignals.signals(full, AltEarlySample.stable());

    record Expect(int day, boolean[] on, int count, boolean s2, double alt, double r, double ethbtc, double breadth, double stable30,
                  int since, double fromHigh, List<String> picks) {
    }

    static final List<Expect> RESEARCH = List.of(
            new Expect(118, new boolean[]{false, true, false, false, false}, 1, false, 0.8089033365, 9.650105873049e-06, 0.0251802672, 0.625,
                    -0.0148880604, 0, 0.0, List.of("K46USDT", "K42USDT", "K04USDT", "K08USDT", "K18USDT")),
            new Expect(250, new boolean[]{true, false, true, true, false}, 3, true, 1.6782051568, 1.575554083284e-05, 0.0295482589, 0.975,
                    0.0460278599, 3, -0.0277277401, List.of("K49USDT", "K14USDT", "K22USDT", "K18USDT", "K26USDT")),
            new Expect(320, new boolean[]{false, false, false, false, true}, 1, false, 2.4080802373, 1.644640772964e-05, 0.0272406868, 0.675,
                    0.0135915365, 15, -0.0398391005, List.of("K28USDT", "K21USDT", "K36USDT", "K19USDT", "K29USDT")),
            new Expect(399, new boolean[]{true, false, true, false, false}, 2, true, 3.9556306716, 2.247000276524e-05, 0.0263709812, 0.8,
                    0.0, 1, -0.0113239300, List.of("K27USDT", "K12USDT", "K17USDT", "K38USDT", "K32USDT")));

    @Test
    void matchesTheResearchImplementationOnSampleDays() {
        for (Expect e : RESEARCH) {
            int d = e.day();
            boolean[] on = new boolean[5];
            for (int k = 0; k < 5; k++) on[k] = sig.on[k][d];
            assertThat(on).as("flags day %d", d).isEqualTo(e.on());
            assertThat(sig.count[d]).as("count day %d", d).isEqualTo(e.count());
            assertThat(sig.s2[d]).as("S2 day %d", d).isEqualTo(e.s2());
            assertThat(sig.alt[d]).isCloseTo(e.alt(), within(1e-9));
            assertThat(sig.r[d] / e.r() - 1).isCloseTo(0, within(1e-9));
            assertThat(sig.ethbtc[d]).isCloseTo(e.ethbtc(), within(1e-9));
            assertThat(sig.breadth[d]).isCloseTo(e.breadth(), within(1e-12));
            assertThat(sig.stable30[d]).isCloseTo(e.stable30(), within(1e-9));
            assertThat(sig.sinceHigh[d]).isEqualTo(e.since());
            assertThat(sig.btcFromHigh[d]).isCloseTo(e.fromHigh(), within(1e-9));
            assertThat(AltEarlySignals.rotation(full, d, 5).stream().map(AltEarlySignals.Pick::symbol).toList()).isEqualTo(e.picks());
        }
    }

    /** The live job only has about 201 closed daily bars: the last 60 days must read the same as on the full history. */
    @Test
    void theLiveWindowGivesTheSameReadingAsTheFullHistory() {
        int from = AltEarlySample.DAYS - 201;
        AltEarlySignals.Panel w = AltEarlySample.panel(from, AltEarlySample.DAYS - 1);
        AltEarlySignals.Signals ws = AltEarlySignals.signals(w, AltEarlySample.stable());
        for (int d = AltEarlySample.DAYS - 60; d < AltEarlySample.DAYS; d++) {
            var a = AltEarlySignals.status(full, sig, d);
            var b = AltEarlySignals.status(w, ws, d - from);
            assertThat(b.on()).as("day %d", d).isEqualTo(a.on());
            assertThat(b.s2()).isEqualTo(a.s2());
            assertThat(b.breadth()).isEqualTo(a.breadth());
            assertThat(b.ethBtcVs50()).isCloseTo(a.ethBtcVs50(), within(1e-12));
            assertThat(b.rFromHigh60()).isCloseTo(a.rFromHigh60(), within(1e-9));
            assertThat(b.rVs50()).isCloseTo(a.rVs50(), within(1e-9));
            assertThat(b.sinceHigh()).isEqualTo(a.sinceHigh());
            assertThat(AltEarlySignals.rotation(w, d - from, 5)).isEqualTo(AltEarlySignals.rotation(full, d, 5));
        }
    }

    @Test
    void missingStablecoinDataLeavesThatIndicatorUnknownAndOff() {
        AltEarlySignals.Signals s = AltEarlySignals.signals(full, Map.of());
        var st = AltEarlySignals.status(full, s, 250);
        assertThat(st.known(3)).isFalse();
        assertThat(st.on()[3]).isFalse();
        assertThat(st.count()).isEqualTo(2); // a and c still on
    }

    @Test
    void noBtcOrTooFewCoinsMeansNothingIsKnownAndNothingThrows() {
        // no BTC: nothing that needs BTC can be known, nothing is on
        String[] syms = {"AAAUSDT", "ETHUSDT"};
        double[][] c = new double[2][120], v = new double[2][120];
        for (double[] x : c) Arrays.fill(x, 1.0);
        for (double[] x : v) Arrays.fill(x, 1e6);
        var p = new AltEarlySignals.Panel(LocalDate.parse("2026-01-01"), syms, c, v, Set.of());
        var s = AltEarlySignals.signals(p, Map.of());
        var st = AltEarlySignals.status(p, s, 119);
        for (int k = 0; k < 5; k++) {
            assertThat(st.known(k)).isFalse();
            assertThat(st.on()[k]).isFalse();
        }
        assertThat(st.s2Known()).isFalse();
        assertThat(st.s2()).isFalse();
        // pool of 1 coin: no basket (needs 20), rotation still lists what it can
        assertThat(AltEarlySignals.rotation(p, 119, 5)).hasSizeLessThanOrEqualTo(2);
    }
}
