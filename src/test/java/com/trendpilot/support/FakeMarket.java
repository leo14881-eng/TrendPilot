package com.trendpilot.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.trendpilot.market.Kline;
import com.trendpilot.market.MarketDataException;
import com.trendpilot.market.MarketDataSource;
import com.trendpilot.market.OrderBook;
import com.trendpilot.market.SymbolBars;
import com.trendpilot.market.SymbolInfo;
import com.trendpilot.market.Ticker24h;

/** 可变的内存行情，用于端到端测试。 */
public class FakeMarket implements MarketDataSource {

    public final Map<String, SymbolInfo> symbols = new ConcurrentHashMap<>();
    public final Map<String, Ticker24h> tickers = new ConcurrentHashMap<>();
    public final Map<String, SymbolBars> bars = new ConcurrentHashMap<>();
    public final Map<String, OrderBook> books = new ConcurrentHashMap<>();

    public void put(String symbol, double change24h, SymbolBars b, OrderBook book) {
        symbols.put(symbol, new SymbolInfo(symbol, symbol.replace("USDT", ""), "USDT", "TRADING", true));
        tickers.put(symbol, new Ticker24h(symbol, b.hourly().getLast().close(), change24h, 10_000_000));
        bars.put(symbol, b);
        if (book != null) books.put(symbol, book);
    }

    @Override
    public List<SymbolInfo> symbols() {
        return new ArrayList<>(symbols.values());
    }

    @Override
    public SymbolInfo symbol(String symbol) {
        return symbols.get(symbol);
    }

    @Override
    public List<Ticker24h> tickers24h() {
        return new ArrayList<>(tickers.values());
    }

    @Override
    public List<Kline> klines(String symbol, String interval, int limit) {
        SymbolBars b = bars.get(symbol);
        if (b == null) throw new MarketDataException("no bars " + symbol);
        return interval.equals("1h") ? b.hourly() : b.quarter();
    }

    @Override
    public OrderBook depth(String symbol, int limit) {
        OrderBook b = books.get(symbol);
        if (b == null) throw new MarketDataException("no book " + symbol);
        return b;
    }

    public static Instant at(String iso) {
        return Instant.parse(iso);
    }
}
