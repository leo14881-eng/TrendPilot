package com.trendpilot.market;

public record Ticker24h(String symbol, double lastPrice, double priceChangePct, double quoteVolume) {
}
