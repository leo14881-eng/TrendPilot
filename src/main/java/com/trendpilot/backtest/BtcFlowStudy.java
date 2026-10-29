package com.trendpilot.backtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntPredicate;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.signal.EarlySignalCalculator;
import com.trendpilot.variant.VariantOverrides;

/**
 * BTC 主动买卖（只看已成交）当山寨开仓过滤器（用户 2026-10-30："有没有监控 BTC 的买盘和卖盘？只看已成交的，对山寨有没有帮助"）。
 * <ol>
 * <li>9 种过滤（1h / 4h / 24h 主动买入占比分位 ≥ 20 / ≥ 50、4h 占比 &gt; 52%、4h / 24h CVD 为负不开）分别套在现行 V2 和 V6
 *     （24h 涨幅前 3）上，共 18 组，看结果前登记；每组配同规则随机对照，用 {@link ReleaseCheck} 对各自的基准做发布前检查。</li>
 * <li>描述性统计：每个整点按 BTC 主动买入占比分位分组，之后 24h 山寨池（当天成交额前 150，不含 BTC）等权平均收益，
 *     和全部整点（= 随机挑时间的期望）比较；均值和 95% 区间都按"每周聚类"的同一口径算。</li>
 * </ol>
 * <pre>
 * java -cp ... com.trendpilot.backtest.BtcFlowStudy 数据目录 输出目录 起始月 结束月 最后一天 开始交易 验证期开始 线程数 [v2|v6|all]
 * </pre>
 */
public final class BtcFlowStudy {

    /** 一种过滤：名字 + {@link BtcFlow#gate} 写法。 */
    record Filter(String id, String label, String spec) {
    }

    static List<Filter> filters() {
        List<Filter> f = new ArrayList<>();
        for (int h : new int[]{1, 4, 24}) {
            f.add(new Filter("F_" + h + "h分位≥20", "BTC " + h + "h 主动买入占比分位 < 20 不开", "pct-min:" + h + ":20"));
            f.add(new Filter("F_" + h + "h分位≥50", "BTC " + h + "h 主动买入占比分位 ≥ 50 才开", "pct-min:" + h + ":50"));
        }
        f.add(new Filter("F_4h占比>52%", "BTC 4h 主动买入占比 > 52% 才开", "share-min:4:0.52"));
        f.add(new Filter("F_CVD4h≥0", "BTC 4h CVD 为负不开", "cvd-pos:4"));
        f.add(new Filter("F_CVD24h≥0", "BTC 24h CVD 为负不开", "cvd-pos:24"));
        return f;
    }

    /** V6 线上写法（和每周回测 W_M_24h前3 相同）。 */
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
        String which = args.length > 8 ? args[8] : "all";
        Files.createDirectories(outDir);

        StrategyParams params = BacktestMain.loadParams();
        Set<String> excluded = new HashSet<>();
        Archive archive = new Archive(dataDir);
        List<String> symbols = new ArrayList<>();
        for (String s : archive.usdtSymbols()) {
            String base = s.substring(0, s.length() - 4);
            if (params.universe().excludedBaseAssets().contains(base) || params.universe().tokenizedStockBaseAssets().contains(base)
                    || params.universe().blocklist().contains(s)) {
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
        Regimes regimes = new Regimes(data);
        SignalTape tape = new SignalTape(data, new EarlySignalCalculator(params));
        BtcFlow flow = new BtcFlow(data.btc);
        int fromQ = q(data, tradeFrom);
        int splitQ = q(data, split);

        StringBuilder md = new StringBuilder();
        md.append("# BTC 主动买卖当山寨开仓过滤（").append(tradeFrom).append(" ～ ").append(endDay.minusDays(1)).append("）\n\n");
        md.append("校准期 ").append(tradeFrom).append(" ～ ").append(split.minusDays(1)).append("，验证期 ").append(split).append(" ～ ")
                .append(endDay.minusDays(1)).append("。BTC 主动买入 = K 线 taker buy quote volume。\n\n");
        md.append(coverage(data, flow, fromQ, size));
        md.append(describe(data, flow, fromQ, splitQ, size));

        List<String> bases = which.equals("all") ? List.of("v2", "v6") : List.of(which);
        for (String b : bases) {
            md.append(runBase(b, data, regimes, tape, params, flow, fromQ, splitQ, size, threads));
        }
        Files.writeString(outDir.resolve("report.md"), md.toString());
        System.out.println(md);
    }

    /** 一套基准（V2 或 V6）+ 9 种过滤 + 各自的随机对照，发布前检查对这套基准做。 */
    static String runBase(String base, MarketData data, Regimes regimes, SignalTape tape, StrategyParams params, BtcFlow flow,
                          int fromQ, int splitQ, int size, int threads) throws Exception {
        VariantOverrides ov = base.equals("v6") ? VariantOverrides.parseInline(V6) : null;
        String baseName = base.equals("v6") ? "V6" : "现行V2";
        List<B0Sim> todo = new ArrayList<>();
        todo.add(EntryStudy.live(baseName, data, regimes, tape, params, fromQ, size).overrides(ov));
        todo.add(EntryStudy.live(baseName + " 随机", data, regimes, tape, params, fromQ, size).overrides(ov).randomEntry(1001));
        int seed = base.equals("v6") ? 7000 : 6000;
        List<Filter> fs = filters();
        for (Filter f : fs) {
            IntPredicate g = flow.gate(f.spec());
            todo.add(EntryStudy.live(baseName + " " + f.id(), data, regimes, tape, params, fromQ, size).overrides(ov).buyGate(g));
            todo.add(EntryStudy.live(baseName + " " + f.id() + " 随机", data, regimes, tape, params, fromQ, size).overrides(ov).buyGate(g)
                    .randomEntry(seed++));
        }
        Map<String, Sim> byName = new LinkedHashMap<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Sim>> futures = new ArrayList<>();
            for (B0Sim sim : todo) {
                futures.add(pool.submit(() -> {
                    sim.run();
                    System.out.println("完成 " + sim.name() + "：" + sim.trades().size() + " 笔");
                    return sim;
                }));
            }
            for (Future<Sim> f : futures) {
                Sim s = f.get();
                byName.put(s.name(), s);
            }
        }
        Sim bs = byName.get(baseName);
        double cap = params.capital().hardCap();
        ReleaseCheck.Period bF = ReleaseCheck.Period.of(bs, regimes, fromQ, size, cap);
        ReleaseCheck.Period bV = ReleaseCheck.Period.of(bs, regimes, splitQ, size);
        ReleaseCheck.Period bR = ReleaseCheck.Period.of(byName.get(baseName + " 随机"), regimes, fromQ, size);
        double[] bReg = Metrics.of(bs, regimes, fromQ, size).regimeEquity();
        StringBuilder md = new StringBuilder();
        md.append("## 套在 ").append(baseName).append(" 上（9 组）\n\n");
        md.append("| 方案 | 结论 | 验证期 权益/回撤 | 全期 权益/回撤 | 净值回撤 | 卡玛 | 笔数 | 每笔差 ±95%（对基准） | 每笔差 ±95%（对随机） | 不满足的检查 |\n");
        md.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---|\n");
        double[] rb = ReleaseCheck.diff(bF, bR);
        md.append(String.format(Locale.ROOT, "| %s（基准） | — | %,.0f / %,.0f | %,.0f / %,.0f | %.1f%% | %.1f | %d | — | %+.2f ±%.2f | — |%n",
                baseName, bV.equity(), bV.maxDd(), bF.equity(), bF.maxDd(), bF.navDd() * 100, bF.calmar(), bF.trades(), rb[0], rb[1]));
        StringBuilder reg = new StringBuilder();
        reg.append("\n六种行情（全期权益 U）：\n\n| 方案 |");
        for (int r = 1; r <= 6; r++) reg.append(' ').append(Regimes.NAMES[r]).append(" |");
        reg.append("\n|---|---:|---:|---:|---:|---:|---:|\n");
        reg.append(regRow(baseName + "（基准）", bReg));
        for (Filter f : fs) {
            Sim s = byName.get(baseName + " " + f.id());
            ReleaseCheck.Period cF = ReleaseCheck.Period.of(s, regimes, fromQ, size, cap);
            ReleaseCheck.Period cV = ReleaseCheck.Period.of(s, regimes, splitQ, size);
            ReleaseCheck.Period rnd = ReleaseCheck.Period.of(byName.get(baseName + " " + f.id() + " 随机"), regimes, fromQ, size);
            double[] cReg = Metrics.of(s, regimes, fromQ, size).regimeEquity();
            ReleaseCheck.Result r = ReleaseCheck.evaluate(cF, cV, bF, bV, cReg, bReg, rnd);
            List<String> why = new ArrayList<>(r.checks().entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList());
            why.addAll(r.notes());
            md.append(String.format(Locale.ROOT, "| %s | %s | %,.0f / %,.0f | %,.0f / %,.0f | %.1f%% | %.1f | %d | %+.2f ±%.2f | %+.2f ±%.2f | %s |%n",
                    f.label(), r.verdict(), cV.equity(), cV.maxDd(), cF.equity(), cF.maxDd(), cF.navDd() * 100, cF.calmar(), cF.trades(),
                    r.perTradeDiff()[0], r.perTradeDiff()[1], r.randomDiff()[0], r.randomDiff()[1], why.isEmpty() ? "—" : String.join("；", why)));
            reg.append(regRow(f.label(), cReg));
        }
        md.append(reg).append('\n');
        return md.toString();
    }

    private static String regRow(String name, double[] r) {
        StringBuilder b = new StringBuilder("| ").append(name).append(" |");
        for (int i = 1; i <= 6; i++) b.append(String.format(Locale.ROOT, " %,.0f |", r[i]));
        return b.append('\n').toString();
    }

    /** BTC 主动买入占比的分布和数据覆盖（确认 K 线里有这一列）。 */
    static String coverage(MarketData data, BtcFlow flow, int fromQ, int toQ) {
        List<Double> s4 = new ArrayList<>();
        int hours = 0, missing = 0;
        for (int q = fromQ - fromQ % 4 + 4; q < toQ; q += 4) {
            hours++;
            double v = flow.share(q, 4);
            if (Double.isNaN(v)) missing++;
            else s4.add(v);
        }
        double[] a = s4.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        if (a.length == 0) return "**BTC K 线没有主动买入数据。**\n\n";
        long over52 = s4.stream().filter(x -> x > 0.52).count();
        return String.format(Locale.ROOT, "数据：整点 %,d 个，BTC 4h 占比缺失 %d 个。4h 占比分布：5%% 分位 %.1f%%、中位数 %.1f%%、95%% 分位 %.1f%%；"
                        + "> 52%% 的整点占 %.1f%%。%n%n",
                hours, missing, a[(int) (a.length * 0.05)] * 100, a[a.length / 2] * 100, a[(int) (a.length * 0.95)] * 100,
                100.0 * over52 / a.length);
    }

    /**
     * 描述性统计：整点 q（Discovery 时刻）之后 24h 山寨池等权平均收益（%），按 BTC 主动买入占比分位 / CVD 分组，和全部整点比较。
     * 区间：按周聚类的稳健标准误（24h 收益相邻整点重叠，周聚类比按天更保守），均值和差值同一口径。
     */
    static String describe(MarketData data, BtcFlow flow, int fromQ, int splitQ, int toQ) {
        List<int[]> qs = new ArrayList<>();
        List<Double> rets = new ArrayList<>();
        for (int q = fromQ - fromQ % 4 + 4; q + MarketData.DAY < toQ; q += 4) {
            double sum = 0;
            int n = 0;
            for (String sym : data.universe(q)) {
                Series s = data.get(sym);
                if (s == null || !s.has(q)) continue;
                double c = s.has(q + MarketData.DAY) ? s.close[q + MarketData.DAY] : s.lastClose(q + MarketData.DAY, MarketData.DAY);
                if (Double.isNaN(c)) continue;
                sum += (c / s.close[q] - 1) * 100;
                n++;
            }
            if (n < 20) continue;
            qs.add(new int[]{q});
            rets.add(sum / n);
        }
        StringBuilder md = new StringBuilder("## 描述性统计：BTC 主动买入占比之后 24h，山寨池等权平均收益\n\n");
        md.append("每个整点一个样本（Discovery 时刻，只用已收盘 K 线），山寨池 = 当天成交额前 150（不含 BTC），等权；")
                .append("对照 = 全部整点（等于随机挑时间买入山寨池的期望）。均值和 95% 区间都按周聚类；差值 = 该组 − 其余整点。\n\n");
        Map<String, IntPredicate> groups = new LinkedHashMap<>();
        for (int h : BtcFlow.WINDOWS) {
            final int hh = h;
            groups.put("BTC " + h + "h 占比分位 < 20（低）", q -> flow.percentile(q, hh) < 20);
            groups.put("BTC " + h + "h 占比分位 ≥ 80（高）", q -> flow.percentile(q, hh) >= 80);
        }
        groups.put("BTC 4h 占比 > 52%", q -> flow.share(q, 4) > 0.52);
        groups.put("BTC 4h CVD < 0", q -> flow.cvd(q, 4) < 0);
        groups.put("BTC 4h CVD ≥ 0", q -> flow.cvd(q, 4) >= 0);
        groups.put("BTC 24h CVD < 0", q -> flow.cvd(q, 24) < 0);
        for (String period : new String[]{"全期", "验证期"}) {
            md.append("### ").append(period).append("\n\n| 分组 | 整点数 | 之后 24h 山寨池平均 % ±95% | 差值（对其余整点）±95% |\n|---|---:|---:|---:|\n");
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < qs.size(); i++) if (period.equals("全期") || qs.get(i)[0] >= splitQ) idx.add(i);
            double[] all = clusterMean(idx, qs, rets, i -> true);
            md.append(String.format(Locale.ROOT, "| 全部整点（对照） | %,d | %+.3f ±%.3f | — |%n", idx.size(), all[0], all[1]));
            for (var g : groups.entrySet()) {
                IntPredicate in = g.getValue();
                double[] m = clusterMean(idx, qs, rets, i -> in.test(qs.get(i)[0]));
                double[] d = clusterDiff(idx, qs, rets, i -> in.test(qs.get(i)[0]));
                md.append(String.format(Locale.ROOT, "| %s | %,d | %+.3f ±%.3f | %+.3f ±%.3f%s |%n", g.getKey(), (int) m[2], m[0], m[1], d[0], d[1],
                        Math.abs(d[0]) > d[1] ? " *" : ""));
            }
            md.append("\n`*` = 差值 95% 区间不含 0。\n\n");
        }
        return md.toString();
    }

    private static long week(int q) {
        return q / (7L * MarketData.DAY);
    }

    /** 组内均值、95% 半宽（周聚类）、样本数。 */
    static double[] clusterMean(List<Integer> idx, List<int[]> qs, List<Double> rets, IntPredicate in) {
        double sum = 0;
        int n = 0;
        for (int i : idx) {
            if (!in.test(i)) continue;
            sum += rets.get(i);
            n++;
        }
        if (n < 2) return new double[]{Double.NaN, Double.NaN, n};
        double m = sum / n;
        Map<Long, Double> c = new HashMap<>();
        for (int i : idx) if (in.test(i)) c.merge(week(qs.get(i)[0]), (rets.get(i) - m) / n, Double::sum);
        double var = c.values().stream().mapToDouble(x -> x * x).sum();
        return new double[]{m, 1.96 * Math.sqrt(var), n};
    }

    /** 组内均值 − 组外均值、95% 半宽（周聚类，影响函数法，含两组同周的相关）。 */
    static double[] clusterDiff(List<Integer> idx, List<int[]> qs, List<Double> rets, IntPredicate in) {
        double sa = 0, sb = 0;
        int na = 0, nb = 0;
        for (int i : idx) {
            if (in.test(i)) {
                sa += rets.get(i);
                na++;
            } else {
                sb += rets.get(i);
                nb++;
            }
        }
        if (na < 2 || nb < 2) return new double[]{Double.NaN, Double.NaN};
        double ma = sa / na, mb = sb / nb;
        Map<Long, Double> c = new HashMap<>();
        for (int i : idx) {
            double psi = in.test(i) ? (rets.get(i) - ma) / na : -(rets.get(i) - mb) / nb;
            c.merge(week(qs.get(i)[0]), psi, Double::sum);
        }
        double var = c.values().stream().mapToDouble(x -> x * x).sum();
        return new double[]{ma - mb, 1.96 * Math.sqrt(var)};
    }

    private static int q(MarketData data, LocalDate d) {
        return (int) ((d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - data.start) / Series.Q);
    }
}
