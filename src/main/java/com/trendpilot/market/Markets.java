package com.trendpilot.market;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/** 已启用的交易所行情（Binance 主池 + Bybit 独立池），每个交易所各有自己的缓存。 */
@Component
public class Markets {

    private final Map<Venue, VenueMarket> byVenue = new EnumMap<>(Venue.class);
    private final KlineStore store;

    public Markets(List<MarketDataSource> sources, KlineStore store) {
        this.store = store;
        for (MarketDataSource s : sources) {
            if (byVenue.containsKey(s.venue())) {
                throw new IllegalStateException("重复的行情来源：" + s.venue());
            }
            byVenue.put(s.venue(), new VenueMarket(s.venue(), s, new SymbolDirectory(s), new DepthCache(s),
                    new BarsLoader(s, store)));
        }
    }

    /** 未启用时返回 null。 */
    public VenueMarket get(Venue venue) {
        return byVenue.get(venue);
    }

    public List<VenueMarket> all() {
        return List.copyOf(byVenue.values());
    }

    public void invalidate() {
        byVenue.values().forEach(VenueMarket::invalidate);
    }

    /** 清理过期的 K 线缓存。 */
    public void prune(Instant now) {
        store.prune(now);
    }
}
