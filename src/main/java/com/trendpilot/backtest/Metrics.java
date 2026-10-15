package com.trendpilot.backtest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** 一个方案在一个时间段内的统计。金额 U，百分比为百分点。 */
public record Metrics(String name, int trades, double winRate, double totalPnl, double equityChange, double maxDrawdown,
                      double meanPct, double meanR, double payoff, double cvar5Pct, double longestLossDays,
                      double ciLowPct, double ciHighPct, double withoutTop3, int l1Stops, double l1Share,
                      double falseStopShare, int ends, double[] regimeMeanPct, int[] regimeTrades, double[] regimePnl,
                      double[] regimeEquity) {

    public static Metrics of(Sim sim, Regimes regimes, int fromQ, int toQ) {
        List<Trade> ts = sim.trades().stream().filter(t -> t.entryQ() >= fromQ && t.entryQ() < toQ).toList();
        double[] eq = sim.equity();
        int a = fromQ / 4, b = Math.min(toQ / 4, eq.length - 1);
        double peak = eq[a], dd = 0;
        for (int i = a; i <= b; i++) {
            peak = Math.max(peak, eq[i]);
            dd = Math.max(dd, peak - eq[i]);
        }
        int n = ts.size();
        double total = ts.stream().mapToDouble(Trade::pnl).sum();
        long wins = ts.stream().filter(t -> t.pnl() > 0).count();
        double meanPct = ts.stream().mapToDouble(Trade::pct).average().orElse(Double.NaN);
        double meanR = ts.stream().mapToDouble(t -> t.pnl() / t.r()).average().orElse(Double.NaN);
        double avgWin = ts.stream().filter(t -> t.pnl() > 0).mapToDouble(Trade::pnl).average().orElse(0);
        double avgLoss = ts.stream().filter(t -> t.pnl() < 0).mapToDouble(Trade::pnl).average().orElse(0);
        double payoff = avgLoss == 0 ? Double.NaN : avgWin / -avgLoss;
        double[] pcts = ts.stream().mapToDouble(Trade::pct).sorted().toArray();
        int k = Math.max(1, (int) Math.ceil(pcts.length * 0.05));
        double cvar = 0;
        for (int i = 0; i < Math.min(k, pcts.length); i++) cvar += pcts[i];
        cvar = pcts.length == 0 ? Double.NaN : cvar / Math.min(k, pcts.length);
        double longest = ts.stream().filter(t -> t.pnl() < 0).mapToDouble(t -> t.hours() / 24).max().orElse(0);
        double[] ci = bootstrap(pcts);
        List<Trade> byPnl = new ArrayList<>(ts);
        byPnl.sort(Comparator.comparingDouble(Trade::pnl).reversed());
        double top3 = byPnl.stream().limit(3).mapToDouble(Trade::pnl).sum();
        List<Trade> open = ts.stream().filter(Trade::altGateOpen).toList();
        long l1 = ts.stream().filter(t -> t.reason().equals("L1_STOP")).count();
        long l1Open = open.stream().filter(t -> t.reason().equals("L1_STOP")).count();
        long falseStops = ts.stream().filter(t -> t.reason().equals("L1_STOP") && t.recoveredAfter()).count();
        int ends = (int) ts.stream().filter(t -> t.reason().equals("END")).count();
        double[] rMean = new double[7], rPnl = new double[7], rEq = new double[7];
        int[] rN = new int[7];
        for (int r = 1; r <= 6; r++) {
            final int rr = r;
            List<Trade> in = ts.stream().filter(t -> t.regime() == rr).toList();
            rN[r] = in.size();
            rMean[r] = in.stream().mapToDouble(Trade::pct).average().orElse(Double.NaN);
            rPnl[r] = in.stream().mapToDouble(Trade::pnl).sum();
        }
        for (int h = a; h < b; h++) {
            int r = regimes.at(h * 4);
            if (r > 0) rEq[r] += eq[h + 1] - eq[h];
        }
        return new Metrics(sim.name(), n, n == 0 ? Double.NaN : wins / (double) n, total, eq[b] - eq[a], dd, meanPct, meanR,
                payoff, cvar, longest, ci[0], ci[1], total - top3, (int) l1, open.isEmpty() ? 0 : l1Open / (double) open.size(),
                l1 == 0 ? 0 : falseStops / (double) l1, ends, rMean, rN, rPnl, rEq);
    }

    /** 每笔平均收益的 90% 置信区间（自助法，固定种子可复现）。 */
    static double[] bootstrap(double[] v) {
        if (v.length < 10) return new double[]{Double.NaN, Double.NaN};
        Random rnd = new Random(42);
        double[] means = new double[2000];
        for (int i = 0; i < means.length; i++) {
            double s = 0;
            for (int j = 0; j < v.length; j++) s += v[rnd.nextInt(v.length)];
            means[i] = s / v.length;
        }
        java.util.Arrays.sort(means);
        return new double[]{means[(int) (means.length * 0.05)], means[(int) (means.length * 0.95)]};
    }
}
