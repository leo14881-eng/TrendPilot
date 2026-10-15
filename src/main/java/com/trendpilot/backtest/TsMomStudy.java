package com.trendpilot.backtest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 候选 A：山寨单币时间序列动量（只做多）。每个币看自己：过去 L 天涨了（或收盘在 N 日均线上方）就持有 H 天，否则这一份拿现金。
 * 和横向排名不同：不和别的币比，只看自己的趋势。
 * 山寨池 = 每天按 30 天平均成交额取前 150（含后来下架的币）。每 H 天统一调仓；信号用前一天收盘，当天收盘成交（晚一天）。
 * 两种仓位：「每币固定 1/150」（不涨的那份拿现金）和「涨了的币等权满仓」。
 * 对照：山寨池等权一直持有、按同样仓位比例持有山寨池（敞口匹配，判断是不是只靠少持仓）、BTC 一直持有。
 * 先在校准期（2018-07 ～ 2021-12）看哪组参数最好，再在验证期（2022 ～ 2026-10）只看一次。
 */
public final class TsMomStudy {

    record Rule(String name, int lookback, int maDays, int hold, boolean fullInvest) {
    }

    record Run(Rule rule, double[] ret, double[] exposure, double[] matched, double[] pool) {
    }

    static List<Rule> rules() {
        List<Rule> r = new ArrayList<>();
        for (boolean full : new boolean[]{false, true}) {
            for (int lb : new int[]{14, 28}) {
                for (int h : new int[]{5, 7}) r.add(new Rule("过去 " + lb + " 天涨了 → 持有 " + h + " 天" + (full ? "（等权满仓）" : "（每币 1/150）"), lb, 0, h, full));
            }
            for (int ma : new int[]{20, 50}) r.add(new Rule("收盘在 " + ma + " 日均线上方 → 持有 7 天" + (full ? "（等权满仓）" : "（每币 1/150）"), 0, ma, 7, full));
        }
        return r;
    }

    private final SeasonStudy st;
    private final List<Run> runs = new ArrayList<>();
    private final List<String> log = new ArrayList<>();
    private double[] btcRet;

    TsMomStudy(SeasonStudy st) {
        this.st = st;
    }

    void run(int fromDay) {
        btcRet = new double[st.days];
        for (int d = fromDay + 1; d < st.days; d++) btcRet[d] = st.btcClose[d] / st.btcClose[d - 1] - 1;
        for (Rule r : rules()) runs.add(simulate(r, fromDay));
    }

    boolean up(Rule r, int i, int d) {
        if (!st.has(i, d)) return false;
        if (r.maDays() > 0) {
            double m = st.sma(i, d, r.maDays());
            return !Double.isNaN(m) && st.close[i][d] > m;
        }
        double x = st.ret(i, d, r.lookback());
        return !Double.isNaN(x) && x > 0;
    }

    /** 每天的组合收益（扣成本）、仓位比例、敞口匹配的山寨池收益、山寨池等权收益。 */
    Run simulate(Rule r, int fromDay) {
        int n = st.days;
        double[] ret = new double[n], expo = new double[n], matched = new double[n], pool = new double[n];
        Map<Integer, Double> w = new HashMap<>();
        Map<Integer, Double> pw = new HashMap<>(); // 山寨池等权（同样每 H 天调仓）
        int since = 1_000_000;
        boolean logIt = r.equals(rules().get(1));
        for (int d = fromDay + 1; d < n; d++) {
            double[] g = grow(w, d);
            double[] pg = grow(pw, d);
            ret[d] = g[0];
            pool[d] = pg[0];
            double e = w.values().stream().mapToDouble(Double::doubleValue).sum();
            since++;
            if (since >= r.hold()) {
                since = 0;
                int[] u = st.top(d - 1, SeasonStudy.TOP);
                Map<Integer, Double> t = new HashMap<>();
                List<Integer> ups = new ArrayList<>();
                for (int i : u) if (up(r, i, d - 1) && st.has(i, d)) ups.add(i);
                double each = r.fullInvest() ? (ups.isEmpty() ? 0 : 1.0 / ups.size()) : 1.0 / SeasonStudy.TOP;
                for (int i : ups) t.put(i, each);
                ret[d] -= cost(w, t, d);
                w = t;
                Map<Integer, Double> pt = new HashMap<>();
                int k = 0;
                for (int i : u) if (st.has(i, d)) k++;
                for (int i : u) if (st.has(i, d)) pt.put(i, 1.0 / k);
                pool[d] -= cost(pw, pt, d);
                pw = pt;
                if (logIt) {
                    StringBuilder l = new StringBuilder(st.date(d)).append(',').append(ups.size()).append(',').append(u.length).append(',');
                    for (int i : ups) l.append(st.syms.get(i)).append(' ');
                    log.add(l.toString().trim());
                }
                e = w.values().stream().mapToDouble(Double::doubleValue).sum();
            }
            // 敞口匹配：昨天收盘的仓位比例 × 山寨池今天的收益（含山寨池自己的调仓成本）
            matched[d] = expo[d - 1] * pool[d];
            expo[d] = e;
        }
        return new Run(r, ret, expo, matched, pool);
    }

    /** 当天收益，并把权重按涨跌更新（下架或缺数据的币按最后收盘转现金）。返回 {收益}。 */
    private double[] grow(Map<Integer, Double> w, int d) {
        double port = 0;
        Map<Integer, Double> next = new HashMap<>();
        for (var e : w.entrySet()) {
            double rr = st.ret(e.getKey(), d, 1);
            if (Double.isNaN(rr)) rr = 0;
            port += e.getValue() * rr;
            if (st.has(e.getKey(), d)) next.put(e.getKey(), e.getValue() * (1 + rr));
        }
        w.clear();
        for (var e : next.entrySet()) w.put(e.getKey(), e.getValue() / (1 + port));
        return new double[]{port};
    }

    private double cost(Map<Integer, Double> from, Map<Integer, Double> to, int d) {
        Set<Integer> keys = new HashSet<>(from.keySet());
        keys.addAll(to.keySet());
        double c = 0;
        for (int i : keys) {
            double dw = Math.abs(to.getOrDefault(i, 0.0) - from.getOrDefault(i, 0.0));
            if (dw == 0) continue;
            double vol = st.has(i, d) ? st.qv[i][d] : 0;
            c += dw * (SeasonStudy.FEE + Costs.impact(vol, 1000));
        }
        return c;
    }

    /** [from, to] 段：{收益%, 最大回撤%, 年化夏普, 平均仓位%}。 */
    static double[] stats(double[] r, double[] expo, int from, int to) {
        double nav = 1, peak = 1, dd = 0, s = 0, s2 = 0, e = 0;
        int n = 0;
        for (int d = from; d <= to; d++) {
            nav *= 1 + r[d];
            peak = Math.max(peak, nav);
            dd = Math.max(dd, 1 - nav / peak);
            s += r[d];
            s2 += r[d] * r[d];
            if (expo != null) e += expo[d];
            n++;
        }
        double m = s / n, sd = Math.sqrt(Math.max(0, s2 / n - m * m));
        return new double[]{(nav - 1) * 100, dd * 100, sd == 0 ? 0 : m / sd * Math.sqrt(365), expo == null ? 100 : e / n * 100};
    }

    /** 策略减对照的每周收益差（%）：均值和 95% 区间（周与周之间近似独立）。 */
    static double[] weeklyDiff(double[] a, double[] b, int from, int to) {
        List<Double> wk = new ArrayList<>();
        double acc = 0;
        int k = 0;
        for (int d = from; d <= to; d++) {
            acc += a[d] - b[d];
            if (++k == 7) {
                wk.add(acc * 100);
                acc = 0;
                k = 0;
            }
        }
        double[] v = wk.stream().mapToDouble(Double::doubleValue).toArray();
        if (v.length < 3) return new double[]{Double.NaN, Double.NaN, Double.NaN};
        double m = Arrays.stream(v).average().orElse(0);
        double se = Math.sqrt(Arrays.stream(v).map(x -> (x - m) * (x - m)).sum() / (v.length - 1) / v.length);
        return new double[]{m, m - 1.96 * se, m + 1.96 * se};
    }

    public String markdown(int fromDay) {
        int split = st.dayOf(LocalDate.of(2022, 1, 1)), end = st.days - 1;
        int[][] segs = {{fromDay + 1, split - 1}, {split, end}};
        String[] segName = {"校准期 " + st.date(fromDay + 1) + " ～ 2021-12-31", "验证期 2022-01-01 ～ " + st.date(end)};
        StringBuilder b = new StringBuilder("## 候选 A：山寨单币趋势持有（日线，前 150 个山寨，每 H 天调仓，晚一天成交，扣手续费 0.1% + 冲击）\n\n");
        b.append("「敞口匹配」= 每天按策略同样的仓位比例持有山寨池等权，用来区分「会挑时机」和「只是少持仓」。")
                .append("「每周超额」= 策略每周收益减敞口匹配的每周收益，区间按周算；区间全在 0 以上才算比「少持仓」更好。\n\n");
        b.append("**参数没有在验证期上调过。下面先看校准期选出的最好一组，再看它在验证期的表现。**\n\n");
        for (int k = 0; k < 2; k++) {
            int a = segs[k][0], z = segs[k][1];
            b.append("### ").append(segName[k]).append("\n\n| 方案 | 收益% | 最大回撤% | 夏普 | 平均仓位% | 敞口匹配收益% | 每周超额%（95% 区间） |\n|---|---|---|---|---|---|---|\n");
            double[] p = stats(runs.get(0).pool(), null, a, z);
            double[] bt = stats(btcRet, null, a, z);
            b.append(String.format(Locale.ROOT, "| 山寨池等权一直持有（对照） | %.0f | %.1f | %.2f | 100 | - | - |%n", p[0], p[1], p[2]));
            b.append(String.format(Locale.ROOT, "| BTC 一直持有（对照） | %.0f | %.1f | %.2f | 100 | - | - |%n", bt[0], bt[1], bt[2]));
            for (Run r : runs) {
                double[] s = stats(r.ret(), r.exposure(), a, z);
                double[] m = stats(r.matched(), null, a, z);
                double[] wd = weeklyDiff(r.ret(), r.matched(), a, z);
                b.append(String.format(Locale.ROOT, "| %s | %.0f | %.1f | %.2f | %.0f | %.0f | %.3f（%.3f ~ %.3f） |%n", r.rule().name(), s[0], s[1], s[2], s[3], m[0], wd[0], wd[1], wd[2]));
            }
            b.append('\n');
        }
        // 校准期夏普最高的一组，验证期结果
        Run best = runs.get(0);
        double bestS = -1e9;
        for (Run r : runs) {
            double sh = stats(r.ret(), r.exposure(), segs[0][0], segs[0][1])[2];
            if (sh > bestS) {
                bestS = sh;
                best = r;
            }
        }
        double[] v = stats(best.ret(), best.exposure(), segs[1][0], segs[1][1]);
        double[] wd = weeklyDiff(best.ret(), best.matched(), segs[1][0], segs[1][1]);
        b.append(String.format(Locale.ROOT, "**校准期夏普最高的是「%s」（%.2f）。它在验证期：收益 %.0f%%，最大回撤 %.1f%%，夏普 %.2f，比敞口匹配每周超额 %.3f%%（%.3f ~ %.3f）。**%n%n",
                best.rule().name(), bestS, v[0], v[1], v[2], wd[0], wd[1], wd[2]));
        // 按年
        b.append("### 按年份收益%\n\n| 方案 |");
        int y0 = Integer.parseInt(st.date(fromDay + 1).substring(0, 4)), y1 = Integer.parseInt(st.date(end).substring(0, 4));
        for (int y = y0; y <= y1; y++) b.append(' ').append(y).append(" |");
        b.append("\n|---|");
        for (int y = y0; y <= y1; y++) b.append("---|");
        b.append('\n');
        yearRow(b, "山寨池等权（对照）", runs.get(0).pool(), y0, y1, fromDay);
        yearRow(b, "BTC（对照）", btcRet, y0, y1, fromDay);
        for (Run r : runs) yearRow(b, r.rule().name(), r.ret(), y0, y1, fromDay);
        return b.append('\n').toString();
    }

    private void yearRow(StringBuilder b, String name, double[] r, int y0, int y1, int fromDay) {
        b.append("| ").append(name).append(" |");
        for (int y = y0; y <= y1; y++) {
            int a = Math.max(fromDay + 1, st.dayOf(LocalDate.of(y, 1, 1))), z = Math.min(st.days - 1, st.dayOf(LocalDate.of(y, 12, 31)));
            b.append(String.format(Locale.ROOT, " %.0f |", stats(r, null, a, z)[0]));
        }
        b.append('\n');
    }

    public void writeCsv(Path dir) throws IOException {
        StringBuilder h = new StringBuilder("# 列编号: ");
        for (int k = 0; k < runs.size(); k++) h.append(k).append('=').append(runs.get(k).rule().name()).append("; ");
        h.append("\ndate,btc_ret,pool_ret");
        for (int k = 0; k < runs.size(); k++) h.append(",ret_").append(k).append(",expo_").append(k).append(",matched_").append(k);
        List<String> lines = new ArrayList<>();
        for (int d = 0; d < st.days; d++) {
            StringBuilder l = new StringBuilder(st.date(d)).append(String.format(Locale.ROOT, ",%.6f,%.6f", btcRet[d], runs.get(0).pool()[d]));
            for (Run r : runs) l.append(String.format(Locale.ROOT, ",%.6f,%.4f,%.6f", r.ret()[d], r.exposure()[d], r.matched()[d]));
            lines.add(l.toString());
        }
        Files.writeString(dir.resolve("tsmom-daily.csv"), h + "\n" + String.join("\n", lines) + "\n");
        Files.writeString(dir.resolve("tsmom-holdings.csv"), "# " + rules().get(1).name() + " 每次调仓：日期、持有币数、池子币数、持有的币\ndate,held,pool,symbols\n" + String.join("\n", log) + "\n");
    }

    List<Run> runs() {
        return runs;
    }
}
