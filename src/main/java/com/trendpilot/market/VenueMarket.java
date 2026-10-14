package com.trendpilot.market;

/** 一个交易所的行情入口：请求都经过这里的缓存。 */
public record VenueMarket(Venue venue, MarketDataSource source, SymbolDirectory directory, DepthCache depth,
                          BarsLoader bars, TickerCache tickers) {

    /** 该交易所的 BTC 基准。Bybit 独立池只用 Bybit 自己的 BTC，缺失时不能拿 Binance 顶替。 */
    public static final String BTC = "BTCUSDT";

    public void invalidate() {
        directory.invalidate();
        depth.invalidate();
        tickers.invalidate();
    }
}
