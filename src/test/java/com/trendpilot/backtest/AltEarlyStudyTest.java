package com.trendpilot.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Alt-season early entry study (research backtest only, never traded live). */
class AltEarlyStudyTest {

    static final LocalDate SUNDAY = LocalDate.of(2023, 1, 1);

    /** BTC + ETH + 24 alts with gently rising prices and a small wiggle. */
    static LitStudy.Panel panel(int days) {
        int n = 26;
        String[] syms = new String[n];
        double[][] c = new double[n][days], v = new double[n][days];
        syms[0] = "BTCUSDT";
        syms[1] = "ETHUSDT";
        for (int i = 2; i < n; i++) syms[i] = "C" + (char) ('A' + i) + "USDT";
        for (int i = 0; i < n; i++) {
            for (int d = 0; d < days; d++) {
                c[i][d] = 100 * Math.pow(1.001 + 0.0001 * i, d) * (d % 2 == 0 ? 1 : 1 + 0.002 * (i + 1));
                v[i][d] = 1e6 * (n - i);
            }
        }
        return new LitStudy.Panel(SUNDAY, syms, c, v, Set.of());
    }

    @Test
    void zigzagKeepsOnlyBigUpLegsWhereTheBasketRises() {
        int n = 200;
        double[] r = new double[n], alt = new double[n];
        for (int d = 0; d < n; d++) {
            // flat, +50% over days 20..60, -30% to day 90, +10% to day 120, then flat
            double x = d < 20 ? 1 : d < 60 ? 1 + 0.5 * (d - 20) / 40 : d < 90 ? 1.5 - 0.45 * (d - 60) / 30 : d < 120 ? 1.05 + 0.105 * (d - 90) / 30 : 1.155;
            r[d] = x;
            alt[d] = x;
        }
        List<AltEarlyStudy.Season> s = AltEarlyStudy.seasons(r, alt, 0, n - 1);
        assertThat(s).hasSize(1);
        assertThat(s.get(0).trough()).isEqualTo(0);
        assertThat(s.get(0).peak()).isEqualTo(60);
        assertThat(s.get(0).rGain()).isCloseTo(0.5, offset(1e-3));
        // the same leg with the basket falling in USD is not an alt season
        double[] down = new double[n];
        for (int d = 0; d < n; d++) down[d] = 1 - d * 0.001;
        assertThat(AltEarlyStudy.seasons(r, down, 0, n - 1)).isEmpty();
    }

    @Test
    void triggersNeedSevenQuietDaysBefore() {
        boolean[] on = new boolean[40];
        on[10] = on[11] = true;
        on[14] = true; // only 2 quiet days: not a new trigger
        on[30] = true;
        assertThat(AltEarlyStudy.triggers(on, 0, 39)).containsExactly(10, 30);
    }

    @Test
    void missingStablecoinDataNeverLightsIndicatorD() {
        LitStudy.Panel p = panel(300);
        AltEarlyStudy.Signals s = AltEarlyStudy.signals(p, Map.of());
        for (int d = 0; d < p.days; d++) assertThat(s.on[3][d]).isFalse();
        // a steady uptrend: basket and BTC both trend up, ETH/BTC rises, so a is on late in the sample
        assertThat(s.on[0][250]).isTrue();
        assertThat(s.alt[250]).isGreaterThan(1);
    }

    static AltEarlyStudy.Pick[] picks(LitStudy.Panel p) {
        AltEarlyStudy.Pick[] k = new AltEarlyStudy.Pick[p.days];
        for (int d = 0; d < p.days; d++) k[d] = AltEarlyStudy.pick(p, d);
        return k;
    }

    @Test
    void tierOneTimesOutAfter45DaysWithoutConfirmationAndWaitsForTheCountToDrop() {
        LitStudy.Panel p = panel(300);
        AltEarlyStudy.Signals s = new AltEarlyStudy.Signals(p.days);
        for (int d = 100; d < 260; d++) s.count[d] = 2;
        AltEarlyStudy.Result r = AltEarlyStudy.run(p, s, picks(p), 60, 299, AltEarlyStudy.Mode.COMBO, null);
        AltEarlyStudy.Ep e = r.eps().get(0);
        assertThat(e.entry()).isEqualTo(100);
        assertThat(e.exit()).isEqualTo(145);
        assertThat(e.reason()).isEqualTo("45 天未确认");
        assertThat(r.expo()[120 - 60]).isCloseTo(1.0 / 3, offset(0.02));
        // count stays at 2 until day 260: no re-entry before it has dropped
        assertThat(r.eps()).hasSize(1);
        double[] expo = Arrays.copyOfRange(r.expo(), 146 - 60, 300 - 60);
        assertThat(Arrays.stream(expo).max().orElse(0)).isZero();
    }

    @Test
    void s2AddsToFullAndClosingS2SellsEverything() {
        LitStudy.Panel p = panel(300);
        AltEarlyStudy.Signals s = new AltEarlyStudy.Signals(p.days);
        for (int d = 100; d < 110; d++) s.count[d] = 2;
        for (int d = 120; d < 150; d++) s.s2[d] = true;
        AltEarlyStudy.Result r = AltEarlyStudy.run(p, s, picks(p), 60, 299, AltEarlyStudy.Mode.COMBO, null);
        AltEarlyStudy.Ep e = r.eps().get(0);
        assertThat(e.entry()).isEqualTo(100);
        assertThat(e.fullAt()).isEqualTo(120);
        assertThat(e.exit()).isEqualTo(150);
        assertThat(e.reason()).isEqualTo("S2 关闭");
        assertThat(r.expo()[130 - 60]).isGreaterThan(0.9);
        assertThat(e.ret()).isGreaterThan(0);
        // the S2-only control enters on day 120
        AltEarlyStudy.Result o = AltEarlyStudy.run(p, s, picks(p), 60, 299, AltEarlyStudy.Mode.S2ONLY, null);
        assertThat(o.eps().get(0).entry()).isEqualTo(120);
    }

    @Test
    void randomPlanKeepsLengthsAndDoesNotOverlap() {
        List<AltEarlyStudy.Ep> eps = List.of(new AltEarlyStudy.Ep(100, 130, 110, "x", 0, 0, 0, Set.of()),
                new AltEarlyStudy.Ep(200, 220, -1, "x", 0, 0, 0, Set.of()));
        double[] plan = AltEarlyStudy.randomPlan(400, 50, 399, eps, new java.util.SplittableRandom(1));
        long full = Arrays.stream(plan).filter(x -> x == 1).count(), third = Arrays.stream(plan).filter(x -> x > 0 && x < 1).count();
        assertThat(full).isEqualTo(20);
        assertThat(third).isEqualTo(10 + 20);
    }
}
