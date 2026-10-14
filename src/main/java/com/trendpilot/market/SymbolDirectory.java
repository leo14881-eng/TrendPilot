package com.trendpilot.market;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 * 交易对信息缓存：整表一次请求，缓存 10 分钟，Discovery 和 Monitor 共用。
 * 不在表里的标的（已下架）返回 null。每个交易所一个实例（见 {@link Markets}）。
 */
public class SymbolDirectory {

    static final Duration TTL = Duration.ofMinutes(10);

    private final MarketDataSource market;
    private final Clock clock;
    private volatile Snapshot cached;

    private record Snapshot(List<SymbolInfo> all, Map<String, SymbolInfo> bySymbol, Instant fetchedAt) {
    }

    public SymbolDirectory(MarketDataSource market) {
        this(market, Clock.systemUTC());
    }

    SymbolDirectory(MarketDataSource market, Clock clock) {
        this.market = market;
        this.clock = clock;
    }

    public List<SymbolInfo> all() {
        return snapshot().all();
    }

    public SymbolInfo get(String symbol) {
        return snapshot().bySymbol().get(symbol);
    }

    /** 丢弃缓存，下次使用时重新请求。 */
    public synchronized void invalidate() {
        cached = null;
    }

    private synchronized Snapshot snapshot() {
        Instant now = clock.instant();
        if (cached == null || Duration.between(cached.fetchedAt(), now).compareTo(TTL) > 0) {
            List<SymbolInfo> all = List.copyOf(market.symbols());
            cached = new Snapshot(all, all.stream().collect(Collectors.toMap(SymbolInfo::symbol, Function.identity(),
                    (a, b) -> a)), now);
        }
        return cached;
    }
}
