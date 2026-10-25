package com.trendpilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trendpilot.job.DiscoveryJob;
import com.trendpilot.market.Markets;
import com.trendpilot.market.Venue;
import com.trendpilot.portfolio.ShadowBook;
import com.trendpilot.research.CoinGeckoClient;
import com.trendpilot.research.ResearchService;
import com.trendpilot.support.FakeMarket;
import com.trendpilot.support.TestData;
import com.trendpilot.variant.VariantRegistry;

/** 涨幅榜动量影子变体：按 24h 涨幅取前 N 名，有新鲜盘口、深度够才买；缺盘口不买；不影响 V2、不发开仓消息。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class MomentumVariantTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
        r.add("trendpilot.telegram.bot-token", () -> "");
        r.add("trendpilot.telegram.chat-id", () -> "");
        r.add("trendpilot.variants.list[0].name", () -> "V6");
        r.add("trendpilot.variants.list[0].overrides.momentum-top", () -> "2");
        r.add("trendpilot.variants.list[0].overrides.max-open", () -> "10");
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeMarket fakeMarket() {
            return new FakeMarket();
        }

        @Bean
        FakeMarket bybitMarket() {
            return new FakeMarket(Venue.BYBIT);
        }
    }

    @Autowired FakeMarket market;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("bybitMarket") FakeMarket bybit;
    @Autowired DiscoveryJob discovery;
    @Autowired ShadowBook v2;
    @Autowired VariantRegistry registry;
    @Autowired JdbcClient jdbc;
    @Autowired Markets markets;
    @Autowired ResearchService research;

    @BeforeEach
    void reset() {
        market.reset();
        bybit.reset();
        markets.invalidate();
        Instant fresh = Instant.parse("2026-12-31T00:00:00Z");
        List<CoinGeckoClient.ExchangeTicker> listed = java.util.stream.Stream.of("ABC", "AAA", "BBB", "CCC", "STKX")
                .map(b -> new CoinGeckoClient.ExchangeTicker(b, "USDT", b.toLowerCase() + "-coin")).toList();
        research.saveIdentity(Venue.BINANCE, listed, fresh);
        research.saveIdentity(Venue.BYBIT, listed, fresh);
        research.saveCategory("tokenized-stock", List.of("stkx-coin"), fresh);
    }

    @Test
    void buysTopGainersWithUsableBooksOnly() {
        Instant t0 = Instant.parse("2026-10-14T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        market.put("AAAUSDT", 12.0, TestData.flatQuarters(t0, 10), TestData.book("AAAUSDT", 10, 2, 5000, t0)); // 第 1 名，盘口够
        market.put("BBBUSDT", 9.0, TestData.flatQuarters(t0, 10), null);                                       // 第 2 名，没盘口
        market.put("CCCUSDT", 1.0, TestData.flatQuarters(t0, 10), TestData.book("CCCUSDT", 10, 2, 5000, t0)); // 不在前 2
        market.put("ABCUSDT", 0.5, TestData.flatQuarters(t0, 104), TestData.book("ABCUSDT", 104, 2, 100, t0));

        discovery.runOnce(t0);
        var book = registry.get("V6").orElseThrow().book();
        assertThat(book.positions()).extracting(p -> p.symbol()).containsExactly("AAAUSDT");
        assertThat(book.position("AAAUSDT").orElseThrow().costBasis().doubleValue()).isBetween(1000.0, 1005.0);
        assertThat(v2.positions()).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE event_key LIKE 'entry:%'").query(Integer.class).single())
                .isZero();
        // 再跑一轮不重复买
        discovery.runOnce(t0);
        assertThat(book.positions()).hasSize(1);
    }
}
