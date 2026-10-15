package com.trendpilot.backtest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.trendpilot.market.SymbolBars;
import com.trendpilot.market.Ticker24h;

/**
 * 回测用的全部行情：统一网格上的 15m 序列、每天的标的池（前一天成交额前 N）、每小时的大盘指标和每个币的均线/波动。
 * 所有指标只用当时已收盘的 K 线，不看未来。
 */
public final class MarketData {

    public static final String BTC = "BTCUSDT";
    public static final String ETH = "ETHUSDT";
    static final int DAY = 96;

    public final long start;
    public final int size;
    public final Map<String, Series> series;
    public final Series btc;
    /** 第 d 天（从 start 起）的标的池（不含 BTC），按前一天成交额从高到低。 */
    private final List<List<String>> universeByDay = new ArrayList<>();
    private final Map<String, Indicators> indicators = new HashMap<>();
    /** 每个整点（小时下标）的大盘指标。 */
    private final Context[] contexts;

    /** 每个币按小时下标 b 的指标：只用 b 之前已收盘的小时 K 线（b 是整点边界）。 */
    public static final class Indicators {
        final float[] ema20h;
        final float[] ema50h;
        final float[] atr24h;
        final float[] ema20x4h;

        Indicators(int hours) {
            ema20h = nanArray(hours);
            ema50h = nanArray(hours);
            atr24h = nanArray(hours);
            ema20x4h = nanArray(hours);
        }
    }

    /**
     * 整点 b 时的大盘：BTC 1/4/24 小时、7 天涨跌；山寨（池内不含 ETH）1/4/24 小时、7 天涨跌中位数；
     * 山寨收盘在自己 4h EMA20 之上的占比；24h 上涨占比（广度）。百分比。
     */
    public record Context(double btc1h, double btc4h, double btc24h, double btc7d, double alt1h, double alt4h,
                          double alt24h, double alt7d, double trendShare, double breadth, int altCount) {
    }

    public MarketData(long start, int size, Map<String, Series> series, int universeSize, Set<String> excluded) {
        this.start = start;
        this.size = size;
        this.btc = series.get(BTC);
        if (btc == null) throw new IllegalStateException("缺少 BTCUSDT 数据");
        int days = size / DAY;
        for (int d = 0; d < days; d++) {
            int at = d * DAY - 1; // 前一天最后一根
            List<String> u = new ArrayList<>();
            if (at >= DAY - 1) {
                final int last = at;
                u = series.values().stream()
                        .filter(s -> !s.symbol.equals(BTC) && !excluded.contains(s.symbol) && s.has(last))
                        .sorted(Comparator.comparingDouble((Series s) -> -s.volume(last, DAY)))
                        .limit(universeSize).map(s -> s.symbol).toList();
            }
            universeByDay.add(u);
        }
        Set<String> ever = new java.util.HashSet<>();
        universeByDay.forEach(ever::addAll);
        this.series = keep(series, ever);
        int hours = size / 4 + 1;
        for (Series s : this.series.values()) indicators.put(s.symbol, compute(s, hours));
        contexts = new Context[hours];
        for (int b = 0; b < hours; b++) contexts[b] = computeContext(b);
    }

    public List<String> universe(int q) {
        int d = q / DAY;
        return d < universeByDay.size() ? universeByDay.get(d) : List.of();
    }

    public Series get(String symbol) {
        return series.get(symbol);
    }

    public Indicators ind(String symbol) {
        return indicators.get(symbol);
    }

    /** q 根 15m 收盘后所在整点的大盘（整点 = q 所在小时的开始）。 */
    public Context context(int q) {
        return contexts[q / 4];
    }

    public Context contextAtHour(int b) {
        return contexts[b];
    }

    public long time(int q) {
        return start + q * Series.Q;
    }

    /** q 根收盘时可用的 K 线截面（26 根小时 + 22 根 15m，和线上 BarsLoader 一致）。数据不完整返回 null。 */
    public SymbolBars bars(String symbol, int q) {
        Series s = series.get(symbol);
        if (s == null) return null;
        var hourly = s.hours(q, 26);
        var quarter = s.quarters(q, 22);
        return hourly == null || quarter == null ? null : new SymbolBars(symbol, hourly, quarter);
    }

    /** q 根收盘时的 24h 行情（涨跌幅、成交额）。 */
    public Ticker24h ticker(String symbol, int q) {
        Series s = series.get(symbol);
        if (s == null || !s.has(q) || !s.has(q - DAY)) return null;
        return new Ticker24h(symbol, s.close[q], (s.close[q] / s.close[q - DAY] - 1) * 100, s.volume(q, DAY));
    }

    private static Indicators compute(Series s, int hours) {
        Indicators ind = new Indicators(hours);
        double e20 = Double.NaN, e50 = Double.NaN, e4 = Double.NaN;
        double prevClose = Double.NaN;
        double[] tr = new double[24];
        int trCount = 0;
        int n20 = 0, n50 = 0, n4 = 0;
        for (int b = 1; b < hours; b++) {
            int i = (b - 1) * 4; // 小时 K 线 b-1 的第一根 15m
            boolean complete = i + 4 <= s.size() && s.complete(i, i + 4);
            if (complete) {
                var k = s.hourly(i);
                double c = k.close();
                e20 = n20 == 0 ? c : e20 + (c - e20) * 2 / 21;
                e50 = n50 == 0 ? c : e50 + (c - e50) * 2 / 51;
                n20++;
                n50++;
                double t = Double.isNaN(prevClose) ? k.high() - k.low()
                        : Math.max(k.high(), prevClose) - Math.min(k.low(), prevClose);
                tr[trCount % 24] = t;
                trCount++;
                prevClose = c;
                if (b % 4 == 0) {
                    e4 = n4 == 0 ? c : e4 + (c - e4) * 2 / 21;
                    n4++;
                }
            }
            if (n20 >= 20) ind.ema20h[b] = (float) e20;
            if (n50 >= 50) ind.ema50h[b] = (float) e50;
            if (trCount >= 24) ind.atr24h[b] = (float) (Arrays.stream(tr).sum() / 24);
            if (n4 >= 20) ind.ema20x4h[b] = (float) e4;
        }
        return ind;
    }

    private Context computeContext(int b) {
        int q = b * 4 - 1; // 整点前最后一根
        if (q < 7 * DAY) return null;
        List<String> u = universe(q);
        double btc1h = ret(btc, q, 4), btc4h = ret(btc, q, 16), btc24h = ret(btc, q, DAY), btc7d = ret(btc, q, 7 * DAY);
        List<Double> r1 = new ArrayList<>(), r4 = new ArrayList<>(), r24 = new ArrayList<>(), r7 = new ArrayList<>();
        int above = 0, trendN = 0, up = 0, breadthN = 0;
        for (String sym : u) {
            Series s = series.get(sym);
            double x24 = ret(s, q, DAY);
            if (!Double.isNaN(x24)) {
                breadthN++;
                if (x24 > 0) up++;
            }
            if (sym.equals(ETH)) continue;
            add(r1, ret(s, q, 4));
            add(r4, ret(s, q, 16));
            add(r24, x24);
            add(r7, ret(s, q, 7 * DAY));
            float e = indicators.get(sym).ema20x4h[b];
            if (!Float.isNaN(e) && s.has(q)) {
                trendN++;
                if (s.close[q] > e) above++;
            }
        }
        return new Context(btc1h, btc4h, btc24h, btc7d, median(r1), median(r4), median(r24), median(r7),
                trendN == 0 ? Double.NaN : above / (double) trendN, breadthN == 0 ? Double.NaN : up / (double) breadthN,
                r24.size());
    }

    /** 第 q 根收盘相对 n 根之前那根收盘的涨跌（%），缺数据 NaN。 */
    static double ret(Series s, int q, int n) {
        if (s == null || !s.has(q) || !s.has(q - n)) return Double.NaN;
        return (s.close[q] / s.close[q - n] - 1) * 100;
    }

    private static void add(List<Double> l, double v) {
        if (!Double.isNaN(v)) l.add(v);
    }

    static double median(List<Double> v) {
        if (v.size() < 20) return Double.NaN;
        double[] a = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return a.length % 2 == 1 ? a[a.length / 2] : (a[a.length / 2 - 1] + a[a.length / 2]) / 2;
    }

    private static float[] nanArray(int n) {
        float[] a = new float[n];
        Arrays.fill(a, Float.NaN);
        return a;
    }

    /** 只保留曾经进过标的池的币和 BTC，释放其余内存。 */
    public static Map<String, Series> keep(Map<String, Series> all, Set<String> symbols) {
        Map<String, Series> out = new LinkedHashMap<>();
        all.forEach((k, v) -> {
            if (symbols.contains(k) || k.equals(BTC)) out.put(k, v);
        });
        return out;
    }
}
