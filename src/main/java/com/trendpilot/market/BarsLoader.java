package com.trendpilot.market;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 按同一时间截面并发拉取 1h / 15m K 线，只保留截面之前已收盘的 K 线。
 * 拉取失败或数据过期的标的放入 missing 名单，不伪装成成功。
 */
@Component
public class BarsLoader {

    public static final int HOURLY_LIMIT = 26;
    public static final int QUARTER_LIMIT = 22;
    private static final long HOURLY_MAX_AGE_MS = 65 * 60_000L;
    private static final long QUARTER_MAX_AGE_MS = 20 * 60_000L;

    private static final Logger log = LoggerFactory.getLogger(BarsLoader.class);

    private final MarketDataSource market;

    public BarsLoader(MarketDataSource market) {
        this.market = market;
    }

    public record Result(Map<String, SymbolBars> bars, List<String> missing) {

        public boolean complete() {
            return missing.isEmpty();
        }
    }

    public Result load(Collection<String> symbols, Instant asOf) {
        Map<String, SymbolBars> bars = new ConcurrentHashMap<>();
        List<String> missing = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<SymbolBars>> futures = new java.util.LinkedHashMap<>();
            for (String s : symbols) {
                futures.put(s, pool.submit(() -> loadOne(s, asOf)));
            }
            for (var e : futures.entrySet()) {
                try {
                    SymbolBars b = e.getValue().get();
                    if (b == null) {
                        missing.add(e.getKey());
                    } else {
                        bars.put(e.getKey(), b);
                    }
                } catch (Exception ex) {
                    log.warn("K 线缺失 {}：{}", e.getKey(), ex.getMessage());
                    missing.add(e.getKey());
                }
            }
        }
        return new Result(Map.copyOf(bars), List.copyOf(missing));
    }

    private SymbolBars loadOne(String symbol, Instant asOf) {
        List<Kline> hourly = closed(market.klines(symbol, "1h", HOURLY_LIMIT), asOf);
        List<Kline> quarter = closed(market.klines(symbol, "15m", QUARTER_LIMIT), asOf);
        long now = asOf.toEpochMilli();
        if (hourly.isEmpty() || quarter.isEmpty()
                || now - hourly.getLast().closeTime() > HOURLY_MAX_AGE_MS
                || now - quarter.getLast().closeTime() > QUARTER_MAX_AGE_MS) {
            return null;
        }
        return new SymbolBars(symbol, hourly, quarter);
    }

    static List<Kline> closed(List<Kline> klines, Instant asOf) {
        long t = asOf.toEpochMilli();
        return klines.stream().filter(k -> k.closeTime() < t).toList();
    }
}
