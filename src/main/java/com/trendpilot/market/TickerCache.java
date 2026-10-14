package com.trendpilot.market;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * 全市场行情短时缓存：24h 行情 60 秒、最优买卖价 30 秒内复用，Discovery、V1、Missed Replay 共用一次请求。
 * 只缓存成功结果。每个交易所一个实例（见 {@link Markets}）。
 */
public class TickerCache {

    static final Duration TICKERS_TTL = Duration.ofSeconds(60);
    static final Duration QUOTES_TTL = Duration.ofSeconds(30);

    private final Slot<Ticker24h> tickers;
    private final Slot<BookTicker> quotes;

    public TickerCache(MarketDataSource market) {
        this(market, Clock.systemUTC());
    }

    TickerCache(MarketDataSource market, Clock clock) {
        this.tickers = new Slot<>(market::tickers24h, TICKERS_TTL, clock);
        this.quotes = new Slot<>(market::bookTickers, QUOTES_TTL, clock);
    }

    public List<Ticker24h> tickers24h() {
        return tickers.get();
    }

    public List<BookTicker> bookTickers() {
        return quotes.get();
    }

    public void invalidate() {
        tickers.clear();
        quotes.clear();
    }

    private static final class Slot<T> {
        private final Supplier<List<T>> fetch;
        private final Duration ttl;
        private final Clock clock;
        private List<T> value;
        private Instant at;

        Slot(Supplier<List<T>> fetch, Duration ttl, Clock clock) {
            this.fetch = fetch;
            this.ttl = ttl;
            this.clock = clock;
        }

        synchronized List<T> get() {
            Instant now = clock.instant();
            if (value == null || Duration.between(at, now).compareTo(ttl) > 0) {
                value = List.copyOf(fetch.get());
                at = now;
            }
            return value;
        }

        synchronized void clear() {
            value = null;
        }
    }
}
