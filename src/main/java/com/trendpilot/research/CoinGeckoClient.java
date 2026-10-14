package com.trendpilot.research;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * CoinGecko 公开接口（只读，免费档）。全局排队限速、429 退避见 {@link #get}；
 * 有可选的免费 Demo key 时带上 x-cg-demo-api-key 并用更短的间隔。
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

    static final int MAX_ATTEMPTS = 4;

    private final RestClient http;
    private final ResearchProperties props;
    private final long interval;
    private final java.util.function.LongSupplier clock;
    private final Sleeper sleeper;
    private long nextAllowedMs;
    private volatile long rateLimitedUntilMs;

    @org.springframework.beans.factory.annotation.Autowired
    public CoinGeckoClient(ResearchProperties props, RestClient.Builder builder) {
        this(props, builder, System::currentTimeMillis, Thread::sleep);
    }

    CoinGeckoClient(ResearchProperties props, RestClient.Builder builder, java.util.function.LongSupplier clock,
                    Sleeper sleeper) {
        this.props = props;
        this.clock = clock;
        this.sleeper = sleeper;
        boolean keyed = props.coingeckoApiKey() != null && !props.coingeckoApiKey().isBlank();
        this.interval = keyed ? props.demoMinIntervalMs() : props.minIntervalMs();
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

    /**
     * 全局排队限速：所有请求串行，相邻两次至少间隔 interval（无 key 默认 12 秒，约每分钟 5 次；有 Demo key 2.5 秒）。
     * 429 时读 Retry-After，没有就指数退避（60s、120s、240s…，封顶 maxBackoffMs），退避期间所有请求一起等待；
     * 5xx 和网络错误按 10s、20s、40s 退避。重试过程只记 INFO，最终失败才抛出，由调用方记一次 WARN。
     */
    private synchronized JsonNode get(java.util.function.Supplier<JsonNode> call) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            waitTurn();
            try {
                JsonNode n = call.get();
                if (n == null) throw new ResearchDataException("CoinGecko 返回空响应");
                nextAllowedMs = clock.getAsLong() + interval;
                rateLimitedUntilMs = 0;
                return n;
            } catch (HttpClientErrorException.TooManyRequests e) {
                last = e;
                long delay = retryAfterMs(e).orElse(Math.min(props.maxBackoffMs(), 60_000L << (attempt - 1)));
                rateLimitedUntilMs = clock.getAsLong() + delay;
                nextAllowedMs = rateLimitedUntilMs;
                log.info("CoinGecko 限流（429，第 {} 次），{} 秒后重试", attempt, delay / 1000);
            } catch (RestClientException e) {
                last = e;
                long delay = Math.min(props.maxBackoffMs(), 10_000L << (attempt - 1));
                nextAllowedMs = clock.getAsLong() + delay;
                log.info("CoinGecko 请求失败（第 {} 次），{} 秒后重试：{}", attempt, delay / 1000, e.getMessage());
            }
        }
        if (last instanceof HttpClientErrorException.TooManyRequests) {
            throw new RateLimited("CoinGecko 限流，" + MAX_ATTEMPTS + " 次重试后仍被拒绝", last);
        }
        throw new ResearchDataException("CoinGecko 请求多次失败：" + (last == null ? "" : last.getMessage()), last);
    }

    /** 当前是否处在 429 退避期（供研究缺口和状态展示）。 */
    public boolean rateLimited() {
        return clock.getAsLong() < rateLimitedUntilMs;
    }

    static java.util.Optional<Long> retryAfterMs(HttpClientErrorException e) {
        var headers = e.getResponseHeaders();
        String v = headers == null ? null : headers.getFirst("Retry-After");
        if (v == null || v.isBlank()) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(Math.max(1, Long.parseLong(v.trim())) * 1000L);
        } catch (NumberFormatException ex) {
            return java.util.Optional.empty();
        }
    }

    private void waitTurn() {
        long wait = nextAllowedMs - clock.getAsLong();
        if (wait <= 0) return;
        try {
            sleeper.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResearchDataException("请求被中断", e);
        }
    }

    /** 被 429 限流且重试用尽。调用方应停止本轮其余 CoinGecko 请求。 */
    public static class RateLimited extends ResearchDataException {
        RateLimited(String message, Throwable cause) {
            super(message, cause);
        }
    }

    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }
}
