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
import com.trendpilot.job.MonitorJob;
import com.trendpilot.market.Markets;
import com.trendpilot.market.Venue;
import com.trendpilot.portfolio.Ledger;
import com.trendpilot.portfolio.ShadowBook;
import com.trendpilot.research.CoinGeckoClient;
import com.trendpilot.research.ResearchService;
import com.trendpilot.support.FakeMarket;
import com.trendpilot.support.TestData;
import com.trendpilot.variant.VariantRegistry;

/** V6BS6: same Bybit momentum entry as V6B, but a fixed 6% stop on the 1h close instead of the ATR stop. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class FixedStopVariantTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
        r.add("trendpilot.telegram.bot-token", () -> "");
        r.add("trendpilot.telegram.chat-id", () -> "");
        r.add("trendpilot.variants.list[0].name", () -> "V6B");
        r.add("trendpilot.variants.list[0].trade-usdt", () -> "1000");
        r.add("trendpilot.variants.list[0].overrides.momentum-top", () -> "1");
        r.add("trendpilot.variants.list[0].overrides.momentum-venue", () -> "BYBIT");
        r.add("trendpilot.variants.list[1].name", () -> "V6BS6");
        r.add("trendpilot.variants.list[1].trade-usdt", () -> "1000");
        r.add("trendpilot.variants.list[1].overrides.momentum-top", () -> "1");
        r.add("trendpilot.variants.list[1].overrides.momentum-venue", () -> "BYBIT");
        r.add("trendpilot.variants.list[1].overrides.stop-fixed-pct", () -> "6");
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
    @Autowired MonitorJob monitor;
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
        List<CoinGeckoClient.ExchangeTicker> listed = java.util.stream.Stream.of("AAA", "CCC", "STKX")
                .map(b -> new CoinGeckoClient.ExchangeTicker(b, "USDT", b.toLowerCase() + "-coin")).toList();
        research.saveIdentity(Venue.BINANCE, listed, fresh);
        research.saveIdentity(Venue.BYBIT, listed, fresh);
        research.saveCategory("tokenized-stock", List.of("stkx-coin"), fresh);
    }

    private Ledger book(String name) {
        return registry.get(name).orElseThrow().book();
    }

    @Test
    void fixedSixPercentStopSellsOnlyTheNewVariant() {
        Instant t0 = Instant.parse("2026-10-14T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        market.put("AAAUSDT", 2.0, TestData.flatQuarters(t0, 10), TestData.book("AAAUSDT", 10, 2, 5000, t0));
        bybit.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        bybit.put("CCCUSDT", 15.0, TestData.flatQuarters(t0, 20), TestData.book("CCCUSDT", 20, 2, 5000, t0));
        discovery.runOnce(t0);
        assertThat(book("V6B").positions()).extracting(p -> p.venue() + ":" + p.symbol()).containsExactly("BYBIT:CCCUSDT");
        assertThat(book("V6BS6").positions()).extracting(p -> p.venue() + ":" + p.symbol()).containsExactly("BYBIT:CCCUSDT");
        assertThat(registry.get("V6BS6").orElseThrow().overrides().describe()).contains("固定 6%");

        // −4% on the next closed hour: neither sells
        Instant t1 = t0.plusSeconds(3600);
        fall(t1, 19.2);
        monitor.runOnce(t1);
        assertThat(book("V6BS6").positions()).hasSize(1);
        assertThat(book("V6B").positions()).hasSize(1);

        // −7%: V6BS6 sells on its 6% stop; V6B keeps its ATR stop (15.5% without daily ATR) and holds
        Instant t2 = t1.plusSeconds(3600);
        fall(t2, 18.6);
        monitor.runOnce(t2);
        assertThat(book("V6BS6").positions()).isEmpty();
        assertThat(jdbc.sql("SELECT reason_code FROM shadow_event WHERE book = 'V6BS6' AND type = 'SELL'").query(String.class).single())
                .isEqualTo("STOP_LOSS");
        assertThat(jdbc.sql("SELECT dist_pct FROM loss_guard WHERE book = 'V6BS6'").query(Double.class).single()).isEqualTo(6.0);
        assertThat(book("V6B").positions()).hasSize(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM shadow_event WHERE book = 'V6B' AND type = 'SELL'").query(Integer.class).single()).isZero();
        assertThat(v2.positions()).isEmpty();
    }

    private void fall(Instant t, double price) {
        market.quarters.put("BTCUSDT", TestData.flatQuarters(t, 60000));
        market.books.put("BTCUSDT", TestData.book("BTCUSDT", 60000, 1, 100, t));
        bybit.quarters.put("BTCUSDT", TestData.flatQuarters(t, 60000));
        bybit.books.put("BTCUSDT", TestData.book("BTCUSDT", 60000, 1, 100, t));
        bybit.quarters.put("CCCUSDT", TestData.flatQuarters(t, price));
        bybit.books.put("CCCUSDT", TestData.book("CCCUSDT", price, 2, 5000, t));
        markets.invalidate();
    }
}
