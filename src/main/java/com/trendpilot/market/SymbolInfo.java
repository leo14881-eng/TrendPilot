package com.trendpilot.market;

public record SymbolInfo(String symbol, String baseAsset, String quoteAsset, String status,
                         boolean spotTradingAllowed) {

    public boolean trading() {
        return "TRADING".equals(status) && spotTradingAllowed;
    }
}
