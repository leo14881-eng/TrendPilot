package com.trendpilot.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.trendpilot.market.Kline;
import com.trendpilot.market.MarketDataException;
import com.trendpilot.market.MarketDataSource;
import com.trendpilot.market.OrderBook;
import com.trendpilot.market.SymbolInfo;
import com.trendpilot.market.Ticker24h;
import com.trendpilot.market.Venue;

/** 可变的内存行情，用于端到端测试；统计各类请求次数。 */
public class FakeMarket implements MarketDataSource {

    private final Venue venue;

    public final Map<String, SymbolInfo> symbols = new ConcurrentHashMap<>();
    public final Map<String, Ticker24h> tickers = new ConcurrentHashMap<>();
    public final Map<String, List<Kline>> quarters = new ConcurrentHashMap<>();
    public final Map<String, OrderBook> books = new ConcurrentHashMap<>();
    public final AtomicInteger symbolRequests = new AtomicInteger();
    public final AtomicInteger klineRequests = new AtomicInteger();
    public final AtomicInteger depthRequests = new AtomicInteger();

    public FakeMarket() {
        this(Venue.BINANCE);
    }

    public FakeMarket(Venue venue) {
        this.venue = venue;
    }

    @Override
    public Venue venue() {
        return venue;
    }

    public void put(String symbol, double change24h, List<Kline> q, OrderBook book) {
        symbols.put(symbol, new SymbolInfo(symbol, symbol.replace("USDT", ""), "USDT", true, ""));
        tickers.put(symbol, new Ticker24h(symbol, q.getLast().close(), change24h, 10_000_000));
        quarters.put(symbol, q);
        if (book != null) books.put(symbol, book);
    }

    public void reset() {
        symbols.clear();
        tickers.clear();
        quarters.clear();
        books.clear();
        symbolRequests.set(0);
        klineRequests.set(0);
        depthRequests.set(0);
    }

    @Override
    public List<SymbolInfo> symbols() {
        symbolRequests.incrementAndGet();
        return new ArrayList<>(symbols.values());
    }

    @Override
    public List<Ticker24h> tickers24h() {
        return new ArrayList<>(tickers.values());
    }

    @Override
    public List<Kline> klines(String symbol, String interval, Long startTime, int limit) {
        klineRequests.incrementAndGet();
        List<Kline> q = quarters.get(symbol);
        if (q == null || !interval.equals("15m")) throw new MarketDataException("no bars " + symbol);
        if (startTime == null) {
            return q.subList(Math.max(0, q.size() - limit), q.size());
        }
        return q.stream().filter(k -> k.openTime() >= startTime).limit(limit).toList();
    }

    @Override
    public OrderBook depth(String symbol, int limit) {
        depthRequests.incrementAndGet();
        OrderBook b = books.get(symbol);
        if (b == null) throw new MarketDataException("no book " + symbol);
        return b;
    }
}
