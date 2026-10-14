package com.trendpilot.market;

import java.util.List;

/**
 * 行情来源。实现必须在失败时抛 {@link MarketDataException}，绝不返回伪造数据。
 * 调用方应通过 {@link SymbolDirectory}、{@link KlineStore}、{@link DepthCache} 使用缓存，避免频繁请求。
 */
public interface MarketDataSource {

    /** 全部现货交易对（一次请求）。 */
    List<SymbolInfo> symbols();

    /** 全市场 24h 行情（一次请求）。 */
    List<Ticker24h> tickers24h();

    /**
     * K 线。startTime 为 null 时返回最近 limit 根；否则返回 openTime ≥ startTime 的最多 limit 根。
     * 可能包含尚未收盘的最后一根，由调用方过滤。
     */
    List<Kline> klines(String symbol, String interval, Long startTime, int limit);

    OrderBook depth(String symbol, int limit);
}
