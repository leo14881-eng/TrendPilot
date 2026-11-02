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

/** V6B: the V6 rule with Bybit's own universe and order books; up to 4 active shadow variants. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class BybitMomentumVariantTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
        r.add("trendpilot.telegram.bot-token", () -> "");
        r.add("trendpilot.telegram.chat-id", () -> "");
        r.add("trendpilot.variants.list[0].name", () -> "V5");
        r.add("trendpilot.variants.list[0].overrides.min-buy-rel1h", () -> "5.855");
        r.add("trendpilot.variants.list[1].name", () -> "V6");
        r.add("trendpilot.variants.list[1].overrides.momentum-top", () -> "1");
        r.add("trendpilot.variants.list[2].name", () -> "V7");
        r.add("trendpilot.variants.list[2].overrides.min-buy-rel1h", () -> "5.855");
        r.add("trendpilot.variants.list[3].name", () -> "V6B");
        r.add("trendpilot.variants.list[3].overrides.momentum-top", () -> "1");
        r.add("trendpilot.variants.list[3].overrides.momentum-venue", () -> "BYBIT");
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
        List<CoinGeckoClient.ExchangeTicker> listed = java.util.stream.Stream.of("ABC", "AAA", "BBX", "CCC", "STKX")
                .map(b -> new CoinGeckoClient.ExchangeTicker(b, "USDT", b.toLowerCase() + "-coin")).toList();
        research.saveIdentity(Venue.BINANCE, listed, fresh);
        research.saveIdentity(Venue.BYBIT, listed, fresh);
        research.saveCategory("tokenized-stock", List.of("stkx-coin"), fresh);
    }

    @Test
    void fourVariantsCanBeActive() {
        assertThat(registry.active()).extracting(VariantRegistry.Variant::name).containsExactlyInAnyOrder("V5", "V6", "V7", "V6B");
    }

    @Test
    void v6bPicksFromBybitAndFillsOnTheBybitBook() {
        Instant t0 = Instant.parse("2026-10-14T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        // Binance: AAA leads; Bybit: CCC leads (AAA is also listed on Bybit but weaker there)
        market.put("AAAUSDT", 12.0, TestData.flatQuarters(t0, 10), TestData.book("AAAUSDT", 10, 2, 5000, t0));
        market.put("CCCUSDT", 1.0, TestData.flatQuarters(t0, 20), TestData.book("CCCUSDT", 20, 2, 5000, t0));
        market.put("ABCUSDT", 0.5, TestData.flatQuarters(t0, 104), TestData.book("ABCUSDT", 104, 2, 100, t0));
        bybit.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        bybit.put("AAAUSDT", 2.0, TestData.flatQuarters(t0, 10), TestData.book("AAAUSDT", 10, 2, 5000, t0));
        bybit.put("CCCUSDT", 15.0, TestData.flatQuarters(t0, 21), TestData.book("CCCUSDT", 21, 2, 5000, t0));

        discovery.runOnce(t0);
        var b6 = registry.get("V6B").orElseThrow().book();
        assertThat(b6.positions()).extracting(p -> p.venue() + ":" + p.symbol()).containsExactly("BYBIT:CCCUSDT");
        // filled on Bybit's book (around 21), not Binance's (around 20)
        assertThat(b6.positions().getFirst().avgEntryPrice().doubleValue()).isBetween(21.0, 21.1);
        // V6 is unchanged: Binance pool, Binance book
        var b = registry.get("V6").orElseThrow().book();
        assertThat(b.positions()).extracting(p -> p.venue() + ":" + p.symbol()).containsExactly("BINANCE:AAAUSDT");
        assertThat(v2.positions()).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM variant_decision WHERE book = 'V6B' AND venue = 'BYBIT' AND outcome = 'BUY'")
                .query(Integer.class).single()).isEqualTo(1);
    }
}
