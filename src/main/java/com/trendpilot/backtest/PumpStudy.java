package com.trendpilot.backtest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 追大涨检验：山寨出现大涨后追进去短线做，能不能赚。
 * 每根 15m 收盘检查触发条件；同一个币同一条件 24 小时内只算第一次。在下一根 15m 开盘买入（实际能做到的最早时间），
 * 按多种卖法计算扣手续费和冲击后的净收益；同一根 K 线按 开→低→高→收 走（先触发止损）。
 */
public final class PumpStudy {

    static final double FEE = 0.001;

    record Trigger(String name, Cond cond) {
    }

    interface Cond {
        boolean hit(Series s, int q, double rank24);
    }

    record Exit(String name, int maxBars, double trailPct, double takePct, double stopPct) {
    }

    static List<Trigger> triggers() {
        List<Trigger> t = new ArrayList<>();
        t.add(new Trigger("1 小时涨 ≥5% 且放量 3 倍", (s, q, r) -> ret(s, q, 4) >= 5 && volRatio(s, q, 4) >= 3));
        t.add(new Trigger("1 小时涨 ≥10%", (s, q, r) -> ret(s, q, 4) >= 10));
        t.add(new Trigger("15 分钟涨 ≥3% 且放量 5 倍", (s, q, r) -> ret(s, q, 1) >= 3 && volRatio(s, q, 1) >= 5));
        t.add(new Trigger("15 分钟涨 ≥5%", (s, q, r) -> ret(s, q, 1) >= 5));
        t.add(new Trigger("24 小时涨 ≥20%（当天大涨币）", (s, q, r) -> ret(s, q, MarketData.DAY) >= 20));
        t.add(new Trigger("首次进入 24h 涨幅前 5", (s, q, r) -> r >= 0 && r < 5));
        t.add(new Trigger("4 小时涨 ≥8% 且没有从高点回落超 2%", (s, q, r) -> ret(s, q, 16) >= 8 && fromHigh(s, q, 16) > -2));
        return t;
    }

    static List<Exit> exits() {
        List<Exit> e = new ArrayList<>();
        e.add(new Exit("持有 1 小时", 4, 0, 0, 0));
        e.add(new Exit("持有 4 小时", 16, 0, 0, 0));
        e.add(new Exit("持有 24 小时", 96, 0, 0, 0));
        e.add(new Exit("从最高回落 3% 卖（最长 24h）", 96, 3, 0, 0));
        e.add(new Exit("从最高回落 5% 卖（最长 24h）", 96, 5, 0, 0));
        e.add(new Exit("赚 5% 卖 / 亏 3% 卖（最长 24h）", 96, 0, 5, 3));
        e.add(new Exit("赚 10% 卖 / 亏 5% 卖（最长 24h）", 96, 0, 10, 5));
        return e;
    }

    private final MarketData data;
    /** [触发][卖法] → 每笔：净收益%、天、年份。 */
    private final List<double[]>[][] rows;
    private final List<Trigger> triggers = triggers();
    private final List<Exit> exits = exits();

    @SuppressWarnings("unchecked")
    public PumpStudy(MarketData data) {
        this.data = data;
        rows = new List[triggers.size()][exits.size()];
        for (List<double[]>[] r : rows) for (int j = 0; j < r.length; j++) r[j] = new ArrayList<>();
    }

    public void run(int fromQ, int toQ) {
        Map<String, int[]> last = new HashMap<>();
        Map<String, Boolean> wasTop5 = new HashMap<>();
        for (int q = fromQ; q < toQ - 97; q++) {
            List<String> uni = data.universe(q);
            // 24h 涨幅排名（每整点更新一次，省计算）
            Map<String, Integer> rank = new HashMap<>();
            if (q % 4 == 0) {
                List<double[]> rs = new ArrayList<>();
                for (int i = 0; i < uni.size(); i++) {
                    double r = ret(data.get(uni.get(i)), q, MarketData.DAY);
                    if (!Double.isNaN(r)) rs.add(new double[]{r, i});
                }
                rs.sort((a, b) -> Double.compare(b[0], a[0]));
                for (int k = 0; k < rs.size(); k++) rank.put(uni.get((int) rs.get(k)[1]), k);
            }
            int day = q / MarketData.DAY;
            int year = Instant.ofEpochMilli(data.time(q)).atZone(ZoneOffset.UTC).getYear();
            for (String sym : uni) {
                Series s = data.get(sym);
                if (s == null || !s.has(q) || !s.has(q + 1)) continue;
                int[] lastHit = last.computeIfAbsent(sym, k -> new int[triggers.size()]);
                double r24 = -1;
                if (q % 4 == 0) {
                    Integer rk = rank.get(sym);
                    boolean top = rk != null && rk < 5;
                    boolean before = wasTop5.getOrDefault(sym, false);
                    wasTop5.put(sym, top);
                    r24 = top && !before ? rk : -1;
                }
                var t = data.ticker(sym, q);
                if (t == null) continue;
                double imp = Costs.impact(t.quoteVolume(), 1000);
                for (int k = 0; k < triggers.size(); k++) {
                    if (lastHit[k] != 0 && q - lastHit[k] < MarketData.DAY) continue;
                    boolean hit;
                    try {
                        hit = triggers.get(k).cond().hit(s, q, r24);
                    } catch (RuntimeException e) {
                        hit = false;
                    }
                    if (!hit) continue;
                    lastHit[k] = q;
                    double entry = s.open[q + 1] * (1 + imp) * (1 + FEE);
                    for (int j = 0; j < exits.size(); j++) {
                        double exitPx = exit(s, q + 1, exits.get(j), s.open[q + 1], toQ);
                        if (Double.isNaN(exitPx)) continue;
                        double net = (exitPx * (1 - imp) * (1 - FEE) / entry - 1) * 100;
                        rows[k][j].add(new double[]{net, day, year});
                    }
                }
            }
        }
    }

    /** 从 b 根开盘买入，按卖法返回卖出价（未扣费）。 */
    static double exit(Series s, int b, Exit e, double open, int toQ) {
        double peak = open;
        double take = e.takePct() > 0 ? open * (1 + e.takePct() / 100) : Double.NaN;
        double stop = e.stopPct() > 0 ? open * (1 - e.stopPct() / 100) : Double.NaN;
        double lastClose = open;
        for (int i = b; i < Math.min(b + e.maxBars(), toQ); i++) {
            if (!s.has(i)) continue;
            double o = s.open[i], l = s.low[i], h = s.high[i], c = s.close[i];
            if (e.trailPct() > 0) {
                double trail = peak * (1 - e.trailPct() / 100);
                if (o <= trail) return o;
                if (l <= trail) return trail;
                peak = Math.max(peak, h);
                trail = peak * (1 - e.trailPct() / 100);
                if (c <= trail) return c;
            }
            if (!Double.isNaN(stop)) {
                if (o <= stop) return o;
                if (l <= stop) return stop;
                if (h >= take) return Math.max(o, take);
            }
            lastClose = c;
        }
        return lastClose;
    }

    static double ret(Series s, int q, int n) {
        if (q - n < 0 || !s.has(q)) return Double.NaN;
        double p = s.lastClose(q - n, 4);
        return Double.isNaN(p) || p <= 0 ? Double.NaN : (s.close[q] / p - 1) * 100;
    }

    /** 最近 n 根成交额 ÷ 之前 24 小时同样长度的平均。 */
    static double volRatio(Series s, int q, int n) {
        double recent = s.volume(q, n);
        double prior = s.volume(q - n, MarketData.DAY) / MarketData.DAY * n;
        return prior <= 0 ? Double.NaN : recent / prior;
    }

    static double fromHigh(Series s, int q, int n) {
        double hi = Double.NaN;
        for (int i = Math.max(0, q - n + 1); i <= q; i++) if (s.has(i)) hi = Double.isNaN(hi) ? s.high[i] : Math.max(hi, s.high[i]);
        return Double.isNaN(hi) ? Double.NaN : (s.close[q] / hi - 1) * 100;
    }

    static double[] dayCi(List<double[]> rs) {
        Map<Integer, double[]> by = new LinkedHashMap<>();
        for (double[] r : rs) {
            double[] a = by.computeIfAbsent((int) r[1], k -> new double[2]);
            a[0] += r[0];
            a[1]++;
        }
        double[] m = by.values().stream().mapToDouble(a -> a[0] / a[1]).toArray();
        if (m.length < 2) return new double[]{Double.NaN, Double.NaN, Double.NaN};
        double mean = Arrays.stream(m).average().orElse(0);
        double se = Math.sqrt(Arrays.stream(m).map(x -> (x - mean) * (x - mean)).sum() / (m.length - 1) / m.length);
        return new double[]{mean, mean - 1.96 * se, mean + 1.96 * se};
    }

    public String markdown() {
        StringBuilder b = new StringBuilder("## 追大涨检验（前 150 个币；触发后下一根 15 分钟开盘买入；同一币同一条件 24 小时只算一次）\n\n");
        b.append("净收益已扣来回手续费和 1,000U 冲击（大涨时实际滑点可能更大，结果偏乐观）。区间按天汇总；全在 0 以上才算能赚。\n\n");
        for (int k = 0; k < triggers.size(); k++) {
            b.append("### ").append(triggers.get(k).name()).append("\n\n");
            b.append("| 卖法 | 笔数 | 每笔平均净收益% | 95% 区间 | 中位数% | 赚钱比例 | 平均赚% | 平均亏% | 1000U 每笔 | 按年份每笔平均% |\n|---|---|---|---|---|---|---|---|---|---|\n");
            for (int j = 0; j < exits.size(); j++) {
                List<double[]> rs = rows[k][j];
                if (rs.isEmpty()) continue;
                double[] v = rs.stream().mapToDouble(r -> r[0]).toArray();
                double[] ci = dayCi(rs);
                double[] sorted = v.clone();
                Arrays.sort(sorted);
                double win = Arrays.stream(v).filter(x -> x > 0).count() * 100.0 / v.length;
                double avgW = Arrays.stream(v).filter(x -> x > 0).average().orElse(0);
                double avgL = Arrays.stream(v).filter(x -> x <= 0).average().orElse(0);
                double mean = Arrays.stream(v).average().orElse(0);
                StringBuilder yr = new StringBuilder();
                rs.stream().mapToInt(r -> (int) r[2]).distinct().sorted().forEach(y -> {
                    double m = rs.stream().filter(r -> r[2] == y).mapToDouble(r -> r[0]).average().orElse(0);
                    yr.append(y).append(": ").append(String.format(Locale.ROOT, "%.2f", m)).append("；");
                });
                b.append(String.format(Locale.ROOT, "| %s | %d | %.2f | %.2f ~ %.2f | %.2f | %.0f%% | %.2f | %.2f | %.1f U | %s |%n",
                        exits.get(j).name(), v.length, mean, ci[1], ci[2], sorted[sorted.length / 2], win, avgW, avgL, mean * 10, yr));
            }
            b.append('\n');
        }
        return b.toString();
    }

    int count(int trigger, int exit) {
        return rows[trigger][exit].size();
    }
}
