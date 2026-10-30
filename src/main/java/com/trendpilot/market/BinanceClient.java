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
 * Binance 现货公开行情（只读，不涉及任何下单接口）。
 * 请求限流与重试见 {@link PublicRest}。
 */
@Component
@ConditionalOnProperty(prefix = "trendpilot.binance", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BinanceClient implements MarketDataSource {

    private final RestClient http;
    private final PublicRest rest;

    public BinanceClient(BinanceProperties props, RestClient.Builder builder) {
        this.http = builder.baseUrl(props.baseUrl()).build();
        this.rest = new PublicRest("Binance", props.maxConcurrency(), 50);
    }

    @Override
    public Venue venue() {
        return Venue.BINANCE;
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
    public List<Kline> klines(String symbol, String interval, Long startTime, int limit) {
        JsonNode root = startTime == null
                ? get(() -> http.get().uri("/api/v3/klines?symbol={s}&interval={i}&limit={l}", symbol, interval, limit),
                        "klines " + symbol + " " + interval)
                : get(() -> http.get().uri("/api/v3/klines?symbol={s}&interval={i}&startTime={t}&limit={l}", symbol,
                        interval, startTime, limit), "klines " + symbol + " " + interval);
        List<Kline> out = new ArrayList<>(root.size());
        for (JsonNode k : root) {
            out.add(new Kline(k.get(0).asLong(), k.get(1).asDouble(), k.get(2).asDouble(), k.get(3).asDouble(),
                    k.get(4).asDouble(), k.get(7).asDouble(), k.get(6).asLong()));
        }
        return out;
    }

    @Override
    public List<BookTicker> bookTickers() {
        JsonNode root = get(() -> http.get().uri("/api/v3/ticker/bookTicker"), "bookTicker");
        List<BookTicker> out = new ArrayList<>();
        for (JsonNode t : root) {
            out.add(new BookTicker(t.path("symbol").asText(), t.path("bidPrice").asDouble(), t.path("askPrice").asDouble()));
        }
        return out;
    }

    @Override
    public OrderBook depth(String symbol, int limit) {
        JsonNode root = get(() -> http.get().uri("/api/v3/depth?symbol={s}&limit={l}", symbol, limit), "depth " + symbol);
        return new OrderBook(symbol, levels(root.path("bids")), levels(root.path("asks")), Instant.now());
    }

    /** USDⓈ-M 永续的标记价格和资金费率（lastFundingRate = 下一次结算要用的费率，小数，如 0.0001 = 0.01%）。 */
    public record PremiumIndex(String symbol, double markPrice, double lastFundingRate, long nextFundingTime) {
    }

    /**
     * 永续合约公开行情 premiumIndex（只读），和现货请求共用同一个 Binance 限速器（429 / Retry-After 处理相同）。
     * futuresBaseUrl 例如 https://fapi.binance.com。
     */
    public PremiumIndex premiumIndex(String futuresBaseUrl, String symbol) {
        java.net.URI uri = java.net.URI.create(futuresBaseUrl + "/fapi/v1/premiumIndex?symbol=" + symbol);
        JsonNode n = get(() -> http.get().uri(uri), "premiumIndex " + symbol);
        double mark = n.path("markPrice").asDouble(Double.NaN);
        if (!(mark > 0)) throw new MarketDataException("premiumIndex 没有标记价格：" + symbol);
        return new PremiumIndex(symbol, mark, n.path("lastFundingRate").asDouble(Double.NaN), n.path("nextFundingTime").asLong(0));
    }

    private static SymbolInfo toSymbol(JsonNode s) {
        return new SymbolInfo(s.path("symbol").asText(), s.path("baseAsset").asText(), s.path("quoteAsset").asText(),
                "TRADING".equals(s.path("status").asText()) && s.path("isSpotTradingAllowed").asBoolean(), "");
    }

    private static List<OrderBook.Level> levels(JsonNode arr) {
        List<OrderBook.Level> out = new ArrayList<>(arr.size());
        for (JsonNode l : arr) {
            out.add(new OrderBook.Level(new BigDecimal(l.get(0).asText()), new BigDecimal(l.get(1).asText())));
        }
        return out;
    }

    private JsonNode get(Supplier<RestClient.RequestHeadersSpec<?>> request, String what) {
        return rest.get(request, what);
    }
}
