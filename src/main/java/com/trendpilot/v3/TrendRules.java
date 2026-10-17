package com.trendpilot.v3;

/**
 * V3 趋势跟踪（"大赚小亏"，用户 2026-10-17 要求做成 V3、影子跑 1 个月）的规则，事先登记，回测和线上共用：
 * <ul>
 * <li>标的：Binance 前一天成交额前 100 的山寨（不含 BTC、稳定币、杠杆币、股票代币、黑名单），至少有 61 根日线。</li>
 * <li>大盘过滤（只在多头时开新仓）：BTC 日收盘 &gt; 50 日均线，且标的池里收盘在 20 日均线上方的比例 ≥ 50%。</li>
 * <li>买点（日线收盘确认，下一根日线开盘买）：收盘创 20 日新高（高于前 20 天最高价）、当天成交额 ≥ 前 20 天平均的 1.5 倍、
 *     收盘不高于 20 日均线的 1.3 倍（不追离均线太远的）。</li>
 * <li>初始止损：买入价 − 2 × 日线 ATR14，距离限制在 5%～15%。</li>
 * <li>跟踪止损（让利润跑）：止损线 = max(初始止损, 买入以来最高价 − 3 × 买入时 ATR14)，只上调；另外日线收盘跌破前 10 天最低价卖出。
 *     没有固定止盈、没有过早的利润保护。</li>
 * <li>仓位：每笔风险 200 U（资金 20,000 U 的 1%），金额 = 200 / 止损距离，单笔上限 3,000 U；最多同时 10 笔，总占用不超过 20,000 U。</li>
 * </ul>
 */
public final class TrendRules {

    public static final int POOL = 100;
    public static final int MIN_DAYS = 61;
    public static final int BREAKOUT_DAYS = 20;
    public static final double VOLUME_MULT = 1.5;
    public static final double MAX_ABOVE_MA20 = 1.3;
    public static final double STOP_ATR = 2.0;
    public static final double MIN_STOP_PCT = 5;
    public static final double MAX_STOP_PCT = 15;
    public static final double TRAIL_ATR = 3.0;
    public static final int EXIT_LOW_DAYS = 10;
    public static final double CAPITAL = 20_000;
    public static final double RISK_USDT = 200;
    public static final double MAX_NOTIONAL = 3_000;
    public static final int MAX_POSITIONS = 10;
    public static final double BREADTH_MIN = 0.5;

    private TrendRules() {
    }

    /** 日线 ATR14（价格单位）。h/l/c 是到判断那天为止的已收盘日线（升序），至少 15 根。 */
    public static double atr(double[] h, double[] l, double[] c, int end) {
        double sum = 0;
        for (int i = end - 13; i <= end; i++) {
            double prev = c[i - 1];
            sum += Math.max(h[i], prev) - Math.min(l[i], prev);
        }
        return sum / 14;
    }

    public static double sma(double[] c, int end, int n) {
        double s = 0;
        for (int i = end - n + 1; i <= end; i++) s += c[i];
        return s / n;
    }

    /** 第 end 根日线收盘后是否满足买点（h/l/c/v 是已收盘日线，end 是最后一根）。 */
    public static boolean breakout(double[] h, double[] c, double[] v, int end) {
        if (end < MIN_DAYS - 1) return false;
        double hh = 0, vol = 0;
        for (int i = end - BREAKOUT_DAYS; i < end; i++) {
            hh = Math.max(hh, h[i]);
            vol += v[i];
        }
        return c[end] > hh && v[end] >= VOLUME_MULT * vol / BREAKOUT_DAYS && c[end] <= MAX_ABOVE_MA20 * sma(c, end, 20);
    }

    /** 初始止损距离（比例）。 */
    public static double stopDistance(double atr, double price) {
        double pct = STOP_ATR * atr / price * 100;
        return Math.max(MIN_STOP_PCT, Math.min(MAX_STOP_PCT, pct)) / 100;
    }

    /** 当前止损线：max(初始止损, 最高价 − 3×买入时 ATR)。 */
    public static double stopLine(double initialStop, double peakHigh, double entryAtr) {
        return Math.max(initialStop, peakHigh - TRAIL_ATR * entryAtr);
    }

    /** 买入金额（U）：风险 200 U / 止损距离，上限 3,000 U。 */
    public static double notional(double stopDistance) {
        return Math.min(MAX_NOTIONAL, RISK_USDT / stopDistance);
    }

    /** 日线收盘是否跌破前 10 天最低价（end 是最后一根已收盘日线）。 */
    public static boolean belowExitLow(double[] l, double[] c, int end) {
        double ll = Double.MAX_VALUE;
        for (int i = end - EXIT_LOW_DAYS; i < end; i++) ll = Math.min(ll, l[i]);
        return c[end] < ll;
    }
}
