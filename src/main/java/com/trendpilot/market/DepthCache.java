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

    /** 先取 100 档（Binance 权重 5）；不够覆盖 ±bandPct 或买盘总额不够 minBidNotional 时再取 500 档（权重 25）。 */
    public OrderBook getCovering(String symbol, double bandPct, double minBidNotional) {
        OrderBook b = get(symbol, SHALLOW);
        return covers(b, bandPct, minBidNotional) ? b : get(symbol, DEEP);
    }

    static final int SHALLOW = 100;
    static final int DEEP = 500;

    static boolean covers(OrderBook b, double bandPct, double minBidNotional) {
        if (b.bids().size() < SHALLOW && b.asks().size() < SHALLOW) return true; // 交易所只有这么多档
        if (!b.usable()) return false;
        double mid = b.mid().doubleValue();
        boolean bidSpan = b.bids().size() < SHALLOW || b.bids().getLast().price().doubleValue() <= mid * (1 - bandPct / 100);
        boolean askSpan = b.asks().size() < SHALLOW || b.asks().getLast().price().doubleValue() >= mid * (1 + bandPct / 100);
        double bidTotal = b.bids().stream().mapToDouble(l -> l.price().doubleValue() * l.qty().doubleValue()).sum();
        return bidSpan && askSpan && (b.bids().size() < SHALLOW || bidTotal >= minBidNotional);
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
