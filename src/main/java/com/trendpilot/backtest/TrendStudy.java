package com.trendpilot.backtest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 大币趋势持有：每天 UTC 0 点看一次，收盘价在 N 日均线上方就持有，否则拿现金。换仓按手续费 0.1% + 冲击 5 bps 计。
 * 和一直持有比较收益、最大回撤、换仓次数、持仓时间，并按年份分开；前段选均线长度，后段只验证一次。
 */
public final class TrendStudy {

    static final int[] MA_DAYS = {20, 50, 100, 200};
    static final double SWITCH_COST = 0.0015;

    public record Result(String name, double returnPct, double maxDdPct, double cagrPct, int switches, double inMarketPct,
                         Map<Integer, Double> yearly, double calibReturn, double calibDd, double validReturn, double validDd) {
    }

    private final MarketData data;
    private final List<Result> results = new ArrayList<>();
    /** BTC 各方案逐日净值（核对用）：方案名 → 每天 {q, 净值, 是否持有}。 */
    private final Map<String, List<double[]>> btcDays = new LinkedHashMap<>();

    public TrendStudy(MarketData data) {
        this.data = data;
    }

    public void run(int fromQ, int splitQ, int toQ) {
        for (String[] asset : new String[][]{{"BTC", MarketData.BTC}, {"ETH", MarketData.ETH}, {"BTC+ETH 各半", MarketData.BTC, MarketData.ETH}}) {
            results.add(simulate(asset, 0, fromQ, splitQ, toQ));
            for (int ma : MA_DAYS) results.add(simulate(asset, ma, fromQ, splitQ, toQ));
        }
    }

    /** ma = 0 表示一直持有。多个币时各占一半资金，各自按自己的均线判断。 */
    private Result simulate(String[] asset, int ma, int fromQ, int splitQ, int toQ) {
        int legs = asset.length - 1;
        String name = asset[0] + (ma == 0 ? " 一直持有" : " " + ma + " 日均线上方持有");
        int start = fromQ - Math.floorMod(fromQ, MarketData.DAY);
        List<double[]> days = new ArrayList<>(); // q, nav
        double nav = 1;
        boolean[] in = new boolean[legs];
        int switches = 0, inDays = 0, n = 0;
        for (int q = start; q + MarketData.DAY < toQ; q += MarketData.DAY) {
            double dayRet = 0;
            boolean any = false;
            for (int k = 0; k < legs; k++) {
                Series s = data.get(asset[k + 1]);
                if (s == null) continue;
                double c0 = s.lastClose(q, 8), c1 = s.lastClose(q + MarketData.DAY, 8);
                boolean want = ma == 0 || above(s, q, ma);
                double r = 0;
                if (want != in[k]) {
                    r -= SWITCH_COST;
                    switches++;
                    in[k] = want;
                }
                if (in[k] && !Double.isNaN(c0) && !Double.isNaN(c1) && c0 > 0) r += c1 / c0 - 1;
                dayRet += r / legs;
                any |= in[k];
            }
            nav *= 1 + dayRet;
            days.add(new double[]{q, nav, in[0] ? 1 : 0});
            if (any) inDays++;
            n++;
        }
        if (legs == 1 && asset[1].equals(MarketData.BTC)) btcDays.put(ma == 0 ? "hold" : "ma" + ma, days);
        double[] full = stats(days, start, toQ);
        double[] calib = stats(days, start, splitQ);
        double[] valid = stats(days, splitQ, toQ);
        Map<Integer, Double> yearly = new LinkedHashMap<>();
        double prev = 1;
        int year = -1;
        double yearStart = 1;
        for (double[] d : days) {
            int y = Instant.ofEpochMilli(data.time((int) d[0])).atZone(ZoneOffset.UTC).getYear();
            if (y != year) {
                if (year != -1) yearly.put(year, (prev / yearStart - 1) * 100);
                year = y;
                yearStart = prev;
            }
            prev = d[1];
        }
        if (year != -1) yearly.put(year, (prev / yearStart - 1) * 100);
        double years = n / 365.0;
        double cagr = (Math.pow(nav, 1 / Math.max(years, 1e-9)) - 1) * 100;
        return new Result(name, full[0], full[1], cagr, switches, n == 0 ? 0 : inDays * 100.0 / n, yearly, calib[0], calib[1], valid[0], valid[1]);
    }

    /** [from, to) 段内的 {收益%, 最大回撤%}。 */
    private static double[] stats(List<double[]> days, int from, int to) {
        double base = Double.NaN, peak = Double.NaN, dd = 0, last = Double.NaN;
        double prevNav = 1;
        for (double[] d : days) {
            if (d[0] < from) {
                prevNav = d[1];
                continue;
            }
            if (d[0] >= to) break;
            if (Double.isNaN(base)) {
                base = prevNav;
                peak = prevNav;
            }
            peak = Math.max(peak, d[1]);
            dd = Math.max(dd, 1 - d[1] / peak);
            last = d[1];
        }
        return Double.isNaN(base) ? new double[]{Double.NaN, Double.NaN} : new double[]{(last / base - 1) * 100, dd * 100};
    }

    /** q 时刻收盘价在过去 days 天（每天同一时刻）收盘价均线上方。 */
    static boolean above(Series s, int q, int days) {
        double m = ma(s, q, days);
        double c = s.lastClose(q, 8);
        return !Double.isNaN(m) && !Double.isNaN(c) && c > m;
    }

    /** 过去 days 天（含今天，每天同一时刻）收盘价的平均；有效天数不到 80% 返回 NaN。 */
    static double ma(Series s, int q, int days) {
        double sum = 0;
        int k = 0;
        for (int d = 0; d < days; d++) {
            int i = q - d * MarketData.DAY;
            if (i < 0) break;
            double c = s.lastClose(i, 8);
            if (!Double.isNaN(c)) {
                sum += c;
                k++;
            }
        }
        return k >= days * 0.8 ? sum / k : Double.NaN;
    }

    /**
     * BTC 逐日明细：日期（UTC 0 点那根 15 分钟 K 线收盘时判断）、收盘价、各均线、各方案是否持有和净值。
     * 第 d 行的持有状态决定从这一刻到下一行这一刻的收益；换仓那天扣 0.15%。
     */
    public void writeBtcCsv(java.nio.file.Path file) throws java.io.IOException {
        Series s = data.get(MarketData.BTC);
        List<String> names = new ArrayList<>(btcDays.keySet());
        if (names.isEmpty()) return;
        StringBuilder h = new StringBuilder("date_utc,btc_close");
        for (int m : MA_DAYS) h.append(",ma").append(m);
        for (String n : names) h.append(",in_").append(n).append(",nav_").append(n);
        List<String> lines = new ArrayList<>();
        List<double[]> first = btcDays.get(names.get(0));
        for (int i = 0; i < first.size(); i++) {
            int q = (int) first.get(i)[0];
            StringBuilder l = new StringBuilder(Instant.ofEpochMilli(data.time(q)).toString().substring(0, 10)).append(',').append(s.lastClose(q, 8));
            for (int m : MA_DAYS) l.append(String.format(Locale.ROOT, ",%.2f", ma(s, q, m)));
            for (String n : names) {
                double[] d = btcDays.get(n).get(i);
                l.append(',').append((int) d[2]).append(String.format(Locale.ROOT, ",%.6f", d[1]));
            }
            lines.add(l.toString());
        }
        java.nio.file.Files.writeString(file, h + "\n" + String.join("\n", lines) + "\n");
    }

    public List<Result> results() {
        return results;
    }

    public String markdown(String calibLabel, String validLabel) {
        StringBuilder b = new StringBuilder("## 大币趋势持有（每天 UTC 0 点看一次：在均线上方持有，否则拿现金；换仓扣 0.15%）\n\n");
        b.append("| 方案 | 全程收益% | 年化% | 最大回撤% | 换仓次数 | 持仓时间% | ").append(calibLabel).append(" 收益% / 回撤% | ")
                .append(validLabel).append(" 收益% / 回撤% |\n|---|---|---|---|---|---|---|---|\n");
        for (Result r : results) {
            b.append(String.format(Locale.ROOT, "| %s | %.0f | %.1f | %.1f | %d | %.0f | %.1f / %.1f | %.1f / %.1f |%n", r.name(), r.returnPct(),
                    r.cagrPct(), r.maxDdPct(), r.switches(), r.inMarketPct(), r.calibReturn(), r.calibDd(), r.validReturn(), r.validDd()));
        }
        List<Integer> years = results.isEmpty() ? List.of() : new ArrayList<>(results.get(0).yearly().keySet());
        b.append("\n### 按年份收益%\n\n| 方案 |");
        for (int y : years) b.append(' ').append(y).append(" |");
        b.append("\n|---|");
        for (int y : years) b.append("---|");
        b.append('\n');
        for (Result r : results) {
            b.append("| ").append(r.name()).append(" |");
            for (int y : years) {
                Double v = r.yearly().get(y);
                b.append(v == null ? " - |" : String.format(Locale.ROOT, " %.1f |", v));
            }
            b.append('\n');
        }
        return b.append('\n').toString();
    }
}
