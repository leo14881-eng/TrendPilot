package com.trendpilot.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.trendpilot.market.Ticker24h;
import com.trendpilot.market.Venue;
import com.trendpilot.support.TestData;

/** 研究：CoinGecko 解析、身份核验、供给与价值捕获标签、缺口。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ResearchTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
    }

    @Autowired ResearchService research;
    @Autowired ResearchProperties props;

    private final Instant now = Instant.parse("2026-10-14T10:00:00Z");

    @Test
    void coinGeckoPaginatesExchangeTickersAndParsesMarkets() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var fastProps = new ResearchProperties(true, "https://cg.test", "", "https://llama.test", "https://llama-data.test", 25, 10, 10, 0, 0, 1000, 0.3, List.of());
        CoinGeckoClient client = new CoinGeckoClient(fastProps, builder);
        server.expect(requestTo("https://cg.test/exchanges/binance/tickers?page=1")).andRespond(withSuccess("""
                {"tickers":[{"base":"ABC","target":"USDT","coin_id":"abc"},{"base":"ABC","target":"BTC","coin_id":"abc"}]}""",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://cg.test/exchanges/binance/tickers?page=2")).andRespond(withSuccess(
                "{\"tickers\":[]}", MediaType.APPLICATION_JSON));
        assertThat(client.exchangeTickers("binance")).hasSize(2);

        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://cg.test/coins/markets"))).andRespond(withSuccess("""
                [{"id":"abc","symbol":"abc","market_cap":1000000,"fully_diluted_valuation":5000000,
                  "circulating_supply":20,"total_supply":100,"max_supply":null}]""", MediaType.APPLICATION_JSON));
        CoinGeckoClient.Market m = client.markets(List.of("abc")).getFirst();
        assertThat(m.circulating()).isEqualTo(20);
        assertThat(m.max()).isNaN();
    }

    @Test
    void identitySupplyAndValueCapture() {
        research.saveIdentity(Venue.BINANCE, List.of(
                new CoinGeckoClient.ExchangeTicker("ABC", "USDT", "abc"),
                new CoinGeckoClient.ExchangeTicker("DUP", "USDT", "dup-one"),
                new CoinGeckoClient.ExchangeTicker("DUP", "USDT", "dup-two")), now);
        research.saveMarkets(List.of(new CoinGeckoClient.Market("abc", "abc", 1e6, 5e6, 20, 100, Double.NaN)), now);
        research.saveProtocols(Map.of("abc", new DefiLlamaClient.Protocol("abc", "ABC Protocol", "Dexs", 5e6, 1000, 300)), now);

        ResearchRecord abc = research.research(Venue.BINANCE, "ABCUSDT", new Ticker24h("ABCUSDT", 1, 25, 1e6), now);
        assertThat(abc.identity()).isEqualTo(Identity.VERIFIED);
        assertThat(abc.circulatingRatio()).isEqualTo(0.2);
        assertThat(abc.labels()).contains("LOW_FLOAT", "HAS_REVENUE", "MOVE_24H_GE_20");
        assertThat(abc.gaps()).containsExactly("UNLOCK_SCHEDULE_NOT_COVERED");
        assertThat(abc.capitalReady()).isFalse();

        assertThat(research.research(Venue.BINANCE, "DUPUSDT", null, now).identity()).isEqualTo(Identity.AMBIGUOUS);
        assertThat(research.research(Venue.BINANCE, "NONEUSDT", null, now).identity()).isEqualTo(Identity.UNVERIFIED);
        // Bybit 的身份资料没加载过
        assertThat(research.research(Venue.BYBIT, "ABCUSDT", null, now).identity()).isEqualTo(Identity.DATA_UNAVAILABLE);
        // 资料超过 3 天没刷新也不能用
        assertThat(research.research(Venue.BINANCE, "ABCUSDT", null, now.plusSeconds(4 * 86400)).identity())
                .isEqualTo(Identity.DATA_UNAVAILABLE);
    }

    @Test
    void categoryExclusionMapsToSymbols() {
        research.saveIdentity(Venue.BYBIT, List.of(new CoinGeckoClient.ExchangeTicker("LLYB", "USDT", "lly-token"),
                new CoinGeckoClient.ExchangeTicker("OK", "USDT", "ok")), now);
        research.saveCategory("tokenized-stock", List.of("lly-token"), now);
        assertThat(research.excludedSymbols(Venue.BYBIT)).containsExactly("LLYBUSDT");
    }

    @Test
    void rateLimitHonoursRetryAfterThenGivesUpWithoutHammering() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var p = new ResearchProperties(true, "https://cg.test", "", "https://llama.test", "https://llama-data.test", 25, 10, 10, 12_000, 2_500, 600_000, 0.3, List.of());
        long[] clock = {0};
        List<Long> waits = new java.util.ArrayList<>();
        CoinGeckoClient client = new CoinGeckoClient(p, builder, () -> clock[0], ms -> { waits.add(ms); clock[0] += ms; });
        org.springframework.http.HttpHeaders retry = new org.springframework.http.HttpHeaders();
        retry.add("Retry-After", "30");
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://cg.test/coins/markets")))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS).headers(retry));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://cg.test/coins/markets")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThat(client.markets(List.of("abc"))).isEmpty();
        assertThat(waits).containsExactly(30_000L);

        // 第二次请求要等满 12 秒间隔；之后全部 429 且无 Retry-After：60s、120s、240s 指数退避，第 4 次后放弃
        server.reset();
        waits.clear();
        for (int i = 0; i < CoinGeckoClient.MAX_ATTEMPTS; i++) {
            server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://cg.test/coins/markets")))
                    .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.markets(List.of("abc")))
                .isInstanceOf(CoinGeckoClient.RateLimited.class);
        assertThat(waits).containsExactly(12_000L, 60_000L, 120_000L, 240_000L);
        assertThat(client.rateLimited()).isTrue();
        server.verify();
    }

    @Test
    void unlockScheduleFromDefiLlamaDataset() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        long now = this.now.getEpochSecond();
        var entry = mapper.readTree("""
                {"gecko_id":"abc","circSupply":1000,"maxSupply":2000,
                 "unlockEvents":[
                   {"timestamp":%d,"summary":{"totalTokensCliff":500,"totalNewWeeklyRate":7}},
                   {"timestamp":%d,"summary":{"totalTokensCliff":40}},
                   {"timestamp":%d,"summary":{"totalTokensCliff":999}}],
                 "nextEvent":{"date":%d,"toUnlock":40}}""".formatted(now - 86400 * 100, now + 86400 * 3,
                now + 86400 * 60, now + 86400 * 3));
        DefiLlamaClient.Unlock u = DefiLlamaClient.parseUnlock(entry, this.now);
        // 30 天内：一次性 40 + 线性 7/周 × 30/7 = 30 → 70 / 1000 = 7%；60 天后的 999 和历史 500 不算
        assertThat(u.unlock30dPct()).isEqualTo(7.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(u.nextUnlockPct()).isEqualTo(4.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(DefiLlamaClient.parseUnlock(mapper.readTree("{\"gecko_id\":null,\"circSupply\":5}"), this.now)).isNull();

        research.saveIdentity(Venue.BINANCE, List.of(new CoinGeckoClient.ExchangeTicker("ABC", "USDT", "abc"),
                new CoinGeckoClient.ExchangeTicker("XYZ", "USDT", "xyz")), this.now);
        research.saveUnlocks(java.util.Map.of("abc", u), this.now);
        ResearchRecord abc = research.research(Venue.BINANCE, "ABCUSDT", null, this.now);
        assertThat(abc.labels()).contains("HEAVY_UNLOCK_30D", "UNLOCK_CLIFF_7D");
        assertThat(abc.gaps()).doesNotContain("UNLOCK_SCHEDULE_NOT_COVERED");
        // DefiLlama 没收录的币仍记缺口
        assertThat(research.research(Venue.BINANCE, "XYZUSDT", null, this.now).gaps()).contains("UNLOCK_SCHEDULE_NOT_COVERED");
    }
}
