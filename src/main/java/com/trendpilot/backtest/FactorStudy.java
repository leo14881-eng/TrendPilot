package com.trendpilot.backtest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * 因子检验（组合方式，不重复计数）：每隔 H 小时调仓一次，按因子在标的池里排名，买排名前 20% 的币等权持有到下次调仓。
 * 每期记录：组合净收益（扣来回手续费和冲击，按换手比例计）、相对同期全池等权的超额。期与期不重叠，区间按期数算。
 * 另外报告"BTC 在 50 日均线上方才持有，否则拿现金"的版本，看能不能把绝对收益变正。
 */
public final class FactorStudy {

    static final int[] HOLD_HOURS = {24, 72, 168, 336};
    static final double FEE = 0.001;
    static final double TOP = 0.2;

    /** 一个因子：名字和打分（越大越好，NaN 表示不参与）。 */
    record Factor(String name, ToDoubleFunction<Ctx> score) {
    }

    /** 某个币在调仓时刻能看到的数据。 */
    record Ctx(MarketData data, String sym, int q) {
        Series s() {
            return data.get(sym);
        }

        double ret(int n) {
            Series s = s();
            if (!s.has(q) || q - n < 0) return Double.NaN;
            double p = s.lastClose(q - n, 8);
            return Double.isNaN(p) || p <= 0 ? Double.NaN : (s.close[q] / p - 1) * 100;
        }

        /** 过去 n 小时小时收益的标准差（%）。 */
        double vol(int hours) {
            Series s = s();
            double sum = 0, sum2 = 0;
            int k = 0;
            for (int h = 0; h < hours; h++) {
                int a = q - h * 4, b = a - 4;
                if (b < 0) break;
                double pa = s.lastClose(a, 4), pb = s.lastClose(b, 4);
                if (Double.isNaN(pa) || Double.isNaN(pb) || pb <= 0) continue;
                double r = (pa / pb - 1) * 100;
                sum += r;
                sum2 += r * r;
                k++;
            }
            if (k < hours / 2) return Double.NaN;
            double m = sum / k;
            return Math.sqrt(Math.max(0, sum2 / k - m * m));
        }

        /** 最近 24h 成交额 ÷ 之前 7 天日均成交额。 */
        double volumeSurge() {
            Series s = s();
            double last = s.volume(q, MarketData.DAY);
            double prior = s.volume(q - MarketData.DAY, 7 * MarketData.DAY) / 7;
            return prior <= 0 ? Double.NaN : last / prior;
        }

        /** 离 30 天最高收盘价的距离（%，≤0）。 */
        double fromHigh30() {
            Series s = s();
            double hi = Double.NaN;
            for (int i = Math.max(0, q - 30 * MarketData.DAY); i <= q; i += 4) {
                if (s.has(i)) hi = Double.isNaN(hi) ? s.close[i] : Math.max(hi, s.close[i]);
            }
            return Double.isNaN(hi) || !s.has(q) ? Double.NaN : (s.close[q] / hi - 1) * 100;
        }
    }

    static List<Factor> factors() {
        int d = MarketData.DAY;
        List<Factor> f = new ArrayList<>();
        f.add(new Factor("7 天涨幅（动量）", c -> c.ret(7 * d)));
        f.add(new Factor("30 天涨幅（动量）", c -> c.ret(30 * d)));
        f.add(new Factor("30 天涨幅去掉最近 7 天", c -> {
            double a = c.ret(30 * d), b = c.ret(7 * d);
            return Double.isNaN(a) || Double.isNaN(b) ? Double.NaN : (1 + a / 100) / (1 + b / 100) - 1;
        }));
        f.add(new Factor("24h 跌幅（短线反转）", c -> -c.ret(d)));
        f.add(new Factor("7 天跌幅（周反转）", c -> -c.ret(7 * d)));
        f.add(new Factor("低波动（7 天小时波动小）", c -> -c.vol(168)));
        f.add(new Factor("风险调整动量（30 天涨幅 ÷ 波动）", c -> {
            double r = c.ret(30 * d), v = c.vol(720);
            return Double.isNaN(r) || Double.isNaN(v) || v <= 0 ? Double.NaN : r / v;
        }));
        f.add(new Factor("接近 30 天新高", FactorStudy.Ctx::fromHigh30));
        f.add(new Factor("放量（24h 成交额 ÷ 7 天日均）", FactorStudy.Ctx::volumeSurge));
        f.add(new Factor("放量但价格没动（|24h| < 3%）", c -> Math.abs(c.ret(d)) < 3 ? c.volumeSurge() : Double.NaN));
        f.add(new Factor("缩量（成交额低于 7 天日均）", c -> -c.volumeSurge()));
        f.add(new Factor("成交额大（流动性）", c -> c.s().volume(c.q(), d)));
        return f;
    }

    private final MarketData data;
    private final int poolSize;
    /** [因子][持有期] → 每期：净收益%、超额%、带 BTC 趋势过滤的净收益%、年份。 */
    private final Map<String, List<double[]>[]> rows = new LinkedHashMap<>();
    private final List<double[]>[] pool;
    private final List<double[]>[] btc;

    @SuppressWarnings("unchecked")
    public FactorStudy(MarketData data, int poolSize) {
        this.data = data;
        this.poolSize = poolSize;
        pool = new List[HOLD_HOURS.length];
        btc = new List[HOLD_HOURS.length];
        for (int h = 0; h < HOLD_HOURS.length; h++) {
            pool[h] = new ArrayList<>();
            btc[h] = new ArrayList<>();
        }
    }

    public void run(int fromQ, int toQ) {
        List<Factor> fs = factors();
        for (Factor f : fs) {
            List<double[]>[] arr = newArr();
            rows.put(f.name(), arr);
        }
        Series b = data.get(MarketData.BTC);
        for (int h = 0; h < HOLD_HOURS.length; h++) {
            int step = HOLD_HOURS[h] * 4;
            Map<String, java.util.Set<String>> prevHold = new LinkedHashMap<>();
            java.util.Set<String> prevPool = new java.util.HashSet<>();
            for (int q = fromQ + Math.floorMod(-fromQ, 4); q + step < toQ; q += step) {
                List<String> uni = data.universe(q);
                if (uni.size() > poolSize) uni = uni.subList(0, poolSize);
                int year = Instant.ofEpochMilli(data.time(q)).atZone(ZoneOffset.UTC).getYear();
                boolean btcUp = btcAboveMa(b, q, 50);
                double[] fwd = new double[uni.size()];
                double[] cost = new double[uni.size()];
                double sum = 0;
                int n = 0;
                for (int i = 0; i < uni.size(); i++) {
                    fwd[i] = forward(uni.get(i), q, step);
                    var t = data.ticker(uni.get(i), q);
                    cost[i] = t == null ? Double.NaN : (FEE + Costs.impact(t.quoteVolume(), 1000)) * 100;
                    if (!Double.isNaN(fwd[i])) {
                        sum += fwd[i];
                        n++;
                    }
                }
                if (n < 10) continue;
                double poolMean = sum / n;
                // 全池等权：每期换手按进出的币计
                java.util.Set<String> curPool = new java.util.HashSet<>(uni);
                pool[h].add(row(poolMean - turnoverCost(uni, prevPool, cost), 0, btcUp, year));
                prevPool = curPool;
                double bf = forward(MarketData.BTC, q, step);
                if (!Double.isNaN(bf)) btc[h].add(row(bf - (prevHold.isEmpty() ? 0.2 : 0), bf - poolMean, btcUp, year));
                for (Factor f : fs) {
                    double[] sc = new double[uni.size()];
                    for (int i = 0; i < uni.size(); i++) {
                        double v = Double.NaN;
                        try {
                            v = Double.isNaN(fwd[i]) || Double.isNaN(cost[i]) ? Double.NaN : f.score().applyAsDouble(new Ctx(data, uni.get(i), q));
                        } catch (RuntimeException ignored) {
                            // 数据不够的币不参与
                        }
                        sc[i] = v;
                    }
                    double cut = quantile(sc, 1 - TOP);
                    if (Double.isNaN(cut)) continue;
                    List<String> pick = new ArrayList<>();
                    double s = 0;
                    for (int i = 0; i < uni.size(); i++) {
                        if (!Double.isNaN(sc[i]) && sc[i] >= cut) {
                            pick.add(uni.get(i));
                            s += fwd[i];
                        }
                    }
                    if (pick.size() < 3) continue;
                    double gross = s / pick.size();
                    java.util.Set<String> prev = prevHold.getOrDefault(f.name(), java.util.Set.of());
                    double tc = turnoverCost(pick, prev, costMap(uni, cost));
                    rows.get(f.name())[h].add(row(gross - tc, gross - poolMean, btcUp, year));
                    prevHold.put(f.name(), new java.util.HashSet<>(pick));
                }
            }
        }
    }

    private static double[] row(double net, double excess, boolean btcUp, int year) {
        return new double[]{net, excess, btcUp ? net : 0, year};
    }

    private static Map<String, Double> costMap(List<String> uni, double[] cost) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < uni.size(); i++) m.put(uni.get(i), Double.isNaN(cost[i]) ? 0.35 : cost[i]);
        return m;
    }

    /** 等权组合的换手成本（%）：新买入的币付买入成本，卖出的币付卖出成本，按组合权重摊。 */
    private static double turnoverCost(List<String> now, java.util.Set<String> prev, Map<String, Double> cost) {
        if (now.isEmpty()) return 0;
        double w = 1.0 / now.size(), c = 0;
        for (String s : now) if (!prev.contains(s)) c += w * cost.getOrDefault(s, 0.35);
        double wp = prev.isEmpty() ? 0 : 1.0 / prev.size();
        for (String s : prev) if (!now.contains(s)) c += wp * cost.getOrDefault(s, 0.35);
        if (Double.isNaN(c)) c = 0.35;
        return c;
    }

    private static double turnoverCost(List<String> now, java.util.Set<String> prev, double[] cost) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < now.size(); i++) m.put(now.get(i), Double.isNaN(cost[i]) ? 0.35 : cost[i]);
        return turnoverCost(now, prev, m);
    }

    @SuppressWarnings("unchecked")
    private static List<double[]>[] newArr() {
        List<double[]>[] a = new List[HOLD_HOURS.length];
        for (int h = 0; h < a.length; h++) a[h] = new ArrayList<>();
        return a;
    }

    private double forward(String sym, int q, int n) {
        Series s = data.get(sym);
        if (s == null || !s.has(q)) return Double.NaN;
        double exit = s.lastClose(q + n, n);
        return Double.isNaN(exit) ? Double.NaN : (exit / s.close[q] - 1) * 100;
    }

    /** BTC 收盘价在过去 days 天日收盘均线上方。 */
    static boolean btcAboveMa(Series b, int q, int days) {
        double sum = 0;
        int k = 0;
        for (int d = 0; d < days; d++) {
            int i = q - d * MarketData.DAY;
            if (i < 0) break;
            double c = b.lastClose(i, 8);
            if (!Double.isNaN(c)) {
                sum += c;
                k++;
            }
        }
        return k >= days / 2 && b.has(q) && b.close[q] > sum / k;
    }

    static double quantile(double[] v, double p) {
        double[] c = Arrays.stream(v).filter(x -> !Double.isNaN(x)).sorted().toArray();
        if (c.length < 5) return Double.NaN;
        return c[Math.min(c.length - 1, (int) Math.floor(p * c.length))];
    }

    /** {均值, 95% 下限, 上限}。 */
    static double[] ci(double[] v) {
        if (v.length < 2) return new double[]{Double.NaN, Double.NaN, Double.NaN};
        double m = Arrays.stream(v).average().orElse(0);
        double se = Math.sqrt(Arrays.stream(v).map(x -> (x - m) * (x - m)).sum() / (v.length - 1) / v.length);
        return new double[]{m, m - 1.96 * se, m + 1.96 * se};
    }

    /** 每期收益连乘成整段的累计收益%。 */
    static double compound(double[] v) {
        double nav = 1;
        for (double x : v) nav *= 1 + x / 100;
        return (nav - 1) * 100;
    }

    static double maxDd(double[] v) {
        double nav = 1, peak = 1, dd = 0;
        for (double x : v) {
            nav *= 1 + x / 100;
            peak = Math.max(peak, nav);
            dd = Math.max(dd, 1 - nav / peak);
        }
        return dd * 100;
    }

    public String markdown() {
        StringBuilder b = new StringBuilder();
        b.append("## 因子检验（前 ").append(poolSize).append(" 个币，买排名前 20% 等权，按持有期调仓，期与期不重叠）\n\n");
        b.append("净收益已扣手续费和冲击（只对换进换出的币收）。超额 = 组合毛收益 − 同期全池等权毛收益。")
                .append("\"BTC 趋势过滤\" = 只在 BTC 高于 50 日均线时持有，否则拿现金。区间全在 0 以上才算有优势。\n\n");
        for (int h = 0; h < HOLD_HOURS.length; h++) {
            b.append("### 持有 ").append(HOLD_HOURS[h]).append(" 小时调仓一次\n\n");
            b.append("| 方案 | 期数 | 每期净收益% | 95% 区间 | 每期超额% | 超额 95% 区间 | 全程累计% | 最大回撤% | 加 BTC 趋势过滤累计% | 过滤后回撤% |\n|---|---|---|---|---|---|---|---|---|---|\n");
            line(b, "全池等权（基准）", pool[h]);
            line(b, "只持有 BTC", btc[h]);
            for (Map.Entry<String, List<double[]>[]> e : rows.entrySet()) line(b, e.getKey(), e.getValue()[h]);
            b.append('\n');
        }
        int[] years = pool[2].stream().mapToInt(x -> (int) x[3]).distinct().sorted().toArray();
        b.append("### 按年份：持有 168 小时，每期超额%（年内期数平均）\n\n| 方案 |");
        for (int y : years) b.append(' ').append(y).append(" |");
        b.append("\n|---|");
        for (int y : years) b.append("---|");
        b.append('\n');
        yearLine(b, "只持有 BTC", btc[2], years, 1);
        for (Map.Entry<String, List<double[]>[]> e : rows.entrySet()) yearLine(b, e.getKey(), e.getValue()[2], years, 1);
        b.append("\n### 按年份：持有 168 小时，每期净收益%\n\n| 方案 |");
        for (int y : years) b.append(' ').append(y).append(" |");
        b.append("\n|---|");
        for (int y : years) b.append("---|");
        b.append('\n');
        yearLine(b, "全池等权（基准）", pool[2], years, 0);
        yearLine(b, "只持有 BTC", btc[2], years, 0);
        for (Map.Entry<String, List<double[]>[]> e : rows.entrySet()) yearLine(b, e.getKey(), e.getValue()[2], years, 0);
        return b.append('\n').toString();
    }

    private static void line(StringBuilder b, String name, List<double[]> rs) {
        if (rs.isEmpty()) {
            b.append("| ").append(name).append(" | 0 |  |  |  |  |  |  |  |  |\n");
            return;
        }
        double[] net = rs.stream().mapToDouble(r -> r[0]).toArray();
        double[] ex = rs.stream().mapToDouble(r -> r[1]).toArray();
        double[] filt = rs.stream().mapToDouble(r -> r[2]).toArray();
        double[] a = ci(net), e = ci(ex);
        b.append(String.format(Locale.ROOT, "| %s | %d | %.2f | %.2f ~ %.2f | %.2f | %.2f ~ %.2f | %.0f | %.0f | %.0f | %.0f |%n", name, rs.size(),
                a[0], a[1], a[2], e[0], e[1], e[2], compound(net), maxDd(net), compound(filt), maxDd(filt)));
    }

    private static void yearLine(StringBuilder b, String name, List<double[]> rs, int[] years, int col) {
        b.append("| ").append(name).append(" |");
        for (int y : years) {
            double[] v = rs.stream().filter(r -> r[3] == y).mapToDouble(r -> r[col]).toArray();
            double[] c = ci(v);
            b.append(v.length == 0 ? " - |" : String.format(Locale.ROOT, " %.2f（%.2f ~ %.2f）|", c[0], c[1], c[2]));
        }
        b.append('\n');
    }

    int periods(String factor, int h) {
        return rows.get(factor)[h].size();
    }
}
