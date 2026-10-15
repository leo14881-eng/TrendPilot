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

    static final int[] HOURS = {1, 4, 24, 72, 168};
    static final double FEE = 0.001;

    /**
     * 分组：现行 EARLY 按评分分档，另加几种候选买入信号（7.7 第 3 条：没有优势就改买入），最后一组是标的池全部。
     * 排名都在同一时刻的标的池里算，只用当时已收盘的数据。
     */
    static final String[] GROUPS = {"EARLY 评分≥8", "其中 24h 内首次", "EARLY 评分≥8 且山寨大盘向好", "EARLY 评分 6~8", "EARLY 评分<6",
            "候选：7 天强势中回调（7d 前 20% 且 24h 跌 ≥3%）", "候选：7 天强势（前 10%）", "候选：24h 涨幅前 10%",
            "候选：24h 超跌（跌幅前 10%）",
            "候选：暴跌/暴跌后反弹时 24h 超跌（跌幅前 10%）", "候选：暴跌/暴跌后反弹时买标的池任意币", "候选：山寨 7 天上涨时 7 天强势（前 10%）",
            "候选：72h 超跌（跌幅前 10%）", "候选：ETH",
            "回调 P2：7d 前 20% 且 24h 跌 ≥6%", "回调 P3：7d 前 30% 且 24h 跌 ≥3%", "回调 P4：P1 且山寨 24h 中位数 > −2%",
            "回调 P5：30d 前 20% 且 24h 跌 ≥3%", "回调 P6：7d 前 20% 且 72h 跌 ≥5%", "回调 P1 首次（同币 72h 内只算一次）",
            "标的池全部（基准）"};
    static final int ALL = GROUPS.length - 1;

    private final MarketData data;
    private final Regimes regimes;
    private final SignalTape tape;
    private final double minScore;

    /** [组][周期] → 每个样本：净收益%、超额%（相对同一时刻同成交额档的平均，相当于随机挑同档的币）、所在天、行情。 */
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
        Map<String, Integer> lastPullback = new LinkedHashMap<>();
        for (int q = fromQ + Math.floorMod(-fromQ, 4); q < toQ; q += 4) {
            List<String> universe = data.universe(q);
            int n = universe.size();
            int regime = regimes.at(q);
            int day = q / MarketData.DAY;
            int year = java.time.Instant.ofEpochMilli(data.time(q)).atZone(java.time.ZoneOffset.UTC).getYear();
            MarketData.Context ctx = data.context(q + 1);
            boolean altGood = ctx != null && ctx.alt24h() > 0 && ctx.alt4h() > 0;
            double[][] fwd = new double[n][HOURS.length];
            double[] r24 = new double[n], r72 = new double[n], r7d = new double[n], r30 = new double[n], vol = new double[n];
            boolean crash = regime == 5 || regime == 6;
            boolean altUp7d = ctx != null && ctx.alt7d() > 0;
            for (int i = 0; i < n; i++) {
                String sym = universe.get(i);
                for (int h = 0; h < HOURS.length; h++) fwd[i][h] = forward(sym, q, HOURS[h] * 4, toQ);
                r24[i] = back(sym, q, MarketData.DAY);
                r72[i] = back(sym, q, 3 * MarketData.DAY);
                r30[i] = back(sym, q, 30 * MarketData.DAY);
                r7d[i] = back(sym, q, 7 * MarketData.DAY);
                var t = data.ticker(sym, q);
                vol[i] = t == null ? Double.NaN : t.quoteVolume();
            }
            // 同成交额档（冲击分档）的平均远期收益：随机挑一个同档币的期望
            double[][] tierSum = new double[4][HOURS.length];
            int[][] tierN = new int[4][HOURS.length];
            for (int i = 0; i < n; i++) {
                if (Double.isNaN(vol[i])) continue;
                int tier = tier(vol[i]);
                for (int h = 0; h < HOURS.length; h++) {
                    if (Double.isNaN(fwd[i][h])) continue;
                    tierSum[tier][h] += fwd[i][h];
                    tierN[tier][h]++;
                }
            }
            double r7Top20 = quantile(r7d, 0.8), r7Top10 = quantile(r7d, 0.9), r7Top30 = quantile(r7d, 0.7), r30Top20 = quantile(r30, 0.8);
            boolean altCalm = ctx != null && ctx.alt24h() > -2;
            double r24Top10 = quantile(r24, 0.9), r24Bottom10 = quantile(r24, 0.1), r72Bottom10 = quantile(r72, 0.1);
            for (int i = 0; i < n; i++) {
                String sym = universe.get(i);
                if (Double.isNaN(vol[i])) continue;
                EarlySignal s = tape.at(sym, q);
                double cost = 2 * (FEE + Costs.impact(vol[i], 1000)) * 100;
                List<Integer> groups = new ArrayList<>();
                groups.add(ALL);
                if (s != null && s.early()) {
                    if (s.score() >= minScore) {
                        groups.add(0);
                        Integer prev = lastEarly.get(sym);
                        if (prev == null || q - prev > MarketData.DAY) groups.add(1);
                        if (altGood) groups.add(2);
                    } else if (s.score() >= 6) {
                        groups.add(3);
                    } else {
                        groups.add(4);
                    }
                    lastEarly.put(sym, q);
                }
                if (!Double.isNaN(r7d[i]) && r7d[i] >= r7Top20 && r24[i] <= -3) groups.add(5);
                if (!Double.isNaN(r7d[i]) && r7d[i] >= r7Top10) groups.add(6);
                if (!Double.isNaN(r24[i]) && r24[i] >= r24Top10) groups.add(7);
                if (!Double.isNaN(r24[i]) && r24[i] <= r24Bottom10) groups.add(8);
                if (crash && !Double.isNaN(r24[i]) && r24[i] <= r24Bottom10) groups.add(9);
                if (crash) groups.add(10);
                if (altUp7d && !Double.isNaN(r7d[i]) && r7d[i] >= r7Top10) groups.add(11);
                if (!Double.isNaN(r72[i]) && r72[i] <= r72Bottom10) groups.add(12);
                if (sym.equals("ETHUSDT")) groups.add(13);
                boolean strong7 = !Double.isNaN(r7d[i]) && r7d[i] >= r7Top20;
                boolean p1 = strong7 && r24[i] <= -3;
                if (strong7 && r24[i] <= -6) groups.add(14);
                if (!Double.isNaN(r7d[i]) && r7d[i] >= r7Top30 && r24[i] <= -3) groups.add(15);
                if (p1 && altCalm) groups.add(16);
                if (!Double.isNaN(r30[i]) && r30[i] >= r30Top20 && r24[i] <= -3) groups.add(17);
                if (strong7 && r72[i] <= -5) groups.add(18);
                if (p1) {
                    Integer prev = lastPullback.get(sym);
                    if (prev == null || q - prev >= 3 * MarketData.DAY) {
                        groups.add(19);
                        lastPullback.put(sym, q);
                    }
                }
                int tier = tier(vol[i]);
                for (int h = 0; h < HOURS.length; h++) {
                    if (Double.isNaN(fwd[i][h]) || tierN[tier][h] == 0) continue;
                    double net = fwd[i][h] - cost;
                    double excess = fwd[i][h] - tierSum[tier][h] / tierN[tier][h];
                    for (int g : groups) samples[g][h].add(new double[]{net, excess, day, regime, year});
                }
            }
        }
    }

    static int tier(double vol) {
        return vol >= 50_000_000 ? 0 : vol >= 10_000_000 ? 1 : vol >= 2_000_000 ? 2 : 3;
    }

    /** 过去 n 根 15m 的涨跌%；数据不够返回 NaN。 */
    private double back(String sym, int q, int n) {
        Series s = data.get(sym);
        if (s == null || !s.has(q) || q - n < 0) return Double.NaN;
        double p = s.lastClose(q - n, 8);
        return Double.isNaN(p) ? Double.NaN : (s.close[q] / p - 1) * 100;
    }

    static double quantile(double[] v, double p) {
        double[] c = Arrays.stream(v).filter(x -> !Double.isNaN(x)).sorted().toArray();
        if (c.length == 0) return Double.NaN;
        return c[Math.min(c.length - 1, (int) Math.floor(p * c.length))];
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
                .append("净收益已扣来回手续费 0.2% 和 1,000U 冲击；超额 = 毛收益 − 同一时刻同成交额档所有币的平均（相当于随机挑同档的币，不扣费，看选币能力）。")
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
        int[] years = samples[ALL][0].stream().mapToInt(x -> (int) x[4]).distinct().sorted().toArray();
        if (years.length > 1) {
            for (int hi : new int[]{2, 3}) {
                b.append("### 按年份：").append(HOURS[hi]).append(" 小时后 平均净收益% / 超额%\n\n| 分组 |");
                for (int y : years) b.append(' ').append(y).append(" |");
                b.append("\n|---|");
                for (int y : years) b.append("---|");
                b.append('\n');
                for (int g = 0; g < GROUPS.length; g++) {
                    b.append("| ").append(GROUPS[g]).append(" |");
                    for (int y : years) {
                        final int yy = y;
                        List<double[]> in = samples[g][hi].stream().filter(x -> x[4] == yy).toList();
                        if (in.isEmpty()) {
                            b.append(" - |");
                            continue;
                        }
                        double[] net = dailyCi(in, 0), ex = dailyCi(in, 1);
                        b.append(String.format(Locale.ROOT, " %.2f / %.2f（%.2f ~ %.2f）|", net[1], ex[1], ex[2], ex[3]));
                    }
                    b.append('\n');
                }
                b.append('\n');
            }
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
        b.append("\n超额按行情（24 小时后，比随机挑同档的币多涨%）：\n\n| 分组 |");
        for (int r = 1; r < Regimes.NAMES.length; r++) b.append(' ').append(Regimes.NAMES[r]).append(" |");
        b.append("\n|---|");
        for (int r = 1; r < Regimes.NAMES.length; r++) b.append("---|");
        b.append('\n');
        for (int g = 0; g < ALL; g++) {
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
