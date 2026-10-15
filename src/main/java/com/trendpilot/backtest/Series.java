package com.trendpilot.backtest;

import java.util.ArrayList;
import java.util.List;

import com.trendpilot.market.Kline;

/**
 * 一个标的在统一时间网格上的 15m K 线（回测用，float 省内存）。没有数据的格子 close 为 NaN。
 * 下标 i 对应开盘时间 start + i × 15 分钟。
 */
public final class Series {

    public static final long Q = 15 * 60_000L;
    public static final long H = 60 * 60_000L;

    public final String symbol;
    public final long start;
    public final float[] open;
    public final float[] high;
    public final float[] low;
    public final float[] close;
    public final float[] quoteVolume;

    public Series(String symbol, long start, int size) {
        this.symbol = symbol;
        this.start = start;
        open = new float[size];
        high = new float[size];
        low = new float[size];
        close = new float[size];
        quoteVolume = new float[size];
        java.util.Arrays.fill(close, Float.NaN);
    }

    public int size() {
        return close.length;
    }

    public boolean has(int i) {
        return i >= 0 && i < close.length && !Float.isNaN(close[i]);
    }

    public void set(int i, double o, double h, double l, double c, double qv) {
        if (i < 0 || i >= close.length) return;
        open[i] = (float) o;
        high[i] = (float) h;
        low[i] = (float) l;
        close[i] = (float) c;
        quoteVolume[i] = (float) qv;
    }

    public long openTime(int i) {
        return start + i * Q;
    }

    public Kline kline(int i) {
        return new Kline(openTime(i), open[i], high[i], low[i], close[i], quoteVolume[i], openTime(i) + Q - 1);
    }

    /** [from, to) 全部有数据。 */
    public boolean complete(int from, int to) {
        if (from < 0 || to > close.length) return false;
        for (int i = from; i < to; i++) if (Float.isNaN(close[i])) return false;
        return true;
    }

    /** 截至第 last 根（含）的 n 根 15m K 线；不完整返回 null。 */
    public List<Kline> quarters(int last, int n) {
        if (!complete(last - n + 1, last + 1)) return null;
        List<Kline> out = new ArrayList<>(n);
        for (int i = last - n + 1; i <= last; i++) out.add(kline(i));
        return out;
    }

    /** 截至第 last 根 15m（含）之前已完整的 n 根小时 K 线（4 根 15m 合成）；不完整返回 null。 */
    public List<Kline> hours(int last, int n) {
        int endHour = hourEnd(last);
        int from = endHour - 4 * n;
        if (!complete(from, endHour)) return null;
        List<Kline> out = new ArrayList<>(n);
        for (int i = from; i < endHour; i += 4) out.add(hourly(i));
        return out;
    }

    /** 第 i 根开始的 4 根 15m 合成 1 根小时 K 线（i 需在整点）。 */
    public Kline hourly(int i) {
        double hi = Math.max(Math.max(high[i], high[i + 1]), Math.max(high[i + 2], high[i + 3]));
        double lo = Math.min(Math.min(low[i], low[i + 1]), Math.min(low[i + 2], low[i + 3]));
        double qv = quoteVolume[i] + quoteVolume[i + 1] + quoteVolume[i + 2] + quoteVolume[i + 3];
        return new Kline(openTime(i), open[i], hi, lo, close[i + 3], qv, openTime(i) + H - 1);
    }

    /** 截至第 last 根 15m（含）最后一个完整小时之后的下标（即该小时最后一根 15m 的下一根）。 */
    public int hourEnd(int last) {
        long t = openTime(last + 1);
        long hourStart = Math.floorDiv(t, H) * H;
        return (int) ((hourStart - start) / Q);
    }

    /** 最近 n 根 15m 的成交额合计（缺数据按 0）。 */
    public double volume(int last, int n) {
        double s = 0;
        for (int i = Math.max(0, last - n + 1); i <= last && i < close.length; i++) {
            if (!Float.isNaN(close[i])) s += quoteVolume[i];
        }
        return s;
    }

    /** 第 i 根之前（含）最近一根有数据的收盘价；没有返回 NaN。 */
    public double lastClose(int i, int maxBack) {
        for (int k = i; k >= 0 && k > i - maxBack; k--) if (!Float.isNaN(close[k])) return close[k];
        return Double.NaN;
    }

    private int lastIndex = -2;

    /** 最后一根有数据的下标（用于判断下架）；没有数据为 -1。 */
    public int lastIndex() {
        if (lastIndex == -2) {
            int k = close.length - 1;
            while (k >= 0 && Float.isNaN(close[k])) k--;
            lastIndex = k;
        }
        return lastIndex;
    }
}
