package com.trendpilot.market;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Binance 现货公开行情（只读，不涉及任何下单接口）。
 * 全局最多 maxConcurrency 个并发请求；429/5xx/网络错误有界重试 + 指数退避，418（封禁）立即失败。
 */
@Component
public class BinanceClient implements MarketDataSource {

    private static final Logger log = LoggerFactory.getLogger(BinanceClient.class);
    private static final int MAX_ATTEMPTS = 3;

    private final RestClient http;
    private final Semaphore permits;

    public BinanceClient(BinanceProperties props, RestClient.Builder builder) {
        this.http = builder.baseUrl(props.baseUrl()).build();
        this.permits = new Semaphore(Math.max(1, props.maxConcurrency()));
    }

    @Override
    public List<SymbolInfo> symbols() {
        JsonNode root = get(() -> http.get().uri("/api/v3/exchangeInfo?permissions=SPOT"), "exchangeInfo");
        List<SymbolInfo> out = new ArrayList<>();
        for (JsonNode s : root.path("symbols")) {
            out.add(toSymbol(s));
        }
        return out;
    }

    @Override
    public SymbolInfo symbol(String symbol) {
        try {
            JsonNode root = get(() -> http.get().uri("/api/v3/exchangeInfo?symbol={s}", symbol), "exchangeInfo " + symbol);
            JsonNode s = root.path("symbols").path(0);
            return s.isMissingNode() ? null : toSymbol(s);
        } catch (MarketDataException e) {
            if (e.getCause() instanceof HttpStatusCodeException h && h.getStatusCode().value() == 400) {
                return null; // 标的不存在（已下架）
            }
            throw e;
        }
    }

    @Override
    public List<Ticker24h> tickers24h() {
        JsonNode root = get(() -> http.get().uri("/api/v3/ticker/24hr"), "ticker24hr");
        List<Ticker24h> out = new ArrayList<>();
        for (JsonNode t : root) {
            out.add(new Ticker24h(t.path("symbol").asText(), t.path("lastPrice").asDouble(),
                    t.path("priceChangePercent").asDouble(), t.path("quoteVolume").asDouble()));
        }
        return out;
    }

    @Override
    public List<Kline> klines(String symbol, String interval, int limit) {
        JsonNode root = get(() -> http.get().uri("/api/v3/klines?symbol={s}&interval={i}&limit={l}", symbol, interval, limit),
                "klines " + symbol + " " + interval);
        List<Kline> out = new ArrayList<>(root.size());
        for (JsonNode k : root) {
            out.add(new Kline(k.get(0).asLong(), k.get(1).asDouble(), k.get(2).asDouble(), k.get(3).asDouble(),
                    k.get(4).asDouble(), k.get(7).asDouble(), k.get(6).asLong()));
        }
        return out;
    }

    @Override
    public OrderBook depth(String symbol, int limit) {
        JsonNode root = get(() -> http.get().uri("/api/v3/depth?symbol={s}&limit={l}", symbol, limit), "depth " + symbol);
        return new OrderBook(symbol, levels(root.path("bids")), levels(root.path("asks")), Instant.now());
    }

    private static SymbolInfo toSymbol(JsonNode s) {
        return new SymbolInfo(s.path("symbol").asText(), s.path("baseAsset").asText(), s.path("quoteAsset").asText(),
                s.path("status").asText(), s.path("isSpotTradingAllowed").asBoolean());
    }

    private static List<OrderBook.Level> levels(JsonNode arr) {
        List<OrderBook.Level> out = new ArrayList<>(arr.size());
        for (JsonNode l : arr) {
            out.add(new OrderBook.Level(new BigDecimal(l.get(0).asText()), new BigDecimal(l.get(1).asText())));
        }
        return out;
    }

    private JsonNode get(Supplier<RestClient.RequestHeadersSpec<?>> request, String what) {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                permits.acquire();
                try {
                    return request.get().retrieve().body(JsonNode.class);
                } finally {
                    permits.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MarketDataException("请求被中断：" + what, e);
            } catch (HttpStatusCodeException e) {
                HttpStatusCode code = e.getStatusCode();
                if (code.value() == 418 || (code.is4xxClientError() && code.value() != 429)) {
                    throw new MarketDataException("Binance 拒绝请求 " + code.value() + "：" + what, e);
                }
                last = e;
            } catch (ResourceAccessException e) {
                last = e;
            }
            if (attempt < MAX_ATTEMPTS) {
                log.warn("Binance 请求失败，第 {} 次重试：{}", attempt, what);
                sleep(Duration.ofMillis(500L << (attempt - 1)));
            }
        }
        throw new MarketDataException("Binance 请求多次失败：" + what, last);
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
