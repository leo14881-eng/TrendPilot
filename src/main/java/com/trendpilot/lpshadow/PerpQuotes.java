package com.trendpilot.lpshadow;

import java.util.function.Supplier;

/**
 * ETHUSDT 永续的标记价格和资金费率，缓存 60 秒：每分钟最多请求 Binance 一次（失败也算一次，不会连续重试）。
 * 请求本身走 {@link com.trendpilot.market.BinanceClient} 的统一限速器。
 */
final class PerpQuotes {

    record Quote(double mark, double fundingRate) {
    }

    static final long TTL_MS = 60_000;

    private final Supplier<Quote> fetch;
    private long fetchedAtMs = Long.MIN_VALUE / 2;
    private Quote cached;

    PerpQuotes(Supplier<Quote> fetch) {
        this.fetch = fetch;
    }

    /** @return 最新报价；取不到返回 null（对冲类策略这一分钟记 gap）。 */
    synchronized Quote get(long nowMs) {
        if (nowMs - fetchedAtMs < TTL_MS - 1_000) return cached;
        fetchedAtMs = nowMs;
        try {
            Quote q = fetch.get();
            cached = q != null && q.mark() > 0 && !Double.isNaN(q.fundingRate()) ? q : null;
        } catch (RuntimeException e) {
            cached = null;
        }
        return cached;
    }
}
