package com.trendpilot.market;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按同一时间截面准备 K 线：每个标的只请求 15m K 线，并且只增量拉取缓存里还没有的部分；
 * 1h K 线由 4 根 15m 合成（与交易所 1h K 线口径一致）。
 * 缓存不连续、缺最新一根时整段重拉一次，仍不完整则放入 missing 名单，不伪装成成功。
 * WebSocket 采集器已经写入的 K 线不会重复请求。每个交易所一个实例（见 {@link Markets}）。
 */
public class BarsLoader {

    public static final long QUARTER_MS = 15 * 60_000L;
    public static final long HOUR_MS = 60 * 60_000L;
    /** 104 根 15m ≥ 24 根完整小时 + 余量。 */
    public static final int WINDOW = 104;
    static final int HOURLY_KEEP = 26;
    static final int QUARTER_KEEP = 22;

    private static final Logger log = LoggerFactory.getLogger(BarsLoader.class);

    private final MarketDataSource market;
    private final KlineStore store;

    public BarsLoader(MarketDataSource market, KlineStore store) {
        this.market = market;
        this.store = store;
    }

    public record Result(Map<String, SymbolBars> bars, List<String> missing, int requests) {

        public boolean complete() {
            return missing.isEmpty();
        }
    }

    public Result load(Collection<String> symbols, Instant asOf) {
        Map<String, SymbolBars> bars = new ConcurrentHashMap<>();
        List<String> missing = new ArrayList<>();
        int[] requests = {0};
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<Loaded>> futures = new LinkedHashMap<>();
            for (String s : symbols) {
                futures.put(s, pool.submit(() -> loadOne(s, asOf)));
            }
            for (var e : futures.entrySet()) {
                try {
                    Loaded l = e.getValue().get();
                    requests[0] += l.requests();
                    if (l.bars() == null) missing.add(e.getKey());
                    else bars.put(e.getKey(), l.bars());
                } catch (Exception ex) {
                    log.warn("K 线缺失 {}：{}", e.getKey(), ex.getMessage());
                    missing.add(e.getKey());
                }
            }
        }
        return new Result(Map.copyOf(bars), List.copyOf(missing), requests[0]);
    }

    private record Loaded(SymbolBars bars, int requests) {
    }

    private Loaded loadOne(String symbol, Instant asOf) {
        long lastOpen = lastClosedOpenTime(asOf);
        long windowStart = lastOpen - (WINDOW - 1) * QUARTER_MS;
        int requests = 0;

        Long cached = store.latestOpenTime(market.venue(), symbol);
        boolean full = cached == null || cached < windowStart;
        if (full) {
            fetch(symbol, null, WINDOW + 1, asOf);
            requests++;
        } else if (cached < lastOpen) {
            int need = (int) ((lastOpen - cached) / QUARTER_MS) + 1;
            fetch(symbol, cached + QUARTER_MS, Math.min(1000, need), asOf);
            requests++;
        }
        List<Kline> window = store.range(market.venue(), symbol, windowStart, lastOpen);
        if (!valid(window, lastOpen) && !full) {
            fetch(symbol, null, WINDOW + 1, asOf); // 缓存有缺口：整段重拉一次
            requests++;
            window = store.range(market.venue(), symbol, windowStart, lastOpen);
        }
        if (!valid(window, lastOpen)) {
            return new Loaded(null, requests);
        }
        List<Kline> hourly = toHourly(window);
        return new Loaded(new SymbolBars(symbol, tail(hourly, HOURLY_KEEP), tail(window, QUARTER_KEEP)), requests);
    }

    private void fetch(String symbol, Long startTime, int limit, Instant asOf) {
        long t = asOf.toEpochMilli();
        List<Kline> closed = market.klines(symbol, "15m", startTime, limit).stream()
                .filter(k -> k.closeTime() < t).toList();
        store.upsert(market.venue(), symbol, closed);
    }

    /** 截面之前最后一根已收盘 15m K 线的开盘时间。 */
    public static long lastClosedOpenTime(Instant asOf) {
        return Math.floorDiv(asOf.toEpochMilli(), QUARTER_MS) * QUARTER_MS - QUARTER_MS;
    }

    /** 窗口完整：根数够、连续、最后一根就是截面前最新收盘的那根。 */
    static boolean valid(List<Kline> window, long lastOpen) {
        if (window.size() < WINDOW || window.getLast().openTime() != lastOpen) {
            return false;
        }
        for (int i = 1; i < window.size(); i++) {
            if (window.get(i).openTime() - window.get(i - 1).openTime() != QUARTER_MS) return false;
        }
        return true;
    }

    /** 4 根 15m 合成 1 根 1h，只保留完整的小时。 */
    public static List<Kline> toHourly(List<Kline> quarters) {
        List<Kline> out = new ArrayList<>();
        int i = 0;
        while (i < quarters.size()) {
            Kline first = quarters.get(i);
            if (first.openTime() % HOUR_MS != 0 || i + 4 > quarters.size()) {
                i++;
                continue;
            }
            List<Kline> group = quarters.subList(i, i + 4);
            double high = group.stream().mapToDouble(Kline::high).max().orElseThrow();
            double low = group.stream().mapToDouble(Kline::low).min().orElseThrow();
            double qv = group.stream().mapToDouble(Kline::quoteVolume).sum();
            out.add(new Kline(first.openTime(), first.open(), high, low, group.getLast().close(), qv,
                    group.getLast().closeTime()));
            i += 4;
        }
        return out;
    }

    private static List<Kline> tail(List<Kline> list, int n) {
        return List.copyOf(list.subList(Math.max(0, list.size() - n), list.size()));
    }
}
