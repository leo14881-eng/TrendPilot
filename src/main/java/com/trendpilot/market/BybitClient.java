package com.trendpilot.market;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Bybit 现货公开行情（v5，只读，不涉及任何下单接口）。
 * Bybit 是独立池：只用于 Binance 没有的标的，信号用 Bybit 自己的 BTC 基准。
 * 地区限制等 403 立即失败并记为缺数据，不无限重试。
 */
@Component
@ConditionalOnProperty(prefix = "trendpilot.bybit", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BybitClient implements MarketDataSource {

    static final int MAX_DEPTH = 200;
    /** 限流（10006）、系统繁忙（10016）可重试。 */
    private static final List<Integer> RETRYABLE = List.of(10006, 10016);

    private final RestClient http;
    private final PublicRest rest;

    public BybitClient(BybitProperties props, RestClient.Builder builder) {
        this.http = builder.baseUrl(props.baseUrl()).build();
        this.rest = new PublicRest("Bybit", props.maxConcurrency());
    }

    @Override
    public Venue venue() {
        return Venue.BYBIT;
    }

    @Override
    public List<SymbolInfo> symbols() {
        List<SymbolInfo> out = new ArrayList<>();
        String cursor = "";
        do {
            String c = cursor;
            JsonNode result = call(() -> http.get().uri("/v5/market/instruments-info?category=spot&cursor={c}", c),
                    "instruments-info");
            for (JsonNode s : result.path("list")) {
                String tags = String.join(",", s.path("symbolType").asText(""), s.path("stTag").asText(""));
                out.add(new SymbolInfo(s.path("symbol").asText(), s.path("baseCoin").asText(),
                        s.path("quoteCoin").asText(), "Trading".equals(s.path("status").asText()), tags));
            }
            cursor = result.path("nextPageCursor").asText("");
        } while (!cursor.isEmpty());
        return out;
    }

    @Override
    public List<Ticker24h> tickers24h() {
        JsonNode result = call(() -> http.get().uri("/v5/market/tickers?category=spot"), "tickers");
        List<Ticker24h> out = new ArrayList<>();
        for (JsonNode t : result.path("list")) {
            out.add(new Ticker24h(t.path("symbol").asText(), t.path("lastPrice").asDouble(),
                    t.path("price24hPcnt").asDouble() * 100, t.path("turnover24h").asDouble()));
        }
        return out;
    }

    /** 全市场最优买卖价（与 24h 行情同一个接口，一次请求）。 */
    @Override
    public List<BookTicker> bookTickers() {
        JsonNode result = call(() -> http.get().uri("/v5/market/tickers?category=spot"), "bookTickers");
        List<BookTicker> out = new ArrayList<>();
        for (JsonNode t : result.path("list")) {
            out.add(new BookTicker(t.path("symbol").asText(), t.path("bid1Price").asDouble(), t.path("ask1Price").asDouble()));
        }
        return out;
    }

    /** Bybit K 线按时间倒序返回、没有收盘时间字段；这里转成升序并补上收盘时间。 */
    @Override
    public List<Kline> klines(String symbol, String interval, Long startTime, int limit) {
        String iv = toBybitInterval(interval);
        long ms = intervalMs(interval);
        JsonNode result = startTime == null
                ? call(() -> http.get().uri("/v5/market/kline?category=spot&symbol={s}&interval={i}&limit={l}",
                        symbol, iv, limit), "kline " + symbol)
                : call(() -> http.get().uri("/v5/market/kline?category=spot&symbol={s}&interval={i}&start={t}&limit={l}",
                        symbol, iv, startTime, limit), "kline " + symbol);
        List<Kline> out = new ArrayList<>();
        for (JsonNode k : result.path("list")) {
            long open = k.get(0).asLong();
            out.add(new Kline(open, k.get(1).asDouble(), k.get(2).asDouble(), k.get(3).asDouble(), k.get(4).asDouble(),
                    k.get(6).asDouble(), open + ms - 1));
        }
        out.sort((a, b) -> Long.compare(a.openTime(), b.openTime()));
        return out;
    }

    @Override
    public OrderBook depth(String symbol, int limit) {
        int l = Math.min(limit, MAX_DEPTH);
        JsonNode result = call(() -> http.get().uri("/v5/market/orderbook?category=spot&symbol={s}&limit={l}", symbol, l),
                "orderbook " + symbol);
        return new OrderBook(symbol, levels(result.path("b")), levels(result.path("a")), Instant.now());
    }

    private JsonNode call(Supplier<RestClient.RequestHeadersSpec<?>> request, String what) {
        return rest.get(request, what, body -> {
            int code = body.path("retCode").asInt(-1);
            if (code == 0) return body.path("result");
            String msg = "Bybit retCode " + code + " " + body.path("retMsg").asText() + "：" + what;
            if (RETRYABLE.contains(code)) throw new PublicRest.RetryableException(msg);
            throw new MarketDataException(msg);
        });
    }

    private static List<OrderBook.Level> levels(JsonNode arr) {
        List<OrderBook.Level> out = new ArrayList<>(arr.size());
        for (JsonNode l : arr) {
            out.add(new OrderBook.Level(new BigDecimal(l.get(0).asText()), new BigDecimal(l.get(1).asText())));
        }
        return out;
    }

    static String toBybitInterval(String interval) {
        return switch (interval) {
            case "5m" -> "5";
            case "15m" -> "15";
            case "1h" -> "60";
            case "1d" -> "D";
            default -> throw new IllegalArgumentException("不支持的周期 " + interval);
        };
    }

    static long intervalMs(String interval) {
        return switch (interval) {
            case "5m" -> 5 * 60_000L;
            case "15m" -> 15 * 60_000L;
            case "1h" -> 60 * 60_000L;
            case "1d" -> 24 * 60 * 60_000L;
            default -> throw new IllegalArgumentException("不支持的周期 " + interval);
        };
    }
}
