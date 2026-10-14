package com.trendpilot.research;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * CoinGecko 公开接口（只读）。请求之间至少间隔 minIntervalMs，避免免费额度被限流；
 * 有 Demo key 时带上 x-cg-demo-api-key。
 */
@Component
public class CoinGeckoClient {

    private static final Logger log = LoggerFactory.getLogger(CoinGeckoClient.class);

    /** 交易所在某交易所上市的一个交易对。 */
    public record ExchangeTicker(String base, String target, String coinId) {
    }

    /** 市值与供给。字段缺失时为 NaN。 */
    public record Market(String coinId, String symbol, double marketCap, double fdv, double circulating,
                         double total, double max) {
    }

    private final RestClient http;
    private final ResearchProperties props;
    private long lastCallMs;

    public CoinGeckoClient(ResearchProperties props, RestClient.Builder builder) {
        this.props = props;
        RestClient.Builder b = builder.clone().baseUrl(props.coingeckoBaseUrl());
        if (props.coingeckoApiKey() != null && !props.coingeckoApiKey().isBlank()) {
            b.defaultHeader("x-cg-demo-api-key", props.coingeckoApiKey());
        }
        this.http = b.build();
    }

    /** 某交易所的全部交易对（分页，每页 100 个）。exchangeId：binance / bybit_spot。 */
    public List<ExchangeTicker> exchangeTickers(String exchangeId) {
        List<ExchangeTicker> out = new ArrayList<>();
        for (int page = 1; page <= 60; page++) {
            int p = page;
            JsonNode root = get(() -> http.get().uri("/exchanges/{id}/tickers?page={p}", exchangeId, p)
                    .retrieve().body(JsonNode.class));
            JsonNode tickers = root.path("tickers");
            if (tickers.isEmpty()) break;
            for (JsonNode t : tickers) {
                out.add(new ExchangeTicker(t.path("base").asText(), t.path("target").asText(), t.path("coin_id").asText()));
            }
        }
        return out;
    }

    /** 批量市值与供给，每次最多 250 个。 */
    public List<Market> markets(Collection<String> coinIds) {
        List<String> ids = List.copyOf(coinIds);
        List<Market> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += 250) {
            String batch = String.join(",", ids.subList(i, Math.min(ids.size(), i + 250)));
            JsonNode root = get(() -> http.get()
                    .uri("/coins/markets?vs_currency=usd&per_page=250&ids={ids}", batch).retrieve().body(JsonNode.class));
            for (JsonNode m : root) {
                out.add(new Market(m.path("id").asText(), m.path("symbol").asText(), num(m, "market_cap"),
                        num(m, "fully_diluted_valuation"), num(m, "circulating_supply"), num(m, "total_supply"),
                        num(m, "max_supply")));
            }
        }
        return out;
    }

    /** 某个分类下的全部项目 id（分页，每页 250 个）。 */
    public List<String> categoryCoinIds(String category) {
        List<String> out = new ArrayList<>();
        for (int page = 1; page <= 20; page++) {
            int p = page;
            JsonNode root = get(() -> http.get()
                    .uri("/coins/markets?vs_currency=usd&per_page=250&page={p}&category={c}", p, category)
                    .retrieve().body(JsonNode.class));
            if (root.isEmpty()) break;
            root.forEach(m -> out.add(m.path("id").asText()));
            if (root.size() < 250) break;
        }
        return out;
    }

    private static double num(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNumber() ? v.asDouble() : Double.NaN;
    }

    /** 带节流与有界重试的请求；429 时等待更久。 */
    private synchronized JsonNode get(java.util.function.Supplier<JsonNode> call) {
        RestClientException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            pace(attempt == 1 ? props.minIntervalMs() : props.minIntervalMs() * 4L * attempt);
            try {
                JsonNode n = call.get();
                if (n == null) throw new ResearchDataException("CoinGecko 返回空响应");
                return n;
            } catch (RestClientException e) {
                last = e;
                log.warn("CoinGecko 请求失败（第 {} 次）：{}", attempt, e.getMessage());
            }
        }
        throw new ResearchDataException("CoinGecko 请求多次失败", last);
    }

    private void pace(long gapMs) {
        long wait = lastCallMs + gapMs - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResearchDataException("请求被中断", e);
            }
        }
        lastCallMs = System.currentTimeMillis();
    }
}
