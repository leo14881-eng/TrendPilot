package com.trendpilot.backtest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.variant.VariantOverrides;

/**
 * 山寨自己的短周期主动买入激增 + 放量（用户 2026-10-30："实时买单是否增加，成交量大的话应该会上涨"）。只看已成交（K 线 taker buy）。
 * <p>迹象（看结果前登记，8 组）：最近 15 / 60 分钟主动买入额 ≥ 之前 24h 同长度平均的 3 / 5 倍，主动买入占比 ≥ 60% / 70%，
 * 同窗口价格涨幅 &lt; 3%。对照：只放量不看方向（成交额 ≥ 3 / 5 倍、涨幅 &lt; 3%，4 组）；同期随机（每次触发同一根 K 线在池内随机挑一个币）。
 * <p>事件研究：每根 15m 收盘检查，同一币同一迹象 24h 只算一次，下一根开盘买，扣来回 0.1% 手续费和 1,000U 冲击；
 * 卖法：持有 1h / 4h / 24h、"+5% 后从最高回落 3% 卖（最长 24h）"。均值和 95% 区间按天聚类。
 * <p>组合回测（V6 的卖法）：8 个迹象各自当 V6 的买入规则（每小时 Discovery 时池内触发的按买入放大倍数取前 3），退出、资金、冻结照 V2，
 * 对 V6 做发布前检查，并配同规则随机对照。
 */
final class AltFlowStudy {

    static final double FEE = PumpStudy.FEE;

    record Sign(String id, String label, int bars, double mult, double share, boolean volumeOnly) {
    }

    static List<Sign> signs() {
        List<Sign> s = new ArrayList<>();
        for (int bars : new int[]{1, 4}) {
            String w = bars == 1 ? "15m" : "60m";
            for (double m : new double[]{3, 5}) {
                for (double sh : new double[]{0.60, 0.70}) {
                    s.add(new Sign("A_" + w + "_买入" + (int) m + "倍_占比" + (int) (sh * 100), String.format(Locale.ROOT,
                            "%s 主动买入 ≥ %d 倍、占比 ≥ %d%%、涨幅 < 3%%", w, (int) m, (int) (sh * 100)), bars, m, sh, false));
                }
            }
        }
        for (int bars : new int[]{1, 4}) {
            String w = bars == 1 ? "15m" : "60m";
            for (double m : new double[]{3, 5}) {
                s.add(new Sign("V_" + w + "_放量" + (int) m + "倍", String.format(Locale.ROOT, "对照：%s 只放量 ≥ %d 倍、涨幅 < 3%%", w, (int) m),
                        bars, m, 0, true));
            }
        }
        return s;
    }

    record Exit(String name, int bars, double armPct, double trailPct) {
    }

    static List<Exit> exits() {
        return List.of(new Exit("持有 1h", 4, 0, 0), new Exit("持有 4h", 16, 0, 0), new Exit("持有 24h", 96, 0, 0),
                new Exit("+5% 后回落 3% 卖（最长 24h）", 96, 5, 3));
    }

    /** 每个币的前缀和：成交额、主动买入额（按需算、缓存）。 */
    private final Map<Series, double[][]> cum = new HashMap<>();
    private final MarketData data;

    AltFlowStudy(MarketData data) {
        this.data = data;
    }

    synchronized double[][] cum(Series s) {
        return cum.computeIfAbsent(s, k -> {
            double[] v = new double[s.size() + 1], b = new double[s.size() + 1];
            for (int i = 0; i < s.size(); i++) {
                boolean ok = s.has(i);
                v[i + 1] = v[i] + (ok ? s.quoteVolume[i] : 0);
                b[i + 1] = b[i] + (ok ? s.takerBuyQuote[i] : 0);
            }
            return new double[][]{v, b};
        });
    }

    /**
     * 第 q 根收盘时是否出现迹象；返回买入（或放量）放大倍数，没出现或缺数据返回 NaN。只用已收盘数据。
     */
    double score(Series s, int q, Sign g) {
        int n = g.bars();
        if (q - n - MarketData.DAY + 1 < 0 || !s.complete(q - n + 1, q + 1)) return Double.NaN;
        double ret = PumpStudy.ret(s, q, n);
        if (Double.isNaN(ret) || ret >= 3) return Double.NaN;
        double[][] c = cum(s);
        double[] arr = g.volumeOnly() ? c[0] : c[1];
        double recent = arr[q + 1] - arr[q - n + 1];
        double prior = (arr[q - n + 1] - arr[q - n + 1 - MarketData.DAY]) / MarketData.DAY * n;
        if (!(prior > 0) || !(recent > 0)) return Double.NaN;
        double mult = recent / prior;
        if (mult < g.mult()) return Double.NaN;
        if (!g.volumeOnly()) {
            double vol = c[0][q + 1] - c[0][q - n + 1];
            if (!(vol > 0) || recent / vol < g.share()) return Double.NaN;
        }
        return mult;
    }

    /** 从 b 根开盘买入，按卖法返回卖出价（未扣费）；同一根 K 线按 开→低→高→收。 */
    static double exit(Series s, int b, Exit e, double open, int toQ) {
        double peak = open;
        boolean armed = false;
        double last = open;
        for (int i = b; i < Math.min(b + e.bars(), toQ); i++) {
            if (!s.has(i)) continue;
            if (e.armPct() > 0) {
                if (armed) {
                    double trail = peak * (1 - e.trailPct() / 100);
                    if (s.open[i] <= trail) return s.open[i];
                    if (s.low[i] <= trail) return trail;
                }
                peak = Math.max(peak, s.high[i]);
                if (peak >= open * (1 + e.armPct() / 100)) armed = true;
                if (armed && s.close[i] <= peak * (1 - e.trailPct() / 100)) return s.close[i];
            }
            last = s.close[i];
        }
        return last;
    }

    /** 每笔 {净收益%, 天, 是否验证期, 配对随机净收益%}。 */
    @SuppressWarnings("unchecked")
    String events(int fromQ, int splitQ, int toQ) {
        List<Sign> gs = signs();
        List<Exit> es = exits();
        List<double[]>[][] rows = new List[gs.size()][es.size()];
        for (List<double[]>[] r : rows) for (int j = 0; j < r.length; j++) r[j] = new ArrayList<>();
        Map<String, int[]> last = new HashMap<>();
        Random rnd = new Random(4242);
        for (int q = fromQ; q < toQ - 97; q++) {
            List<String> uni = data.universe(q);
            int day = q / MarketData.DAY;
            for (String sym : uni) {
                Series s = data.get(sym);
                if (s == null || !s.has(q) || !s.has(q + 1)) continue;
                int[] lh = last.computeIfAbsent(sym, k -> new int[gs.size()]);
                for (int k = 0; k < gs.size(); k++) {
                    if (lh[k] != 0 && q - lh[k] < MarketData.DAY) continue;
                    if (Double.isNaN(score(s, q, gs.get(k)))) continue;
                    lh[k] = q;
                    String rs = uni.get(rnd.nextInt(uni.size()));
                    Series r = data.get(rs);
                    boolean rOk = r != null && r.has(q) && r.has(q + 1) && data.ticker(rs, q) != null;
                    double[] own = trade(s, q, es, toQ);
                    double[] ctl = rOk ? trade(r, q, es, toQ) : null;
                    if (own == null) continue;
                    for (int j = 0; j < es.size(); j++) {
                        rows[k][j].add(new double[]{own[j], day, q >= splitQ ? 1 : 0, ctl == null ? Double.NaN : ctl[j]});
                    }
                }
            }
        }
        StringBuilder md = new StringBuilder();
        md.append("### 事件研究（").append(gs.size()).append(" 种迹象 × ").append(es.size()).append(" 种卖法 = ")
                .append(gs.size() * es.size()).append(" 组）\n\n");
        md.append("每笔净收益 %（扣来回 0.1% 手续费和 1,000U 冲击）；95% 区间按天聚类；\"对随机\" = 同一根 K 线在池内随机挑一个币、同样卖法的配对差值。\n\n");
        md.append("| 迹象 | 卖法 | 笔数（验证期） | 全期每笔 ±95% | 验证期每笔 ±95% | 全期对随机 ±95% | 验证期对随机 ±95% |\n|---|---|---:|---:|---:|---:|---:|\n");
        for (int k = 0; k < gs.size(); k++) {
            for (int j = 0; j < es.size(); j++) {
                List<double[]> all = rows[k][j];
                List<double[]> val = all.stream().filter(r -> r[2] > 0).toList();
                md.append(String.format(Locale.ROOT, "| %s | %s | %,d（%,d） | %s | %s | %s | %s |%n", gs.get(k).label(), es.get(j).name(), all.size(), val.size(),
                        ci(all, false), ci(val, false), ci(all, true), ci(val, true)));
            }
        }
        return md.append('\n').toString();
    }

    private static String ci(List<double[]> rs, boolean vsRandom) {
        List<double[]> x = new ArrayList<>();
        for (double[] r : rs) {
            if (vsRandom && Double.isNaN(r[3])) continue;
            x.add(new double[]{vsRandom ? r[0] - r[3] : r[0], r[1]});
        }
        if (x.size() < 10) return "—";
        double[] c = PumpStudy.clusterCi(x);
        boolean sig = c[1] > 0 || c[2] < 0;
        return String.format(Locale.ROOT, "%+.2f ±%.2f%s", c[0], (c[2] - c[1]) / 2, sig ? " *" : "");
    }

    /** 下一根开盘买入，各卖法的净收益 %；缺数据返回 null。 */
    private double[] trade(Series s, int q, List<Exit> es, int toQ) {
        var t = data.ticker(s.symbol, q);
        if (t == null) return null;
        double imp = Costs.impact(t.quoteVolume(), 1000);
        double open = s.open[q + 1];
        double entry = open * (1 + imp) * (1 + FEE);
        double[] out = new double[es.size()];
        for (int j = 0; j < es.size(); j++) {
            double px = exit(s, q + 1, es.get(j), open, toQ);
            out[j] = (px * (1 - imp) * (1 - FEE) / entry - 1) * 100;
        }
        return out;
    }

    static String run(MarketData data, Regimes regimes, SignalTape tape, StrategyParams params, int fromQ, int splitQ, int size, int threads,
                      LocalDate from, LocalDate split, LocalDate to) throws Exception {
        AltFlowStudy st = new AltFlowStudy(data);
        StringBuilder md = new StringBuilder("# 山寨自己的短周期主动买入激增 + 放量（").append(from).append(" ～ ").append(to).append("）\n\n");
        md.append("验证期 ").append(split).append(" ～ ").append(to).append("。15m K 线（5 分钟级别没有测：现有回测数据是 15m）。\n\n");
        md.append(st.events(fromQ, splitQ, size));
        md.append(st.portfolio(regimes, tape, params, fromQ, splitQ, size, threads));
        return md.toString();
    }

    /** 8 个迹象各自当 V6 的买入规则（V6 卖法），对 V6 做发布前检查。 */
    String portfolio(Regimes regimes, SignalTape tape, StrategyParams params, int fromQ, int splitQ, int size, int threads) throws Exception {
        VariantOverrides v6 = VariantOverrides.parseInline(BtcFlowStudy.V6);
        List<B0Sim> todo = new ArrayList<>();
        todo.add(EntryStudy.live("V6", data, regimes, tape, params, fromQ, size).overrides(v6));
        List<Sign> gs = signs().stream().filter(g -> !g.volumeOnly()).toList();
        int seed = 8000;
        for (Sign g : gs) {
            java.util.function.BiFunction<String, Integer, Double> f = (sym, q) -> score(data.get(sym), q, g);
            todo.add(EntryStudy.live(g.id(), data, regimes, tape, params, fromQ, size).pickBy(f, 3));
            todo.add(EntryStudy.live(g.id() + " 随机", data, regimes, tape, params, fromQ, size).pickBy(f, 3).randomEntry(seed++));
        }
        Map<String, Sim> by = new LinkedHashMap<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Sim>> fs = new ArrayList<>();
            for (B0Sim sim : todo) {
                fs.add(pool.submit(() -> {
                    sim.run();
                    System.out.println("完成 " + sim.name() + "：" + sim.trades().size() + " 笔");
                    return sim;
                }));
            }
            for (Future<Sim> f : fs) {
                Sim s = f.get();
                by.put(s.name(), s);
            }
        }
        double cap = params.capital().hardCap();
        Sim base = by.get("V6");
        ReleaseCheck.Period bF = ReleaseCheck.Period.of(base, regimes, fromQ, size, cap);
        ReleaseCheck.Period bV = ReleaseCheck.Period.of(base, regimes, splitQ, size);
        double[] bReg = Metrics.of(base, regimes, fromQ, size).regimeEquity();
        StringBuilder md = new StringBuilder("### 组合回测：迹象当买入规则、V6 的卖法（").append(gs.size()).append(" 组，对 V6 做发布前检查）\n\n");
        md.append("| 方案 | 结论 | 验证期 权益/回撤 | 全期 权益/回撤 | 净值回撤 | 卡玛 | 笔数 | 每笔差 ±95%（对 V6） | 每笔差 ±95%（对随机） | 不满足的检查 |\n");
        md.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---|\n");
        md.append(String.format(Locale.ROOT, "| V6（基准） | — | %,.0f / %,.0f | %,.0f / %,.0f | %.1f%% | %.1f | %d | — | — | — |%n",
                bV.equity(), bV.maxDd(), bF.equity(), bF.maxDd(), bF.navDd() * 100, bF.calmar(), bF.trades()));
        StringBuilder reg = new StringBuilder("\n六种行情（全期权益 U）：\n\n| 方案 |");
        for (int r = 1; r <= 6; r++) reg.append(' ').append(Regimes.NAMES[r]).append(" |");
        reg.append("\n|---|---:|---:|---:|---:|---:|---:|\n").append(row("V6（基准）", bReg));
        for (Sign g : gs) {
            Sim s = by.get(g.id());
            ReleaseCheck.Period cF = ReleaseCheck.Period.of(s, regimes, fromQ, size, cap);
            ReleaseCheck.Period cV = ReleaseCheck.Period.of(s, regimes, splitQ, size);
            ReleaseCheck.Period rnd = ReleaseCheck.Period.of(by.get(g.id() + " 随机"), regimes, fromQ, size);
            double[] cReg = Metrics.of(s, regimes, fromQ, size).regimeEquity();
            ReleaseCheck.Result r = ReleaseCheck.evaluate(cF, cV, bF, bV, cReg, bReg, rnd);
            List<String> why = new ArrayList<>(r.checks().entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList());
            why.addAll(r.notes());
            md.append(String.format(Locale.ROOT, "| %s | %s | %,.0f / %,.0f | %,.0f / %,.0f | %.1f%% | %.1f | %d | %+.2f ±%.2f | %+.2f ±%.2f | %s |%n",
                    g.label(), r.verdict(), cV.equity(), cV.maxDd(), cF.equity(), cF.maxDd(), cF.navDd() * 100, cF.calmar(), cF.trades(),
                    r.perTradeDiff()[0], r.perTradeDiff()[1], r.randomDiff()[0], r.randomDiff()[1], why.isEmpty() ? "—" : String.join("；", why)));
            reg.append(row(g.label(), cReg));
        }
        return md.append(reg).append('\n').toString();
    }

    private static String row(String name, double[] r) {
        StringBuilder b = new StringBuilder("| ").append(name).append(" |");
        for (int i = 1; i <= 6; i++) b.append(String.format(Locale.ROOT, " %,.0f |", r[i]));
        return b.append('\n').toString();
    }
}
