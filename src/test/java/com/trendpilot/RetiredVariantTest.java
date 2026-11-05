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
import com.trendpilot.market.Kline;
import com.trendpilot.market.Markets;
import com.trendpilot.market.Venue;
import com.trendpilot.portfolio.Ledger;
import com.trendpilot.research.CoinGeckoClient;
import com.trendpilot.research.ResearchService;
import com.trendpilot.support.FakeMarket;
import com.trendpilot.support.TestData;
import com.trendpilot.variant.VariantRegistry;

/**
 * Retiring a variant (V7, user decision 2026-11-05): it stops opening new positions, keeps selling what it holds under its
 * original rules, frees its active slot, and is archived once empty with its ledger and history kept.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class RetiredVariantTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
        r.add("trendpilot.optimize.max-active", () -> "2");
        r.add("trendpilot.telegram.bot-token", () -> "");
        r.add("trendpilot.telegram.chat-id", () -> "");
        r.add("trendpilot.variants.list[0].name", () -> "V5");
        r.add("trendpilot.variants.list[0].overrides.min-buy-rel1h", () -> "1.0");
        r.add("trendpilot.variants.list[1].name", () -> "V7");
        r.add("trendpilot.variants.list[1].trade-usdt", () -> "1000");
        r.add("trendpilot.variants.list[1].overrides.min-buy-rel1h", () -> "1.0");
        // a retired variant that was never registered is not registered now
        r.add("trendpilot.variants.list[2].name", () -> "V8");
        r.add("trendpilot.variants.list[2].overrides.min-buy-rel1h", () -> "2.0");
        r.add("trendpilot.variants.list[2].retired", () -> "true");
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
    @Autowired VariantRegistry registry;
    @Autowired JdbcClient jdbc;
    @Autowired Markets markets;
    @Autowired ResearchService research;
    @Autowired com.trendpilot.report.HoldingsReport holdings;

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

    private Ledger book(String name) {
        return registry.get(name).orElseThrow().book();
    }

    @Test
    void retiredVariantStopsBuyingSellsUnderItsOwnRulesAndIsArchived() {
        assertThat(registry.active()).extracting(VariantRegistry.Variant::name).containsExactly("V5", "V7");
        assertThat(registry.get("V8")).isEmpty();

        Instant t0 = Instant.parse("2026-10-14T10:17:00Z");
        strong(t0, "ABCUSDT");
        discovery.runOnce(t0);
        assertThat(book("V7").position("ABCUSDT")).isPresent();

        // retire V7: no longer opens new positions, frees its slot, keeps its position
        registry.retire("V7", "test", t0);
        registry.retire("V7", "again", t0); // idempotent
        assertThat(registry.get("V7").orElseThrow().status()).isEqualTo(VariantRegistry.STOPPED);
        assertThat(registry.active()).extracting(VariantRegistry.Variant::name).containsExactly("V5");
        assertThat(registry.activeCount()).isEqualTo(1);
        assertThat(holdings.markdown(t0.plusSeconds(60))).contains("**V7**（已停用（不开新仓");

        // a new strong signal one hour later: V5 buys it, the retired V7 does not
        Instant t1 = t0.plusSeconds(3600);
        strong(t1, "ABCUSDT");
        strong(t1, "AAAUSDT");
        discovery.runOnce(t1);
        assertThat(book("V5").position("AAAUSDT")).isPresent();
        assertThat(book("V7").position("AAAUSDT")).isEmpty();
        assertThat(book("V7").position("ABCUSDT")).isPresent();

        // ABC falls ~23%: V7 sells under its original ATR stop (15.5% when daily ATR is missing) and is archived
        Instant t2 = t1.plusSeconds(2 * 3600);
        fall(t2, "ABCUSDT", 80);
        monitor.runOnce(t2);
        assertThat(book("V7").positions()).isEmpty();
        assertThat(jdbc.sql("SELECT reason_code FROM shadow_event WHERE book = 'V7' AND type = 'SELL'").query(String.class).single())
                .isEqualTo("STOP_LOSS");
        VariantRegistry.Variant v7 = registry.get("V7").orElseThrow();
        assertThat(v7.status()).isEqualTo(VariantRegistry.ARCHIVED);
        assertThat(v7.note()).startsWith(VariantRegistry.RETIRED_PREFIX);
        // history kept: buy and sell events, the ledger row and the archived entry in the daily report
        assertThat(jdbc.sql("SELECT COUNT(*) FROM shadow_event WHERE book = 'V7'").query(Integer.class).single()).isGreaterThanOrEqualTo(2);
        assertThat(registry.everything()).extracting(VariantRegistry.Variant::name).contains("V7");
        assertThat(registry.all()).extracting(VariantRegistry.Variant::name).doesNotContain("V7");
        assertThat(holdings.markdown(t2.plusSeconds(60))).contains("**V7**（已停用（已归档）");
    }

    private void strong(Instant t, String sym) {
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t));
        market.put(sym, 5.0, TestData.strongQuarters(t), TestData.book(sym, 104, 2, 100, t));
        for (String s : new String[]{"BBXUSDT", "CCCUSDT"}) market.put(s, 2.0, TestData.flatQuarters(t, 10), null);
        markets.invalidate();
    }

    private void fall(Instant t, String sym, double price) {
        List<Kline> q = TestData.flatQuarters(t, price);
        market.quarters.put(sym, q);
        market.quarters.put("BTCUSDT", TestData.flatQuarters(t, 60000));
        market.books.put(sym, TestData.book(sym, price, 2, 100, t));
        market.books.put("BTCUSDT", TestData.book("BTCUSDT", 60000, 1, 100, t));
        markets.invalidate();
    }
}
