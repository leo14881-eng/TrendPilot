package com.trendpilot.market;

/** 一根 K 线。价格与成交额用 double：只用于信号计算，不进账本。 */
public record Kline(long openTime, double open, double high, double low, double close,
                    double quoteVolume, long closeTime) {

    /** (最高 − 最低) / 开盘，百分比。 */
    public double rangePct() {
        return open > 0 ? (high - low) / open * 100 : 0;
    }
}
