package com.trendpilot.market;

import java.util.List;

/**
 * 行情来源。实现必须在失败时抛 {@link MarketDataException}，绝不返回伪造数据。
 */
public interface MarketDataSource {

    List<SymbolInfo> symbols();

    /** 单个标的的交易状态，查不到返回 null。 */
    SymbolInfo symbol(String symbol);

    List<Ticker24h> tickers24h();

    /** 返回最近 limit 根 K 线，可能包含尚未收盘的最后一根，由调用方过滤。 */
    List<Kline> klines(String symbol, String interval, int limit);

    OrderBook depth(String symbol, int limit);
}
