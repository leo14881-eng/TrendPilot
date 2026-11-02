package com.trendpilot.fastwatch;

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
import com.trendpilot.market.stream.StreamHub;
import com.trendpilot.portfolio.StrongVariantBook;
import com.trendpilot.research.CoinGeckoClient;
import com.trendpilot.research.ResearchService;
import com.trendpilot.support.FakeMarket;
import com.trendpilot.support.TestData;

/** FastWatch also guards the profit protection of configured variants (V5...) and V2D15, the same way as V2. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class FastWatchVariantTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
        r.add("trendpilot.telegram.bot-token", () -> "");
        r.add("trendpilot.telegram.chat-id", () -> "");
        r.add("trendpilot.variants.strong-min-rel1h", () -> "1.0");
        r.add("trendpilot.variants.strong-trade-usdt", () -> "1000");
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
    @Autowired FastWatch fastWatch;
    @Autowired StrongVariantBook v5;
    @Autowired JdbcClient jdbc;
    @Autowired Markets markets;
    @Autowired ResearchService research;

    @BeforeEach
    void reset() {
        market.reset();
        bybit.reset();
        markets.invalidate();
        for (String t : new String[]{"position", "v5_position", "shadow_event", "early_seen", "notification_outbox",
                "kline_15m", "equity_curve", "decision_log", "variant_decision"}) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        Instant fresh = Instant.parse("2026-12-31T00:00:00Z");
        List<CoinGeckoClient.ExchangeTicker> listed = java.util.stream.Stream.of("ABC", "AAA", "BBX", "CCC", "STKX")
                .map(b -> new CoinGeckoClient.ExchangeTicker(b, "USDT", b.toLowerCase() + "-coin")).toList();
        research.saveIdentity(Venue.BINANCE, listed, fresh);
        research.saveIdentity(Venue.BYBIT, listed, fresh);
        research.saveCategory("tokenized-stock", List.of("stkx-coin"), fresh);
    }

    private Instant armV5(Instant t0) {
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        market.put("ABCUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("ABCUSDT", 104, 2, 100, t0));
        for (String s : new String[]{"AAAUSDT", "BBXUSDT", "CCCUSDT"}) market.put(s, 2.0, TestData.flatQuarters(t0, 10), null);
        discovery.runOnce(t0);
        assertThat(v5.position("ABCUSDT")).isPresent();
        Instant t1 = t0.plusSeconds(300);
        market.books.put("ABCUSDT", TestData.book("ABCUSDT", 107.2, 2, 100, t1));
        markets.invalidate();
        monitor.runOnce(t1);
        assertThat(v5.position("ABCUSDT").orElseThrow().protectArmed()).isTrue();
        return t1;
    }

    private int v5Sells() {
        return jdbc.sql("SELECT COUNT(*) FROM shadow_event WHERE book = 'V5' AND type = 'SELL'").query(Integer.class).single();
    }

    @Test
    void variantProtectionIsSoldByFastWatchBeforeTheNextMonitorRound() {
        Instant t1 = armV5(Instant.parse("2026-10-20T10:17:00Z"));
        // 1 minute later the real-time best price drops below the protection floor; the depth cache still has 107.2
        Instant t2 = t1.plusSeconds(60);
        market.books.put("ABCUSDT", TestData.book("ABCUSDT", 104.7, 2, 100, t2));
        fastWatch.observe(t2, (venue, symbol) -> new StreamHub.Quote(104.68, 104.72, t2));
        assertThat(v5.position("ABCUSDT")).isEmpty();
        var sell = jdbc.sql("SELECT reason_code, generation, CAST(price AS REAL) AS price FROM shadow_event WHERE book = 'V5' AND type = 'SELL'")
                .query().singleRow();
        assertThat(sell.get("reason_code")).isEqualTo("PROFIT_PROTECTION");
        assertThat((String) sell.get("generation")).startsWith("F-");
        assertThat(((Number) sell.get("price")).doubleValue()).isBetween(104.5, 104.8); // close to the floor, not 5 minutes later
        // the next 5-minute round does not sell it again
        monitor.runOnce(t2.plusSeconds(240));
        assertThat(v5Sells()).isEqualTo(1);
    }

    @Test
    void fastWatchAndMonitorAtTheSameTimeSellOnlyOnce() throws Exception {
        Instant t1 = armV5(Instant.parse("2026-10-21T10:17:00Z"));
        Instant t2 = t1.plusSeconds(300);
        market.books.put("ABCUSDT", TestData.book("ABCUSDT", 104.7, 2, 100, t2));
        markets.invalidate();
        Thread a = new Thread(() -> fastWatch.observe(t2, (venue, symbol) -> new StreamHub.Quote(104.68, 104.72, t2)));
        Thread b = new Thread(() -> monitor.runOnce(t2));
        a.start();
        b.start();
        a.join();
        b.join();
        assertThat(v5.position("ABCUSDT")).isEmpty();
        assertThat(v5Sells()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM shadow_event WHERE book = 'V2' AND type = 'SELL'").query(Integer.class).single())
                .isEqualTo(1);
    }
}
