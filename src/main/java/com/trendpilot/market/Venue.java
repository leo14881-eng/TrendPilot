package com.trendpilot.market;

/** 行情来源交易所。Binance 是主池；Bybit 是独立池，只覆盖 Binance 没有的标的。 */
public enum Venue {
    BINANCE,
    BYBIT
}
