package com.trendpilot.altseason;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 山寨季 5 个前兆指标、S2 开关和"轮动 21 日"排名（只显示，不交易）。
 * 定义逐行照搬研究分支 research/altseason-early-2026-11-05 的 AltEarlyStudy.signals / pick 和 LitStudy.Panel
 * （事先登记见 docs/策略对比记录/2026-11-05-山寨季提前埋伏.md 第 0 节），不改任何参数：
 * <ul>
 * <li>池子：排除 BTC 后，上线满 90 天、按过去 30 日成交额中位数取前 40（时点池）；篮子 ALT = 持有前一天池子等权、每日再平衡；R = ALT / BTC。</li>
 * <li>a ETH/BTC 收盘 &gt; 50 日均线且均线高于 10 天前；b 池内 50 日线上方比例升破 60% 且过去 60 天低于过 30%（之后 14 天算亮）；
 *     c R 创 60 日收盘新高（之后 14 天算亮）；d 稳定币总供应 30 日增速 &gt; 3%；e BTC 上次 90 日新高在 14～28 天前且此后不低于那次的 90%。</li>
 * <li>S2：R 在 R 的 50 日均线上方，且 ALT 在 ALT 的 50 日均线上方。</li>
 * <li>轮动 21 日：池子里按 21 日收益 ÷ 21 日日对数收益波动率打分，取前 5。</li>
 * </ul>
 * 数据缺失 = 该项算不出来（NaN / 不亮），绝不会变成"亮"。
 */
public final class AltEarlySignals {

    public static final int POOL = 40;
    public static final int MIN_AGE = 90;
    public static final int MIN_POOL = 20;
    public static final int EVENT_DAYS = 14;
    public static final int ROT_L = 21;
    public static final int ROT_K = 5;
    public static final String BTC = "BTCUSDT";
    public static final String ETH = "ETHUSDT";
    public static final String[] NAMES = {"ETH/BTC 转强", "山寨广度修复", "篮子/BTC 60 日新高", "稳定币 30 日 > 3%", "BTC 新高后横盘"};

    private AltEarlySignals() {
    }

    /** 公共日历上的日线收盘 / 成交额（第 d 天 = startDate + d，收盘 = 次日 00:00 UTC 的价格）。同 LitStudy.Panel。 */
    public static final class Panel {
        final LocalDate startDate;
        final int days;
        final String[] syms;
        final double[][] cpx; // 沿用的收盘（d 当天或之前最后一个有效收盘），上线前 NaN
        final double[][] qv; // 成交额，没有数据为 0
        final int[][] stale; // 距上一个有效收盘的天数
        final int[] first;
        final boolean[] alt;
        final int btc;
        final int[][] pool; // 时点池：30 日成交额中位数前 40

        /**
         * @param close    close[i][d]，缺失为 NaN 或 ≤ 0
         * @param qvol     qvol[i][d]，缺失为 0
         * @param excluded 不算山寨的交易对（BTC 总是不算）
         */
        public Panel(LocalDate startDate, String[] syms, double[][] close, double[][] qvol, Set<String> excluded) {
            this.startDate = startDate;
            this.syms = syms;
            this.days = close.length == 0 ? 0 : close[0].length;
            int n = syms.length;
            cpx = new double[n][days];
            qv = new double[n][days];
            stale = new int[n][days];
            first = new int[n];
            alt = new boolean[n];
            int b = -1;
            for (int i = 0; i < n; i++) {
                if (syms[i].equals(BTC)) b = i;
                alt[i] = !syms[i].equals(BTC) && !excluded.contains(syms[i]);
                double last = Double.NaN;
                int since = 1_000_000;
                first[i] = -1;
                for (int d = 0; d < days; d++) {
                    double c = close[i][d];
                    if (c > 0) {
                        last = c;
                        since = 0;
                        if (first[i] < 0) first[i] = d;
                    } else {
                        since++;
                    }
                    cpx[i][d] = last;
                    stale[i][d] = since;
                    double v = qvol[i][d];
                    qv[i][d] = v > 0 && c > 0 ? v : 0;
                }
            }
            btc = b;
            pool = new int[days][];
            for (int d = 0; d < days; d++) pool[d] = rankPool(d);
        }

        public int days() {
            return days;
        }

        public LocalDate date(int d) {
            return startDate.plusDays(d);
        }

        public String symbol(int i) {
            return syms[i];
        }

        public int index(String symbol) {
            return Arrays.asList(syms).indexOf(symbol);
        }

        public int[] pool(int d) {
            return pool[d];
        }

        boolean valid(int i, int d) {
            return d >= 0 && d < days && stale[i][d] == 0;
        }

        /** 上线满 90 天；数据第一个月就在交易的币按老币处理。 */
        boolean oldEnough(int i, int d) {
            return first[i] >= 0 && (d - first[i] >= MIN_AGE || first[i] <= 31);
        }

        private int[] rankPool(int d) {
            List<double[]> c = new ArrayList<>();
            double[] w = new double[30];
            for (int i = 0; i < syms.length; i++) {
                if (!alt[i] || !valid(i, d) || !oldEnough(i, d) || d < 29) continue;
                for (int j = 0; j < 30; j++) w[j] = qv[i][d - j];
                double[] s = w.clone();
                Arrays.sort(s);
                double med = (s[14] + s[15]) / 2;
                if (med > 0) c.add(new double[]{i, med});
            }
            c.sort((x, y) -> Double.compare(y[1], x[1]));
            int k = Math.min(POOL, c.size());
            int[] out = new int[k];
            for (int j = 0; j < k; j++) out[j] = (int) c.get(j)[0];
            return out;
        }

        /** l 日简单收益；两端都不是近期收盘时为 NaN。 */
        public double ret(int i, int d, int l) {
            if (d - l < 0 || !valid(i, d) || stale[i][d - l] > 3 || first[i] < 0 || first[i] > d - l) return Double.NaN;
            return cpx[i][d] / cpx[i][d - l] - 1;
        }

        /** 最近 l 天日对数收益的标准差（至少 80% 是真实收盘）。 */
        public double vol(int i, int d, int l) {
            if (d - l < 0 || first[i] < 0 || first[i] > d - l) return Double.NaN;
            double s = 0, s2 = 0;
            int k = 0;
            for (int j = d - l + 1; j <= d; j++) {
                if (stale[i][j] != 0 || Double.isNaN(cpx[i][j - 1])) continue;
                double r = Math.log(cpx[i][j] / cpx[i][j - 1]);
                s += r;
                s2 += r * r;
                k++;
            }
            if (k < Math.max(5, (int) Math.ceil(0.8 * l))) return Double.NaN;
            double m = s / k;
            double v = Math.sqrt(Math.max(0, s2 / k - m * m) * k / Math.max(1, k - 1));
            return v > 0 ? v : Double.NaN;
        }

        double sma(int i, int d, int l) {
            if (d - l + 1 < 0 || first[i] < 0 || first[i] > d - l + 1) return Double.NaN;
            double s = 0;
            int k = 0;
            for (int j = d - l + 1; j <= d; j++) {
                s += cpx[i][j];
                if (stale[i][j] == 0) k++;
            }
            return k < 0.8 * l ? Double.NaN : s / l;
        }

        /** [a, b] 内沿用收盘的最高 / 最低；不到 90% 是真实收盘或 a 之前没上线时为 null。 */
        double[] range(int i, int a, int b) {
            if (a < 0 || first[i] < 0 || first[i] > a) return null;
            double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
            int k = 0;
            for (int j = a; j <= b; j++) {
                hi = Math.max(hi, cpx[i][j]);
                lo = Math.min(lo, cpx[i][j]);
                if (stale[i][j] == 0) k++;
            }
            return k < 0.9 * (b - a + 1) ? null : new double[]{hi, lo};
        }
    }

    /** 每天的指标序列。on[k][d]：第 k 个指标第 d 天是否亮；fire：b、c 当天触发。 */
    public static final class Signals {
        public final double[] alt, r, ethbtc, breadth, stable30, btcFromHigh;
        public final int[] sinceHigh;
        public final boolean[][] on = new boolean[5][];
        public final boolean[][] fire = new boolean[5][];
        public final int[] count;
        public final boolean[] s2;

        Signals(int days) {
            alt = nan(days);
            r = nan(days);
            ethbtc = nan(days);
            breadth = nan(days);
            stable30 = nan(days);
            btcFromHigh = nan(days);
            sinceHigh = new int[days];
            Arrays.fill(sinceHigh, -1);
            for (int k = 0; k < 5; k++) {
                on[k] = new boolean[days];
                fire[k] = new boolean[days];
            }
            count = new int[days];
            s2 = new boolean[days];
        }
    }

    static double[] nan(int n) {
        double[] x = new double[n];
        Arrays.fill(x, Double.NaN);
        return x;
    }

    /** x[d-l+1..d] 的简单均值；有缺失就是 NaN。 */
    public static double sma(double[] x, int d, int l) {
        if (d - l + 1 < 0 || d >= x.length) return Double.NaN;
        double s = 0;
        for (int j = d - l + 1; j <= d; j++) {
            if (Double.isNaN(x[j])) return Double.NaN;
            s += x[j];
        }
        return s / l;
    }

    /** @param stable 稳定币总供应，按 UTC 日（epochDay，DefiLlama 当天 00:00 的值）；空 = 指标 d 算不出来 */
    public static Signals signals(Panel p, Map<Long, Double> stable) {
        int days = p.days;
        Signals s = new Signals(days);
        int btc = p.btc, eth = p.index(ETH);
        // 山寨篮子：持有前一天的池子等权
        for (int d = 1; d < days; d++) {
            int[] mem = p.pool[d - 1];
            if (mem.length < MIN_POOL) continue; // 篮子算不出来 = 没有信号
            double base = Double.isNaN(s.alt[d - 1]) ? 1 : s.alt[d - 1];
            double sum = 0;
            for (int i : mem) sum += p.cpx[i][d] / p.cpx[i][d - 1] - 1;
            s.alt[d] = base * (1 + sum / mem.length);
        }
        for (int d = 0; d < days; d++) {
            boolean b = btc >= 0 && p.valid(btc, d);
            if (b && !Double.isNaN(s.alt[d])) s.r[d] = s.alt[d] / p.cpx[btc][d];
            if (b && eth >= 0 && p.valid(eth, d)) s.ethbtc[d] = p.cpx[eth][d] / p.cpx[btc][d];
            int[] mem = p.pool[d];
            int n = 0, up = 0;
            for (int i : mem) {
                double m = p.sma(i, d, 50);
                if (Double.isNaN(m) || !p.valid(i, d)) continue;
                n++;
                if (p.cpx[i][d] > m) up++;
            }
            if (n >= MIN_POOL) s.breadth[d] = (double) up / n;
            long e = p.date(d).toEpochDay();
            Double v0 = stable.get(e), v30 = stable.get(e - 30);
            if (v0 != null && v30 != null && v30 > 0) s.stable30[d] = v0 / v30 - 1;
        }
        // BTC 90 日收盘新高
        int lastHigh = -1;
        for (int d = 0; d < days; d++) {
            if (btc < 0 || !p.valid(btc, d)) continue;
            double[] rg = p.range(btc, d - 89, d);
            if (rg != null && p.cpx[btc][d] >= rg[0]) lastHigh = d;
            if (lastHigh >= 0) {
                s.sinceHigh[d] = d - lastHigh;
                s.btcFromHigh[d] = p.cpx[btc][d] / p.cpx[btc][lastHigh] - 1;
            }
        }
        for (int d = 0; d < days; d++) {
            // a：ETH/BTC 在 50 日均线上方，且均线比 10 天前高
            double m = sma(s.ethbtc, d, 50), m10 = sma(s.ethbtc, d - 10, 50);
            s.on[0][d] = !Double.isNaN(m) && !Double.isNaN(m10) && s.ethbtc[d] > m && m > m10;
            // b：宽度升破 60%，且过去 60 天里低于过 30%
            if (d >= 60 && !Double.isNaN(s.breadth[d]) && s.breadth[d] >= 0.6 && !Double.isNaN(s.breadth[d - 1]) && s.breadth[d - 1] < 0.6) {
                double lo = Double.POSITIVE_INFINITY;
                for (int j = d - 60; j < d; j++) if (!Double.isNaN(s.breadth[j])) lo = Math.min(lo, s.breadth[j]);
                s.fire[1][d] = lo < 0.3;
            }
            // c：R 收盘创 60 日新高
            if (d >= 59 && !Double.isNaN(s.r[d])) {
                boolean ok = true;
                for (int j = d - 59; j < d && ok; j++) ok = !Double.isNaN(s.r[j]) && s.r[d] >= s.r[j];
                s.fire[2][d] = ok;
            }
            // d：稳定币总供应 30 日增长超过 3%
            s.on[3][d] = !Double.isNaN(s.stable30[d]) && s.stable30[d] > 0.03;
            // e：上次 BTC 90 日新高在 14～28 天前，且此后一直不低于那次的 90%
            int h = s.sinceHigh[d];
            if (btc >= 0 && p.valid(btc, d) && h >= 14 && h <= 28) {
                double hi = p.cpx[btc][d - h], lo = Double.POSITIVE_INFINITY;
                for (int j = d - h + 1; j <= d; j++) lo = Math.min(lo, p.cpx[btc][j]);
                s.on[4][d] = lo >= 0.9 * hi;
            }
        }
        for (int k : new int[]{1, 2}) {
            for (int d = 0; d < days; d++) {
                boolean a = false;
                for (int j = Math.max(0, d - EVENT_DAYS + 1); j <= d && !a; j++) a = s.fire[k][j];
                s.on[k][d] = a;
            }
        }
        for (int d = 0; d < days; d++) {
            int c = 0;
            for (int k = 0; k < 5; k++) if (s.on[k][d]) c++;
            s.count[d] = c;
            double mr = sma(s.r, d, 50), ma = sma(s.alt, d, 50);
            s.s2[d] = !Double.isNaN(mr) && !Double.isNaN(ma) && s.r[d] > mr && s.alt[d] > ma;
        }
        return s;
    }

    /** 轮动 21 日的一个入选币：21 日收益、21 日波动率、打分 = 收益 ÷ 波动率。 */
    public record Pick(String symbol, double ret21, double vol21, double score) {
    }

    /** 第 d 天池子里按 21 日收益 ÷ 21 日波动率排名的前 k 个（与研究的 Choice.top 同序，分数相同时按池内顺序）。 */
    public static List<Pick> rotation(Panel p, int d, int k) {
        List<Integer> ci = new ArrayList<>();
        List<double[]> rv = new ArrayList<>();
        for (int i : p.pool[d]) {
            double r = p.ret(i, d, ROT_L), v = p.vol(i, d, ROT_L);
            if (Double.isNaN(r) || Double.isNaN(v)) continue;
            ci.add(i);
            rv.add(new double[]{r, v, r / v});
        }
        Integer[] o = new Integer[ci.size()];
        for (int j = 0; j < o.length; j++) o[j] = j;
        Arrays.sort(o, (a, b) -> Double.compare(rv.get(b)[2], rv.get(a)[2]));
        List<Pick> out = new ArrayList<>();
        for (int j = 0; j < Math.min(k, o.length); j++) {
            double[] x = rv.get(o[j]);
            out.add(new Pick(p.syms[ci.get(o[j])], x[0], x[1], x[2]));
        }
        return out;
    }

    /**
     * 某一天各指标的数值和离"亮"还差多少（NaN = 算不出来）。字段口径同研究报告的"当前状态"一节。
     *
     * @param ethBtcVs50     ETH/BTC 比 50 日均线
     * @param ethSmaSlope10  50 日均线比 10 天前
     * @param breadth        池内 50 日线上方比例（0～1）
     * @param breadthLow60   过去 60 天最低比例
     * @param rFromHigh60    R 比过去 59 天最高收盘（≥ 0 即新高）
     * @param lastFireB      最近一次 b 触发的日期（窗口内没有为 null）
     * @param sinceHigh      距上次 BTC 90 日新高的天数（窗口内没有为 -1）
     */
    public record Status(LocalDate day, double ethBtc, double ethBtcVs50, double ethSmaSlope10, double breadth, double breadthLow60,
                         LocalDate lastFireB, double rFromHigh60, LocalDate lastFireC, double stable30, int sinceHigh, double btcFromHigh,
                         double rVs50, double altVs50, boolean[] on, int count, boolean s2) {

        /** 第 k 个指标是否有数据可判断（没有数据显示"-"，不算亮）。 */
        public boolean known(int k) {
            return switch (k) {
                case 0 -> !Double.isNaN(ethBtcVs50) && !Double.isNaN(ethSmaSlope10);
                case 1 -> !Double.isNaN(breadth);
                case 2 -> !Double.isNaN(rFromHigh60);
                case 3 -> !Double.isNaN(stable30);
                case 4 -> sinceHigh >= 0 && !Double.isNaN(btcFromHigh);
                default -> false;
            };
        }

        public boolean s2Known() {
            return !Double.isNaN(rVs50) && !Double.isNaN(altVs50);
        }
    }

    public static Status status(Panel p, Signals s, int d) {
        double m = sma(s.ethbtc, d, 50), m10 = sma(s.ethbtc, d - 10, 50);
        double lo = Double.POSITIVE_INFINITY;
        for (int j = Math.max(0, d - 60); j < d; j++) if (!Double.isNaN(s.breadth[j])) lo = Math.min(lo, s.breadth[j]);
        LocalDate lastB = null, lastC = null;
        for (int j = d; j >= 0 && lastB == null; j--) if (s.fire[1][j]) lastB = p.date(j);
        for (int j = d; j >= 0 && lastC == null; j--) if (s.fire[2][j]) lastC = p.date(j);
        double hi = Double.NEGATIVE_INFINITY;
        boolean full = d >= 59;
        for (int j = Math.max(0, d - 59); j < d; j++) {
            if (Double.isNaN(s.r[j])) full = false;
            else hi = Math.max(hi, s.r[j]);
        }
        double mr = sma(s.r, d, 50), ma = sma(s.alt, d, 50);
        boolean[] on = new boolean[5];
        for (int k = 0; k < 5; k++) on[k] = s.on[k][d];
        return new Status(p.date(d), s.ethbtc[d], s.ethbtc[d] / m - 1, m / m10 - 1, s.breadth[d], lo == Double.POSITIVE_INFINITY ? Double.NaN : lo,
                lastB, full ? s.r[d] / hi - 1 : Double.NaN, lastC, s.stable30[d], s.sinceHigh[d], s.btcFromHigh[d], s.r[d] / mr - 1,
                s.alt[d] / ma - 1, on, s.count[d], s.s2[d]);
    }
}
