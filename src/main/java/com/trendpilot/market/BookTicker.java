package com.trendpilot.market;

/** 最优买卖价（全市场批量行情中的一行）。 */
public record BookTicker(String symbol, double bid, double ask) {

    public double spreadBps() {
        double mid = (bid + ask) / 2;
        return mid <= 0 ? Double.NaN : (ask - bid) / mid * 10_000;
    }
}
