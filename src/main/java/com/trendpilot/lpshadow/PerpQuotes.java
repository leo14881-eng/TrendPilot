package com.trendpilot.lpshadow;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 永续合约（ETHUSDT、BTCUSDT）的标记价格和资金费率，每个合约缓存 60 秒：每个合约每分钟最多请求 Binance 一次
 * （失败也算一次，不会连续重试）。请求本身走 {@link com.trendpilot.market.BinanceClient} 的统一限速器，每次权重 1。
 */
final class PerpQuotes {

    record Quote(double mark, double fundingRate) {
    }

    static final long TTL_MS = 60_000;

    private final Function<String, Quote> fetch;
    private final Map<String, long[]> fetchedAt = new HashMap<>();
    private final Map<String, Quote> cached = new HashMap<>();

    PerpQuotes(Function<String, Quote> fetch) {
        this.fetch = fetch;
    }

    /** 测试用：所有合约都返回同一个报价来源。 */
    PerpQuotes(Supplier<Quote> fetch) {
        this((Function<String, Quote>) s -> fetch.get());
    }

    /** @return 最新报价；取不到（或 symbol 为空）返回 null（对冲类策略这一分钟记 gap）。 */
    synchronized Quote get(String symbol, long nowMs) {
        if (symbol == null || symbol.isBlank()) return null;
        long[] at = fetchedAt.computeIfAbsent(symbol, k -> new long[] {Long.MIN_VALUE / 2});
        if (nowMs - at[0] < TTL_MS - 1_000) return cached.get(symbol);
        at[0] = nowMs;
        Quote q;
        try {
            q = fetch.apply(symbol);
            q = q != null && q.mark() > 0 && !Double.isNaN(q.fundingRate()) ? q : null;
        } catch (RuntimeException e) {
            q = null;
        }
        if (q == null) cached.remove(symbol);
        else cached.put(symbol, q);
        return q;
    }
}
