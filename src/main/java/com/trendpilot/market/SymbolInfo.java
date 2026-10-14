package com.trendpilot.market;

/**
 * 交易对信息。trading 表示正在交易且允许现货；tags 是交易所给的分类标记（如 Bybit 的代币化股票），用于排除。
 */
public record SymbolInfo(String symbol, String baseAsset, String quoteAsset, boolean trading, String tags) {
}
