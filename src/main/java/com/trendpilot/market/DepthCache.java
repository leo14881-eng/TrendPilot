package com.trendpilot.market;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 盘口短时缓存：同一标的 15 秒内重复需要时复用，不重复请求。
 * 只缓存成功结果；fetchedAt 保留原始抓取时间，审核按它判断新鲜度。每个交易所一个实例（见 {@link Markets}）。
 */
public class DepthCache {

    static final Duration TTL = Duration.ofSeconds(15);

    private final MarketDataSource market;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    private record Entry(OrderBook book, Instant cachedAt) {
    }

    public DepthCache(MarketDataSource market) {
        this.market = market;
    }

    /** 丢弃缓存，下次使用时重新请求。 */
    public void invalidate() {
        cache.clear();
    }

    /** 丢弃某个标的的缓存（需要立即用最新盘口确认时）。 */
    public void invalidate(String symbol) {
        cache.keySet().removeIf(k -> k.startsWith(symbol + "/"));
    }

    public OrderBook get(String symbol, int limit) {
        String key = symbol + "/" + limit;
        Instant now = Instant.now();
        Entry hit = cache.get(key);
        if (hit != null && Duration.between(hit.cachedAt(), now).compareTo(TTL) <= 0) {
            return hit.book();
        }
        OrderBook fresh = market.depth(symbol, limit);
        cache.put(key, new Entry(fresh, now));
        return fresh;
    }
}
