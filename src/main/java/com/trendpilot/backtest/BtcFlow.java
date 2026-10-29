package com.trendpilot.backtest;

import java.util.Arrays;

/**
 * BTC 自己的主动买卖（只看已成交）：K 线里的 taker buy quote volume = 主动买入成交额，其余 = 主动卖出成交额。
 * 只用 q 及之前已收盘的 15m K 线，不看未来。数据来自 data.binance.vision 的 K 线包（第 11 列），不另外请求接口。
 * <ul>
 * <li>{@link #share}：最近 N 小时主动买入占成交额的比例（0～1）；</li>
 * <li>{@link #percentile}：这个比例在之前 30 天（720 个整点）同一窗口取值里的分位（0～100）；</li>
 * <li>{@link #cvd}：最近 N 小时累计主动买入 − 主动卖出（U）。</li>
 * </ul>
 * 数据缺失（没有成交或旧数据没有主动买入列）返回 NaN；做开仓过滤时 NaN 一律按"不开"。
 */
public final class BtcFlow {

    /** 分位用的历史长度：之前 30 天的整点。 */
    static final int LOOKBACK_HOURS = 720;
    /** 历史里至少要有这么多个有效值才给分位。 */
    static final int MIN_HISTORY = 360;
    static final int[] WINDOWS = {1, 4, 24};

    private final Series btc;
    private final double[] cumQuote;
    private final double[] cumBuy;
    /** [窗口下标][小时 h] → 截至第 4h 根（含）的占比分位；只在整点后第一根（q % 4 == 0，Discovery）算。 */
    private final float[][] pct = new float[WINDOWS.length][];

    public BtcFlow(Series btc) {
        this.btc = btc;
        int n = btc.size();
        cumQuote = new double[n + 1];
        cumBuy = new double[n + 1];
        for (int i = 0; i < n; i++) {
            boolean ok = btc.has(i);
            cumQuote[i + 1] = cumQuote[i] + (ok ? btc.quoteVolume[i] : 0);
            cumBuy[i + 1] = cumBuy[i] + (ok ? btc.takerBuyQuote[i] : 0);
        }
        int hours = n / 4 + 1;
        for (int w = 0; w < WINDOWS.length; w++) {
            double[] v = new double[hours];
            for (int h = 0; h < hours; h++) v[h] = share(h * 4, WINDOWS[w]);
            float[] p = new float[hours];
            Arrays.fill(p, Float.NaN);
            for (int h = 0; h < hours; h++) {
                if (Double.isNaN(v[h])) continue;
                int valid = 0, below = 0;
                for (int k = Math.max(0, h - LOOKBACK_HOURS); k < h; k++) {
                    if (Double.isNaN(v[k])) continue;
                    valid++;
                    if (v[k] < v[h]) below++;
                }
                if (valid >= MIN_HISTORY) p[h] = (float) (100.0 * below / valid);
            }
            pct[w] = p;
        }
    }

    /** 截至第 q 根（含）最近 hours 小时的主动买入占比；窗口里缺数据超过 1/4 或没有主动买入列时 NaN。 */
    public double share(int q, int hours) {
        int n = hours * 4;
        int from = q - n + 1;
        if (from < 0 || q >= btc.size()) return Double.NaN;
        int have = 0;
        for (int i = from; i <= q; i++) if (btc.has(i)) have++;
        if (have * 4 < n * 3) return Double.NaN;
        double v = cumQuote[q + 1] - cumQuote[from];
        double b = cumBuy[q + 1] - cumBuy[from];
        return v <= 0 || b <= 0 ? Double.NaN : b / v;
    }

    /** 截至第 q 根最近 hours 小时累计主动买入 − 主动卖出（U）；缺数据 NaN。 */
    public double cvd(int q, int hours) {
        double s = share(q, hours);
        if (Double.isNaN(s)) return Double.NaN;
        int from = q - hours * 4 + 1;
        double v = cumQuote[q + 1] - cumQuote[from];
        double b = cumBuy[q + 1] - cumBuy[from];
        return 2 * b - v;
    }

    /** 第 q 根（整点后第一根）时 hours 小时占比在之前 30 天里的分位（0～100）；不是整点后第一根或历史不够时 NaN。 */
    public double percentile(int q, int hours) {
        if (q % 4 != 0) return Double.NaN;
        int w = Arrays.binarySearch(WINDOWS, hours);
        if (w < 0) throw new IllegalArgumentException("分位只支持 1/4/24 小时窗口：" + hours);
        int h = q / 4;
        return h < pct[w].length ? pct[w][h] : Double.NaN;
    }

    /**
     * 开仓过滤（只管新开仓，加仓不管）："kind:hours:value"。
     * <ul>
     * <li>pct-min:4:50 —— 4h 占比分位 ≥ 50 才开；</li>
     * <li>share-min:4:0.52 —— 4h 占比 &gt; 52% 才开；</li>
     * <li>cvd-pos:4 —— 4h CVD 为负时不开。</li>
     * </ul>
     * 数据缺失一律"不开"。
     */
    public java.util.function.IntPredicate gate(String spec) {
        String[] p = spec.split(":");
        int hours = Integer.parseInt(p[1]);
        return switch (p[0]) {
            case "pct-min" -> {
                double x = Double.parseDouble(p[2]);
                yield q -> {
                    double v = percentile(q, hours);
                    return !Double.isNaN(v) && v >= x;
                };
            }
            case "share-min" -> {
                double x = Double.parseDouble(p[2]);
                yield q -> {
                    double v = share(q, hours);
                    return !Double.isNaN(v) && v > x;
                };
            }
            case "cvd-pos" -> q -> {
                double v = cvd(q, hours);
                return !Double.isNaN(v) && v >= 0;
            };
            default -> throw new IllegalArgumentException("看不懂的 BTC 主动买卖过滤：" + spec);
        };
    }
}
