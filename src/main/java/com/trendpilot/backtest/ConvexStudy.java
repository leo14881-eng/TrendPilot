package com.trendpilot.backtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.signal.EarlySignalCalculator;

/**
 * "Big wins, small losses" study (user 2026-11-02): three structural designs ({@link ConvexSim}), 12 parameter sets fixed
 * before looking at results, each with a random twin, compared on the same data and period with the live V2 rule and V6
 * (24h top-3 momentum). Report: payoff profile, full release check against V2 and V6, six market regimes, the three alt
 * rallies, six-month walk-forward and significance corrected for the running trial count.
 * <pre>
 * java -cp ... com.trendpilot.backtest.ConvexStudy dataDir outDir fromMonth toMonth lastDay|- tradeFrom validationFrom threads [label]
 * </pre>
 * Works on Binance archives and on the Bybit 15m archive built by {@link BybitArchive} (same layout).
 */
public final class ConvexStudy {

    /** Rule-family trials registered in docs/策略对比记录/README.md before this study. */
    static final int PRIOR_TRIALS = 365;

    static List<ConvexSim.Rule> grid() {
        List<ConvexSim.Rule> g = new ArrayList<>();
        for (int lb : new int[]{14, 28}) for (int n : new int[]{5, 10}) g.add(ConvexSim.Rule.rotation(lb, n));
        for (int ma : new int[]{50, 100}) for (boolean vol : new boolean[]{false, true}) g.add(ConvexSim.Rule.maTrend(ma, vol));
        for (int n : new int[]{20, 55}) for (int x : new int[]{10, 20}) g.add(ConvexSim.Rule.breakout(n, x));
        return g;
    }

    static final String V6 = "momentum-top=3,momentum-hours=24,max-open=17";

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        YearMonth from = YearMonth.parse(args[2]);
        YearMonth to = YearMonth.parse(args[3]);
        LocalDate lastDay = args[4].equals("-") ? null : LocalDate.parse(args[4]);
        LocalDate tradeFrom = LocalDate.parse(args[5]);
        LocalDate split = LocalDate.parse(args[6]);
        int threads = Integer.parseInt(args[7]);
        String label = args.length > 8 ? args[8] : dataDir.getFileName().toString();
        Files.createDirectories(outDir);

        StrategyParams params = BacktestMain.loadParams();
        Set<String> excluded = new HashSet<>();
        Archive archive = new Archive(dataDir);
        List<String> symbols = new ArrayList<>();
        for (String s : archive.usdtSymbols()) {
            if (com.trendpilot.universe.UniverseFilter.excludedOffline(params.universe(), s)) {
                excluded.add(s);
                continue;
            }
            symbols.add(s);
        }
        archive.download(symbols, from, to, lastDay);
        long start = from.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        LocalDate endDay = lastDay != null ? lastDay.plusDays(1) : to.plusMonths(1).atDay(1);
        long end = endDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        int size = (int) ((end - start) / Series.Q);
        Map<String, Series> series = new ConcurrentHashMap<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> fs = new ArrayList<>();
            for (String s : symbols) {
                fs.add(pool.submit(() -> {
                    Series x = archive.load(s, start, size, from, to, lastDay);
                    if (x != null) series.put(s, x);
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
        }
        MarketData data = new MarketData(start, size, series, 150, excluded);
        String md = study(data, params, tradeFrom, split, endDay, threads, label, outDir);
        Files.writeString(outDir.resolve("report.md"), md);
        System.out.println(md);
    }

    record Run(ConvexSim.Rule rule, Sim real, Sim random, int filterOn, int filterDays) {
    }

    static String study(MarketData data, StrategyParams params, LocalDate tradeFrom, LocalDate split, LocalDate endDay, int threads,
                        String label, Path outDir) throws Exception {
        Regimes regimes = new Regimes(data);
        SignalTape tape = new SignalTape(data, new EarlySignalCalculator(params));
        Map<String, ConvexSim.Daily> daily = ConvexSim.Daily.of(data);
        int size = data.size;
        int fromQ = q(data, tradeFrom), splitQ = q(data, split);
        B0Sim v2 = EntryStudy.live("现行V2", data, regimes, tape, params, fromQ, size);
        B0Sim v6 = CandidateStudy.parse("V6", "base", V6).apply(EntryStudy.live("V6", data, regimes, tape, params, fromQ, size));
        List<ConvexSim> sims = new ArrayList<>();
        long seed = 20261102;
        for (ConvexSim.Rule r : grid()) sims.add(new ConvexSim(r, data, regimes, params, daily, fromQ, size, seed++));
        List<Sim> todo = new ArrayList<>(List.of(v2, v6));
        todo.addAll(sims);
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads))) {
            List<Future<?>> fs = new ArrayList<>();
            for (Sim s : todo) fs.add(pool.submit(() -> {
                s.run();
                System.out.println("done " + s.name() + ": " + s.trades().size() + " trades");
                return null;
            }));
            for (Future<?> f : fs) f.get();
        }
        List<Run> runs = sims.stream().map(s -> new Run(s.rule, s, s.twin(), s.filterOn, s.filterDays)).toList();
        if (outDir != null) {
            for (Sim s : todo) {
                Files.writeString(outDir.resolve("trades-" + s.name().replaceAll("[^\\p{L}\\p{N}]+", "_") + ".csv"), BacktestMain.tradesCsv(s, data));
            }
        }
        return report(data, regimes, params, v2, v6, runs, fromQ, splitQ, size, tradeFrom, split, endDay, label);
    }

    // ------------------------------------------------------------------ statistics

    /** Payoff profile of trades entered in [from, to). */
    record Stats(int n, double winRate, double avgWin, double avgLoss, double payoff, double mean, double ci, double meanPct,
                 double breakEven, double maxWinPct, double maxWinU, double sum) {

        static Stats of(Sim s, int from, int to) {
            List<Trade> ts = s.trades().stream().filter(t -> t.entryQ() >= from && t.entryQ() < to).toList();
            int n = ts.size();
            double w = ts.stream().filter(t -> t.pnl() > 0).mapToDouble(Trade::pnl).average().orElse(Double.NaN);
            double l = ts.stream().filter(t -> t.pnl() <= 0).mapToDouble(Trade::pnl).average().orElse(Double.NaN);
            long wins = ts.stream().filter(t -> t.pnl() > 0).count();
            double m = ts.stream().mapToDouble(Trade::pnl).average().orElse(Double.NaN);
            double var = ts.stream().mapToDouble(t -> (t.pnl() - m) * (t.pnl() - m)).sum() / Math.max(1, n - 1);
            return new Stats(n, n == 0 ? Double.NaN : wins / (double) n, w, l, w / Math.abs(l), m, 1.96 * Math.sqrt(var / Math.max(1, n)),
                    ts.stream().mapToDouble(Trade::pct).average().orElse(Double.NaN), Math.abs(l) / (w + Math.abs(l)),
                    ts.stream().mapToDouble(Trade::pct).max().orElse(Double.NaN), ts.stream().mapToDouble(Trade::pnl).max().orElse(Double.NaN),
                    ts.stream().mapToDouble(Trade::pnl).sum());
        }
    }

    /** Release-check period with per-trade figures scaled to 1,000 U per trade, so different trade sizes compare fairly. */
    static ReleaseCheck.Period per1000(Sim s, Regimes regimes, int from, int to, double capital) {
        ReleaseCheck.Period p = ReleaseCheck.Period.of(s, regimes, from, to, capital);
        double[] x = s.trades().stream().filter(t -> t.entryQ() >= from && t.entryQ() < to).mapToDouble(t -> t.pnl() / t.cost() * 1000).toArray();
        double mean = 0, var = 0;
        for (double v : x) mean += v;
        mean = x.length == 0 ? 0 : mean / x.length;
        for (double v : x) var += (v - mean) * (v - mean);
        var = x.length < 2 ? 0 : var / (x.length - 1);
        return new ReleaseCheck.Period(p.equity(), p.maxDd(), x.length, mean, var, p.navDd());
    }

    /** Daily equity change of a minus b over [fromQ, toQ): {mean U/day, 95% half width, t, days}. */
    static double[] dailyDiff(Sim a, Sim b, int fromQ, int toQ) {
        double[] ea = a.equity(), eb = b.equity();
        List<Double> d = new ArrayList<>();
        for (int day = (fromQ + 95) / 96; (day + 1) * 24 < Math.min(ea.length, eb.length) && (day + 1) * 96 <= toQ; day++) {
            int h0 = day * 24, h1 = (day + 1) * 24;
            d.add((ea[h1] - ea[h0]) - (eb[h1] - eb[h0]));
        }
        int n = d.size();
        double m = d.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = d.stream().mapToDouble(v -> (v - m) * (v - m)).sum() / Math.max(1, n - 1);
        double se = Math.sqrt(var / Math.max(1, n));
        return new double[]{m, 1.96 * se, se > 0 ? m / se : 0, n};
    }

    /** Two-sided normal p-value. */
    static double pValue(double t) {
        return erfc(Math.abs(t) / Math.sqrt(2));
    }

    /** Complementary error function (Numerical Recipes erfcc, relative error < 1.2e-7). */
    static double erfc(double x) {
        double z = Math.abs(x);
        double t = 1 / (1 + 0.5 * z);
        double r = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806
                + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? r : 2 - r;
    }

    static double eqChange(Sim s, int fromQ, int toQ) {
        double[] e = s.equity();
        int a = Math.min(fromQ / 4, e.length - 1), b = Math.min(toQ / 4, e.length - 1);
        return e[b] - e[a];
    }

    // ------------------------------------------------------------------ report

    static String report(MarketData data, Regimes regimes, StrategyParams params, Sim v2, Sim v6, List<Run> runs, int fromQ, int splitQ,
                         int size, LocalDate tradeFrom, LocalDate split, LocalDate endDay, String label) {
        double cap = params.capital().hardCap();
        int trials = PRIOR_TRIALS + runs.size();
        StringBuilder b = new StringBuilder();
        b.append("# 大赚小亏方案回测（").append(label).append("，").append(tradeFrom).append(" ～ ").append(endDay.minusDays(1)).append("）\n\n");
        b.append("验证期 ").append(split).append(" 起。").append(runs.size()).append(" 组（看结果前登记），每组配同规则随机对照（同一天、同金额、同加仓时点、")
                .append("同止损规则、不晚于真实单卖出，币从同一池子里随机挑）。对照 = 同数据同时段的现行 V2 和 V6。")
                .append("累计试验数（山寨族）= ").append(PRIOR_TRIALS).append(" + ").append(runs.size()).append(" = ").append(trials).append("。\n\n");

        List<Sim> all = new ArrayList<>(List.of(v2, v6));
        runs.forEach(r -> all.add(r.real()));

        for (int part = 0; part < 2; part++) {
            int a = part == 0 ? fromQ : splitQ;
            b.append("## 盈亏结构（").append(part == 0 ? "全期" : "验证期").append("，按买入时间）\n\n")
                    .append("| 方案 | 笔数 | 胜率 | 平均盈利 U | 平均亏损 U | 盈亏比 | 每笔 U ±95% | 每笔 % | 保本胜率 | 最大单笔 % / U | 权益 U | 最大回撤 U | 净值回撤 | 随机对照 权益 U | 随机 每笔 U |\n")
                    .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
            for (Sim s : all) {
                Stats st = Stats.of(s, a, size);
                ReleaseCheck.Period p = ReleaseCheck.Period.of(s, regimes, a, size, cap);
                Run run = runs.stream().filter(r -> r.real() == s).findFirst().orElse(null);
                String rnd = "— | —";
                if (run != null) {
                    Stats rs = Stats.of(run.random(), a, size);
                    rnd = String.format(Locale.ROOT, "%,.0f | %+.2f", eqChange(run.random(), a, size), rs.mean());
                }
                b.append(String.format(Locale.ROOT, "| %s | %d | %.1f%% | %+.1f | %+.1f | %.2f | %+.2f ±%.2f | %+.2f | %.1f%% | %.1f / %,.0f | %,.0f | %,.0f | %.1f%% | %s |%n",
                        s.name(), st.n(), st.winRate() * 100, st.avgWin(), st.avgLoss(), st.payoff(), st.mean(), st.ci(), st.meanPct(),
                        st.breakEven() * 100, st.maxWinPct(), st.maxWinU(), p.equity(), p.maxDd(), p.navDd() * 100, rnd));
            }
            b.append('\n');
        }

        b.append("## 发布前检查（全期 + 验证期；每笔期望按每笔 1,000U 折算后比较）\n\n")
                .append("| 方案 | 对 V2 结论 | 对 V2 不满足 | 对 V6 结论 | 对 V6 不满足 | 每笔差 vs 随机（每 1,000U）| 每天差 vs 随机 U（t） | 每天差 vs V6 U（t） | Bonferroni ×")
                .append(trials).append("（vs 随机 / vs V6） |\n|---|---|---|---|---|---:|---:|---:|---|\n");
        ReleaseCheck.Period v2F = per1000(v2, regimes, fromQ, size, cap), v2V = per1000(v2, regimes, splitQ, size, Double.NaN);
        ReleaseCheck.Period v6F = per1000(v6, regimes, fromQ, size, cap), v6V = per1000(v6, regimes, splitQ, size, Double.NaN);
        double[] v2Reg = Metrics.of(v2, regimes, fromQ, size).regimeEquity(), v6Reg = Metrics.of(v6, regimes, fromQ, size).regimeEquity();
        for (Run r : runs) {
            ReleaseCheck.Period f = per1000(r.real(), regimes, fromQ, size, cap), v = per1000(r.real(), regimes, splitQ, size, Double.NaN);
            ReleaseCheck.Period rnd = per1000(r.random(), regimes, fromQ, size, Double.NaN);
            double[] reg = Metrics.of(r.real(), regimes, fromQ, size).regimeEquity();
            ReleaseCheck.Result c2 = ReleaseCheck.evaluate(f, v, v2F, v2V, reg, v2Reg, rnd);
            ReleaseCheck.Result c6 = ReleaseCheck.evaluate(f, v, v6F, v6V, reg, v6Reg, rnd);
            double[] dr = dailyDiff(r.real(), r.random(), fromQ, size), d6 = dailyDiff(r.real(), v6, fromQ, size);
            b.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %+.2f ±%.2f | %+.2f ±%.2f（%.2f） | %+.2f ±%.2f（%.2f） | %s / %s |%n",
                    r.rule().id(), c2.verdict(), failed(c2), c6.verdict(), failed(c6), c2.randomDiff()[0], c2.randomDiff()[1],
                    dr[0], dr[1], dr[2], d6[0], d6[1], d6[2], bonf(dr[2], trials), bonf(d6[2], trials)));
        }
        b.append("\nPASS = 全部检查通过；PROMISING = 两期合计不差，或验证期不差且回撤更小；WORSE = 其余或净值回撤 > 25%。\n\n");

        b.append("## 六种行情（全期，按净值曲线所在行情统计，U）\n\n| 方案 |");
        for (int g = 1; g <= 6; g++) b.append(' ').append(Regimes.NAMES[g]).append(" |");
        b.append("\n|---|").append("---:|".repeat(6)).append('\n');
        for (Sim s : all) {
            double[] reg = Metrics.of(s, regimes, fromQ, size).regimeEquity();
            b.append("| ").append(s.name()).append(" |");
            for (int g = 1; g <= 6; g++) b.append(String.format(Locale.ROOT, " %,.0f |", reg[g]));
            b.append('\n');
        }

        b.append("\n## 三段山寨行情（段内净值变化 U；括号：段内买入笔数 / 最大单笔 %；随机对照段内净值变化）\n\n| 方案 |");
        String[][] wins = {CandidateStudy.WINDOWS[1], CandidateStudy.WINDOWS[2], CandidateStudy.WINDOWS[3]};
        for (String[] w : wins) b.append(' ').append(w[0]).append(" ～ ").append(w[1]).append(" |");
        b.append("\n|---|").append("---:|".repeat(wins.length)).append('\n');
        for (Sim s : all) {
            Run run = runs.stream().filter(r -> r.real() == s).findFirst().orElse(null);
            b.append("| ").append(s.name()).append(" |");
            for (String[] w : wins) {
                int a = Math.max(fromQ, q(data, LocalDate.parse(w[0]))), z = Math.min(size, q(data, LocalDate.parse(w[1]).plusDays(1)));
                if (a >= z) {
                    b.append(" — |");
                    continue;
                }
                List<Trade> ts = s.trades().stream().filter(t -> t.entryQ() >= a && t.entryQ() < z).toList();
                b.append(String.format(Locale.ROOT, " %,.0f（%d / %.1f）%s |", eqChange(s, a, z), ts.size(),
                        ts.stream().mapToDouble(Trade::pct).max().orElse(Double.NaN),
                        run == null ? "" : String.format(Locale.ROOT, "；随机 %,.0f", eqChange(run.random(), a, z))));
            }
            b.append('\n');
        }

        b.append("\n## 滚动前推（每 6 个月一段；同一方案族里只用之前各段合计最好的参数，看下一段）\n\n");
        List<int[]> segs = new ArrayList<>();
        List<String> segNames = new ArrayList<>();
        for (LocalDate d = tradeFrom; d.isBefore(endDay); d = d.plusMonths(6)) {
            LocalDate e = d.plusMonths(6).isBefore(endDay) ? d.plusMonths(6) : endDay;
            segs.add(new int[]{q(data, d), Math.min(size, q(data, e))});
            segNames.add(d + " ～ " + e.minusDays(1));
        }
        b.append("| 方案族 | 段 | 选中 | 样本外 U | 同参数随机 U | V6 U | V2 U |\n|---|---|---|---:|---:|---:|---:|\n");
        for (String fam : new String[]{"A", "B", "C"}) {
            List<Run> fr = runs.stream().filter(r -> r.rule().family().equals(fam)).toList();
            if (fr.isEmpty()) continue;
            double tot = 0, totR = 0, tot6 = 0, tot2 = 0;
            int pos = 0, beatR = 0, beat6 = 0, n = 0;
            for (int i = 1; i < segs.size(); i++) {
                Run best = null;
                double bestV = Double.NEGATIVE_INFINITY;
                for (Run r : fr) {
                    double v = eqChange(r.real(), segs.get(0)[0], segs.get(i)[0]);
                    if (v > bestV) {
                        bestV = v;
                        best = r;
                    }
                }
                int[] sg = segs.get(i);
                double o = eqChange(best.real(), sg[0], sg[1]), ro = eqChange(best.random(), sg[0], sg[1]);
                double o6 = eqChange(v6, sg[0], sg[1]), o2 = eqChange(v2, sg[0], sg[1]);
                tot += o;
                totR += ro;
                tot6 += o6;
                tot2 += o2;
                n++;
                if (o > 0) pos++;
                if (o > ro) beatR++;
                if (o > o6) beat6++;
                b.append(String.format(Locale.ROOT, "| %s | %s | %s | %,.0f | %,.0f | %,.0f | %,.0f |%n", fam, segNames.get(i), best.rule().id(), o, ro, o6, o2));
            }
            b.append(String.format(Locale.ROOT, "| **%s 合计** | %d 段：赚钱 %d 段，好于随机 %d 段，好于 V6 %d 段 | | **%,.0f** | %,.0f | %,.0f | %,.0f |%n",
                    fam, n, pos, beatR, beat6, tot, totR, tot6, tot2));
        }

        b.append("\n## 按年净值变化（U）\n\n| 方案 |");
        Map<Integer, int[]> years = new TreeMap<>();
        for (LocalDate d = tradeFrom; d.isBefore(endDay); d = d.plusDays(1)) {
            int[] y = years.computeIfAbsent(d.getYear(), k -> new int[]{Integer.MAX_VALUE, 0});
            y[0] = Math.min(y[0], q(data, d));
            y[1] = Math.max(y[1], Math.min(size, q(data, d.plusDays(1))));
        }
        years.keySet().forEach(y -> b.append(' ').append(y).append(" |"));
        b.append("\n|---|").append("---:|".repeat(years.size())).append('\n');
        for (Sim s : all) {
            b.append("| ").append(s.name()).append(" |");
            years.values().forEach(y -> b.append(String.format(Locale.ROOT, " %,.0f |", eqChange(s, y[0], y[1]))));
            b.append('\n');
        }

        b.append("\n## 卖出原因（全期：笔数 / 合计 U / 每笔 %）\n\n| 方案 | 原因 |\n|---|---|\n");
        for (Run r : runs) {
            Map<String, double[]> m = new TreeMap<>();
            for (Trade t : r.real().trades()) {
                if (t.entryQ() < fromQ) continue;
                double[] x = m.computeIfAbsent(t.reason(), k -> new double[3]);
                x[0]++;
                x[1] += t.pnl();
                x[2] += t.pct();
            }
            List<String> parts = new ArrayList<>();
            m.forEach((k, x) -> parts.add(String.format(Locale.ROOT, "%s %.0f / %,.0f / %+.1f", k, x[0], x[1], x[2] / x[0])));
            b.append("| ").append(r.rule().id()).append(" | ").append(String.join("；", parts))
                    .append(r.filterDays() > 0 ? String.format(Locale.ROOT, "；大盘过滤打开 %d / %d 次", r.filterOn(), r.filterDays()) : "")
                    .append(" |\n");
        }
        b.append("\n## 单笔大赚分布（全期，每笔收益 %）\n\n| 方案 | 笔数 | ≥ 20% | ≥ 50% | ≥ 100% | 前 5 笔合计 U | 去掉前 5 笔后合计 U |\n|---|---:|---:|---:|---:|---:|---:|\n");
        for (Sim s : all) {
            List<Trade> ts = s.trades().stream().filter(t -> t.entryQ() >= fromQ).sorted((x, y) -> Double.compare(y.pnl(), x.pnl())).toList();
            double top5 = ts.stream().limit(5).mapToDouble(Trade::pnl).sum(), sum = ts.stream().mapToDouble(Trade::pnl).sum();
            b.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | %,.0f | %,.0f |%n", s.name(), ts.size(),
                    ts.stream().filter(t -> t.pct() >= 20).count(), ts.stream().filter(t -> t.pct() >= 50).count(),
                    ts.stream().filter(t -> t.pct() >= 100).count(), top5, sum - top5));
        }
        return b.toString();
    }

    private static String failed(ReleaseCheck.Result r) {
        List<String> f = new ArrayList<>(r.checks().entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList());
        f.addAll(r.notes());
        return f.isEmpty() ? "—" : String.join("；", f);
    }

    private static String bonf(double t, int trials) {
        double p = pValue(t) * trials;
        return t <= 0 ? "不显著（更差）" : p < 0.05 ? String.format(Locale.ROOT, "显著（%.2g）", p) : String.format(Locale.ROOT, "不显著（%.2g）", Math.min(1, p));
    }

    static int q(MarketData data, LocalDate d) {
        return (int) ((d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - data.start) / Series.Q);
    }
}
