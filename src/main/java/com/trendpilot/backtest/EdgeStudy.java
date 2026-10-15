package com.trendpilot.backtest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.trendpilot.signal.EarlySignal;

/**
 * 信号本身有没有优势（设计文档 7.6 之前的前提）：不管买卖规则，只看每个整点 EARLY 信号出现后
 * 1h / 4h / 24h / 72h 的收益，扣掉来回手续费和 1,000U 冲击后是否为正、是否跑赢同期山寨中位数。
 * 同一时刻的样本高度相关，所以置信区间按"天"汇总后再算（每天先取平均，再对天数做 t 区间）。
 */
public final class EdgeStudy {

    static final int[] HOURS = {1, 4, 24, 72};
    static final double FEE = 0.001;

    /** 分组：评分 ≥8 的 EARLY、其中 24h 内首次出现、评分 6~8、评分 <6 的 EARLY、标的池全部（基准）。 */
    static final String[] GROUPS = {"EARLY 评分≥8", "其中 24h 内首次", "EARLY 评分 6~8", "EARLY 评分<6", "标的池全部（基准）"};

    private final MarketData data;
    private final Regimes regimes;
    private final SignalTape tape;
    private final double minScore;

    /** [组][周期] → 每个样本：净收益%、超额%、所在天、行情。 */
    private final List<double[]>[][] samples;

    @SuppressWarnings("unchecked")
    public EdgeStudy(MarketData data, Regimes regimes, SignalTape tape, double minScore) {
        this.data = data;
        this.regimes = regimes;
        this.tape = tape;
        this.minScore = minScore;
        samples = new List[GROUPS.length][HOURS.length];
        for (List<double[]>[] row : samples) for (int h = 0; h < HOURS.length; h++) row[h] = new ArrayList<>();
    }

    public void run(int fromQ, int toQ) {
        Map<String, Integer> lastEarly = new LinkedHashMap<>();
        for (int q = fromQ + Math.floorMod(-fromQ, 4); q < toQ; q += 4) {
            List<String> universe = data.universe(q);
            int regime = regimes.at(q);
            int day = q / MarketData.DAY;
            // 每个周期先算整个标的池的远期收益，取中位数作超额基准
            double[][] fwd = new double[universe.size()][HOURS.length];
            for (int i = 0; i < universe.size(); i++) {
                for (int h = 0; h < HOURS.length; h++) fwd[i][h] = forward(universe.get(i), q, HOURS[h] * 4, toQ);
            }
            double[] median = new double[HOURS.length];
            for (int h = 0; h < HOURS.length; h++) {
                final int hh = h;
                median[h] = median(Arrays.stream(fwd).mapToDouble(r -> r[hh]).filter(x -> !Double.isNaN(x)).toArray());
            }
            for (int i = 0; i < universe.size(); i++) {
                String sym = universe.get(i);
                EarlySignal s = tape.at(sym, q);
                if (s == null) continue;
                var t = data.ticker(sym, q);
                if (t == null) continue;
                double cost = 2 * (FEE + Costs.impact(t.quoteVolume(), 1000)) * 100;
                List<Integer> groups = new ArrayList<>();
                groups.add(4);
                if (s.early()) {
                    if (s.score() >= minScore) {
                        groups.add(0);
                        Integer prev = lastEarly.get(sym);
                        if (prev == null || q - prev > MarketData.DAY) groups.add(1);
                    } else if (s.score() >= 6) {
                        groups.add(2);
                    } else {
                        groups.add(3);
                    }
                    lastEarly.put(sym, q);
                }
                for (int h = 0; h < HOURS.length; h++) {
                    if (Double.isNaN(fwd[i][h])) continue;
                    double net = fwd[i][h] - cost;
                    double excess = fwd[i][h] - median[h];
                    for (int g : groups) samples[g][h].add(new double[]{net, excess, day, regime});
                }
            }
        }
    }

    /** q 收盘买入、n 根 15m 后收盘卖出的毛收益%；之后没数据（下架）按最后一根收盘；未到期返回 NaN。 */
    private double forward(String sym, int q, int n, int toQ) {
        Series s = data.get(sym);
        if (s == null || !s.has(q) || q + n >= toQ) return Double.NaN;
        double exit = s.lastClose(q + n, n);
        if (Double.isNaN(exit)) return Double.NaN;
        return (exit / s.close[q] - 1) * 100;
    }

    static double median(double[] v) {
        if (v.length == 0) return Double.NaN;
        double[] c = v.clone();
        Arrays.sort(c);
        return c.length % 2 == 1 ? c[c.length / 2] : (c[c.length / 2 - 1] + c[c.length / 2]) / 2;
    }

    /** 按天汇总：每天平均后，返回 {天数, 均值, 95% 下限, 95% 上限}。 */
    static double[] dailyCi(List<double[]> rows, int col) {
        Map<Integer, double[]> byDay = new LinkedHashMap<>();
        for (double[] r : rows) {
            double[] a = byDay.computeIfAbsent((int) r[2], k -> new double[2]);
            a[0] += r[col];
            a[1]++;
        }
        double[] means = byDay.values().stream().mapToDouble(a -> a[0] / a[1]).toArray();
        int n = means.length;
        if (n < 2) return new double[]{n, Double.NaN, Double.NaN, Double.NaN};
        double m = Arrays.stream(means).average().orElse(Double.NaN);
        double var = Arrays.stream(means).map(x -> (x - m) * (x - m)).sum() / (n - 1);
        double se = Math.sqrt(var / n);
        return new double[]{n, m, m - 1.96 * se, m + 1.96 * se};
    }

    public String markdown() {
        StringBuilder b = new StringBuilder();
        b.append("## 信号本身有没有优势\n\n");
        b.append("每个整点（线上 :17 那一刻）对标的池里的币按信号分组，看之后 1h / 4h / 24h / 72h 的收益。")
                .append("净收益已扣来回手续费 0.2% 和 1,000U 冲击；超额 = 毛收益 − 同期标的池中位数（不扣费，看选币能力）。")
                .append("区间按天汇总：每天先平均，再对天数算 95% 区间；区间全在 0 以上才算有优势。\n\n");
        for (int h = 0; h < HOURS.length; h++) {
            b.append("### ").append(HOURS[h]).append(" 小时后\n\n");
            b.append("| 分组 | 样本 | 天数 | 平均净收益% | 95% 区间 | 中位数净收益% | 净赚比例 | 平均超额% | 超额 95% 区间 |\n|---|---|---|---|---|---|---|---|---|\n");
            for (int g = 0; g < GROUPS.length; g++) {
                List<double[]> rows = samples[g][h];
                if (rows.isEmpty()) {
                    b.append("| ").append(GROUPS[g]).append(" | 0 |  |  |  |  |  |  |  |\n");
                    continue;
                }
                double[] net = dailyCi(rows, 0);
                double[] ex = dailyCi(rows, 1);
                double[] nets = rows.stream().mapToDouble(r -> r[0]).toArray();
                double win = Arrays.stream(nets).filter(x -> x > 0).count() * 100.0 / nets.length;
                b.append(String.format(Locale.ROOT, "| %s | %d | %.0f | %.2f | %.2f ~ %.2f | %.2f | %.0f%% | %.2f | %.2f ~ %.2f |%n",
                        GROUPS[g], rows.size(), net[0], net[1], net[2], net[3], median(nets), win, ex[1], ex[2], ex[3]));
            }
            b.append('\n');
        }
        b.append("### 按行情：24 小时后平均净收益%（样本数）\n\n| 分组 |");
        for (int r = 1; r < Regimes.NAMES.length; r++) b.append(' ').append(Regimes.NAMES[r]).append(" |");
        b.append("\n|---|");
        for (int r = 1; r < Regimes.NAMES.length; r++) b.append("---|");
        b.append('\n');
        int h24 = 2;
        for (int g = 0; g < GROUPS.length; g++) {
            b.append("| ").append(GROUPS[g]).append(" |");
            for (int r = 1; r < Regimes.NAMES.length; r++) {
                final int rr = r;
                double[] v = samples[g][h24].stream().filter(x -> x[3] == rr).mapToDouble(x -> x[0]).toArray();
                b.append(v.length == 0 ? " - |" : String.format(Locale.ROOT, " %.2f（%d）|", Arrays.stream(v).average().orElse(0), v.length));
            }
            b.append('\n');
        }
        b.append("\n超额按行情（24 小时后，选中的币比同期中位数多涨%）：\n\n| 分组 |");
        for (int r = 1; r < Regimes.NAMES.length; r++) b.append(' ').append(Regimes.NAMES[r]).append(" |");
        b.append("\n|---|");
        for (int r = 1; r < Regimes.NAMES.length; r++) b.append("---|");
        b.append('\n');
        for (int g = 0; g < GROUPS.length - 1; g++) {
            b.append("| ").append(GROUPS[g]).append(" |");
            for (int r = 1; r < Regimes.NAMES.length; r++) {
                final int rr = r;
                double[] v = samples[g][h24].stream().filter(x -> x[3] == rr).mapToDouble(x -> x[1]).toArray();
                b.append(v.length == 0 ? " - |" : String.format(Locale.ROOT, " %.2f |", Arrays.stream(v).average().orElse(0)));
            }
            b.append('\n');
        }
        return b.append('\n').toString();
    }

    /** 测试用：某组某周期的样本数。 */
    int count(int group, int hourIndex) {
        return samples[group][hourIndex].size();
    }
}
