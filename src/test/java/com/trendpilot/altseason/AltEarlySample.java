package com.trendpilot.altseason;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * Deterministic synthetic market (400 daily bars, BTC, ETH, 50 old alts, 5 late listings, one coin with a gap) used to
 * check the shared alt-season indicators against the research implementation. The research branch's AltEarlyStudy
 * was run once on exactly this data (scratch harness, 2026-11-08) and its values are pinned in AltEarlySignalsTest.
 * Do not change this generator without re-running that comparison.
 */
public final class AltEarlySample {

    public static final LocalDate START = LocalDate.parse("2025-01-01");
    public static final int DAYS = 400;

    private AltEarlySample() {
    }

    static double drift(int d, int[] cuts, double[] rates) {
        for (int k = 0; k < cuts.length; k++) if (d < cuts[k]) return rates[k];
        return rates[rates.length - 1];
    }

    /** symbol -> {close[DAYS], quoteVolume[DAYS]}; NaN / 0 where a coin has no bar. Symbols in sorted order. */
    public static Map<String, double[][]> market() {
        SplittableRandom rnd = new SplittableRandom(20261108L);
        double[] btc = new double[DAYS], eth = new double[DAYS], altF = new double[DAYS];
        double b = 60_000, e = 0.035, a = 1;
        for (int d = 0; d < DAYS; d++) {
            b *= Math.exp(drift(d, new int[]{120, 200, 300, 340}, new double[]{0.002, -0.003, 0.005, 0.0, -0.002}) + 0.02 * rnd.nextGaussian());
            e *= Math.exp(drift(d, new int[]{150, 260}, new double[]{-0.002, 0.004, -0.001}) + 0.015 * rnd.nextGaussian());
            a *= Math.exp(drift(d, new int[]{160, 260, 330}, new double[]{-0.005, 0.006, -0.002, 0.003}));
            btc[d] = b;
            eth[d] = b * e;
            altF[d] = a;
        }
        Map<String, double[][]> m = new TreeMap<>();
        m.put("BTCUSDT", series(btc, 5e9, rnd, 0));
        m.put("ETHUSDT", series(eth, 2e9, rnd, 0));
        for (int i = 0; i < 55; i++) {
            boolean late = i >= 50;
            String sym = (late ? "L" : "K") + String.format("%02d", i) + "USDT";
            double beta = 0.8 + 0.6 * rnd.nextDouble(), own = 0.002 * (rnd.nextDouble() - 0.5), sd = 0.03 + 0.03 * rnd.nextDouble();
            double x = 1 + 10 * rnd.nextDouble();
            double[] c = new double[DAYS];
            for (int d = 0; d < DAYS; d++) {
                x *= Math.exp(own + sd * rnd.nextGaussian());
                c[d] = x * Math.pow(btc[d] / btc[0], beta) * altF[d];
            }
            int from = late ? 250 + 10 * (i - 50) : 0;
            for (int d = 0; d < from; d++) c[d] = Double.NaN;
            if (i == 7) for (int d = 300; d < 305; d++) c[d] = Double.NaN; // gap
            m.put(sym, series(c, 2e6 * Math.exp(3 * rnd.nextDouble()), rnd, from));
        }
        return m;
    }

    private static double[][] series(double[] c, double base, SplittableRandom rnd, int from) {
        double[] close = c.clone(), qv = new double[c.length];
        for (int d = 0; d < c.length; d++) {
            double v = base * Math.exp(0.5 * rnd.nextGaussian());
            qv[d] = d >= from && close[d] > 0 ? v : 0;
        }
        return new double[][]{close, qv};
    }

    /** Total stablecoin supply by epoch day, covering 40 days before START to the last day. */
    public static Map<Long, Double> stable() {
        Map<Long, Double> s = new LinkedHashMap<>();
        double v = 1.5e11;
        for (int d = -40; d < DAYS; d++) {
            v *= Math.exp(drift(d, new int[]{180, 300}, new double[]{-0.0005, 0.0015, 0.0}));
            s.put(START.toEpochDay() + d, v);
        }
        return s;
    }

    /** Panel for days [from, to] of the sample (to inclusive), in the shared implementation. */
    public static AltEarlySignals.Panel panel(int from, int to) {
        Map<String, double[][]> m = market();
        String[] syms = m.keySet().toArray(String[]::new);
        double[][] c = new double[syms.length][], v = new double[syms.length][];
        for (int i = 0; i < syms.length; i++) {
            c[i] = java.util.Arrays.copyOfRange(m.get(syms[i])[0], from, to + 1);
            v[i] = java.util.Arrays.copyOfRange(m.get(syms[i])[1], from, to + 1);
        }
        return new AltEarlySignals.Panel(START.plusDays(from), syms, c, v, java.util.Set.of());
    }
}
