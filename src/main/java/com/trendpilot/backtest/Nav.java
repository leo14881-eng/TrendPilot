package com.trendpilot.backtest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 净值曲线对照（设计文档 7.6 第 3 项、7.4 按净值验收）。资金按硬上限 C 计算，净值 = C + 权益（已实现 + 按可退出价格的浮动）。
 * 对照：现金（0）、持有 BTC、标的池等权（每小时再平衡）、按方案同一时刻的资金占用比例持有等权池（"同仓位持币"）。
 */
public final class Nav {

    public record Row(String name, double returnPct, double maxDdPct, int longestDdDays, double sharpe, double calmar,
                      double usagePct, double inMarketPct, double diffMean, double diffLow, double diffHigh) {
    }

    private final MarketData data;
    private final double capital;
    /** 每小时：等权池收益、BTC 收益（比例）。 */
    private final double[] poolRet;
    private final double[] btcRet;

    public Nav(MarketData data, double capital) {
        this.data = data;
        this.capital = capital;
        int hours = data.size / 4;
        poolRet = new double[hours + 1];
        btcRet = new double[hours + 1];
        Series btc = data.get(MarketData.BTC);
        for (int h = 1; h <= hours; h++) {
            int q = h * 4 - 1, p = q - 4;
            if (p < 0) continue;
            btcRet[h] = ret(btc, p, q);
            double sum = 0;
            int n = 0;
            for (String sym : data.universe(p)) {
                double r = ret(data.get(sym), p, q);
                if (!Double.isNaN(r)) {
                    sum += r;
                    n++;
                }
            }
            poolRet[h] = n == 0 ? 0 : sum / n;
            if (Double.isNaN(btcRet[h])) btcRet[h] = 0;
        }
    }

    private static double ret(Series s, int p, int q) {
        if (s == null) return Double.NaN;
        double a = s.lastClose(p, 8), b = s.lastClose(q, 8);
        return Double.isNaN(a) || Double.isNaN(b) || a <= 0 ? Double.NaN : b / a - 1;
    }

    /** 方案净值（下标为小时，第 a 小时 = 1）。 */
    public double[] curve(Sim sim, int a, int b) {
        double[] eq = sim.equity();
        double[] nav = new double[b - a + 1];
        for (int h = a; h <= b; h++) nav[h - a] = (capital + eq[h] - eq[a]) / capital;
        return nav;
    }

    public double[] hold(double[] hourly, int a, int b) {
        double[] nav = new double[b - a + 1];
        nav[0] = 1;
        for (int h = a + 1; h <= b; h++) nav[h - a] = nav[h - a - 1] * (1 + hourly[h]);
        return nav;
    }

    /** 同仓位持币：每小时按方案上一小时的资金占用比例持有等权池，其余现金。 */
    public double[] matched(Sim sim, int a, int b) {
        double[] dep = sim.deployed();
        double[] nav = new double[b - a + 1];
        nav[0] = 1;
        for (int h = a + 1; h <= b; h++) {
            double w = Math.min(1, dep[h - 1] / capital);
            nav[h - a] = nav[h - a - 1] * (1 + w * poolRet[h]);
        }
        return nav;
    }

    public Row row(String name, double[] nav, double[] dep, int a, double[] against) {
        double ret = (nav[nav.length - 1] - 1) * 100;
        double peak = nav[0], dd = 0;
        int peakAt = 0, longest = 0;
        for (int i = 0; i < nav.length; i++) {
            if (nav[i] >= peak) {
                peak = nav[i];
                peakAt = i;
            }
            dd = Math.max(dd, (peak - nav[i]) / peak);
            longest = Math.max(longest, i - peakAt);
        }
        double[] daily = daily(nav);
        double m = Arrays.stream(daily).average().orElse(0);
        double sd = Math.sqrt(Arrays.stream(daily).map(x -> (x - m) * (x - m)).sum() / Math.max(1, daily.length - 1));
        double sharpe = sd == 0 ? 0 : m / sd * Math.sqrt(365);
        double years = (nav.length - 1) / 24.0 / 365;
        double annual = Math.pow(Math.max(nav[nav.length - 1], 1e-9), 1 / Math.max(years, 1e-9)) - 1;
        double calmar = dd == 0 ? Double.NaN : annual / dd;
        double usage = Double.NaN, inMarket = Double.NaN;
        if (dep != null) {
            double s = 0;
            int in = 0;
            for (int i = 1; i < nav.length; i++) {
                s += dep[a + i] / capital;
                if (dep[a + i] > 0) in++;
            }
            usage = s / (nav.length - 1) * 100;
            inMarket = in * 100.0 / (nav.length - 1);
        }
        double[] ci = {Double.NaN, Double.NaN, Double.NaN};
        if (against != null) {
            double[] o = daily(against);
            double[] diff = new double[Math.min(daily.length, o.length)];
            for (int i = 0; i < diff.length; i++) diff[i] = (daily[i] - o[i]) * 100;
            ci = tci(diff);
        }
        return new Row(name, ret, dd * 100, longest / 24, sharpe, calmar, usage, inMarket, ci[0], ci[1], ci[2]);
    }

    static double[] daily(double[] nav) {
        List<Double> out = new ArrayList<>();
        for (int i = 24; i < nav.length; i += 24) out.add(nav[i] / nav[i - 24] - 1);
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** 均值和 95% 区间。 */
    static double[] tci(double[] v) {
        if (v.length < 2) return new double[]{Double.NaN, Double.NaN, Double.NaN};
        double m = Arrays.stream(v).average().orElse(0);
        double se = Math.sqrt(Arrays.stream(v).map(x -> (x - m) * (x - m)).sum() / (v.length - 1) / v.length);
        return new double[]{m, m - 1.96 * se, m + 1.96 * se};
    }

    /** 每种行情里方案净值的小时收益合计是否高于同仓位持币；返回 [行情] = 差值%（NaN = 该段没有这种行情）。 */
    public double[] regimeEdge(double[] curve, double[] matched, int a, Regimes regimes) {
        double[] diff = new double[7];
        boolean[] seen = new boolean[7];
        for (int i = 1; i < curve.length; i++) {
            int r = regimes.at((a + i - 1) * 4);
            if (r <= 0) continue;
            seen[r] = true;
            diff[r] += (curve[i] / curve[i - 1] - 1 - (matched[i] / matched[i - 1] - 1)) * 100;
        }
        for (int r = 0; r < 7; r++) if (!seen[r]) diff[r] = Double.NaN;
        return diff;
    }

    public double capital() {
        return capital;
    }

    public double[] poolHourly() {
        return poolRet;
    }

    public double[] btcHourly() {
        return btcRet;
    }

    public static String table(List<Row> rows) {
        StringBuilder b = new StringBuilder("| 方案 | 收益% | 最大回撤% | 最长回撤天数 | 夏普 | 卡玛 | 平均资金占用% | 持仓时间% | 每日比同仓位持币多% | 95% 区间 |\n"
                + "|---|---|---|---|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            b.append(String.format(Locale.ROOT, "| %s | %.1f | %.1f | %d | %.2f | %s | %s | %s | %s | %s |%n", r.name(), r.returnPct(), r.maxDdPct(),
                    r.longestDdDays(), r.sharpe(), f(r.calmar()), f(r.usagePct()), f(r.inMarketPct()), f3(r.diffMean()),
                    Double.isNaN(r.diffLow()) ? "" : String.format(Locale.ROOT, "%.3f ~ %.3f", r.diffLow(), r.diffHigh())));
        }
        return b.toString();
    }

    private static String f(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String f3(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.3f", v);
    }
}
