package com.trendpilot.altseason;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 山寨季指标和状态（只提醒、不交易）。阈值用 2022-10 ～ 2026-10 的周度数据校准（见 docs/策略对比记录/2026-11-02-山寨季提醒.md，
 * 12 组里误报最少的一组）：
 * <ul>
 * <li>进入"山寨季早期"：行情状态 ≥ CONSTRUCTIVE、池内 ≥ 70% 的币收盘在 50 日均线上方、山寨 30 日收益中位数连续 14 天跑赢 BTC、
 *     ETH/BTC 在其 50 日均线上方。</li>
 * <li>退出：宽度跌到 40% 以下，或山寨 30 日比 BTC 少 10 个百分点以上。</li>
 * <li>缺数据（BTC / ETH 日线不全，或池里有效币不到 50 个）不判断，状态不变。</li>
 * </ul>
 */
public final class AltSeasonRules {

    public static final double ENTER_BREADTH50 = 70;
    public static final int ENTER_OUTPERFORM_DAYS = 14;
    public static final double EXIT_BREADTH50 = 40;
    public static final double EXIT_ALT_VS_BTC = -10;
    public static final int MIN_POOL = 50;

    public enum State { OFF, EARLY }

    /** 一天的指标；NaN = 算不出来。 */
    public record Metrics(int pool, double breadth50, double breadth200, double alt30, double altVsBtc30, double ethBtcVs50,
                          double ethBtcVs100, boolean v3Open) {

        boolean complete() {
            return pool >= MIN_POOL && !Double.isNaN(breadth50) && !Double.isNaN(altVsBtc30) && !Double.isNaN(ethBtcVs50);
        }
    }

    private AltSeasonRules() {
    }

    /**
     * @param daily  每个币已收盘的日线收盘价（时间从早到晚，最后一个是刚收盘的那天）；不含 BTC
     * @param btc    BTC 日线收盘
     * @param eth    ETH 日线收盘（ETH/BTC 用）
     */
    public static Metrics compute(Map<String, double[]> daily, double[] btc, double[] eth) {
        int pool = 0, above50 = 0, n200 = 0, above200 = 0, above20 = 0;
        List<Double> r30 = new ArrayList<>();
        for (double[] c : daily.values()) {
            if (c.length < 51) continue;
            pool++;
            double last = c[c.length - 1];
            if (last > sma(c, 50)) above50++;
            if (last > sma(c, 20)) above20++;
            if (c.length >= 200) {
                n200++;
                if (last > sma(c, 200)) above200++;
            }
            double c30 = c[c.length - 31];
            if (c30 > 0) r30.add((last / c30 - 1) * 100);
        }
        double alt30 = median(r30);
        double btc30 = btc == null || btc.length < 51 ? Double.NaN : (btc[btc.length - 1] / btc[btc.length - 31] - 1) * 100;
        double vs50 = Double.NaN, vs100 = Double.NaN;
        if (btc != null && eth != null && btc.length >= 100 && eth.length >= 100) {
            double[] r = new double[100];
            for (int i = 0; i < 100; i++) r[i] = eth[eth.length - 100 + i] / btc[btc.length - 100 + i];
            vs50 = (r[99] / sma(r, 50) - 1) * 100;
            vs100 = (r[99] / sma(r, 100) - 1) * 100;
        }
        boolean v3 = btc != null && btc.length >= 51 && btc[btc.length - 1] > sma(btc, 50) && pool > 0 && above20 >= 0.5 * pool;
        return new Metrics(pool, pool == 0 ? Double.NaN : above50 * 100.0 / pool, n200 == 0 ? Double.NaN : above200 * 100.0 / n200,
                alt30, alt30 - btc30, vs50, vs100, v3);
    }

    /** 今天的状态。regimeOk = 行情状态 ≥ CONSTRUCTIVE。 */
    public static State next(State prev, Metrics m, int outperformDays, boolean regimeOk) {
        if (!m.complete()) return prev;
        if (prev == State.EARLY) {
            return m.breadth50() < EXIT_BREADTH50 || m.altVsBtc30() < EXIT_ALT_VS_BTC ? State.OFF : State.EARLY;
        }
        boolean enter = regimeOk && m.breadth50() >= ENTER_BREADTH50 && outperformDays >= ENTER_OUTPERFORM_DAYS && m.ethBtcVs50() > 0;
        return enter ? State.EARLY : State.OFF;
    }

    static double sma(double[] c, int n) {
        double s = 0;
        for (int i = c.length - n; i < c.length; i++) s += c[i];
        return s / n;
    }

    static double median(List<Double> x) {
        if (x.isEmpty()) return Double.NaN;
        List<Double> y = new ArrayList<>(x);
        Collections.sort(y);
        return y.get(y.size() / 2);
    }
}
