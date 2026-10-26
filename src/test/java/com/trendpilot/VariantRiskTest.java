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
import com.trendpilot.variant.VariantTracker;
import com.trendpilot.research.CoinGeckoClient;
import com.trendpilot.research.ResearchService;
import com.trendpilot.support.FakeMarket;
import com.trendpilot.support.TestData;
import com.trendpilot.variant.VariantRegistry;

/** 涨幅榜动量影子变体：按 24h 涨幅取前 N 名，有新鲜盘口、深度够才买；缺盘口不买；不影响 V2、不发开仓消息。 */
/** 变体各自的风控：V2 亏损冻结时变体照常开仓；变体自己亏到阈值只冻结它自己，V2 和其他变体照常。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class VariantRiskTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
        r.add("trendpilot.telegram.bot-token", () -> "");
        r.add("trendpilot.telegram.chat-id", () -> "");
        r.add("trendpilot.variants.list[0].name", () -> "V5");
        r.add("trendpilot.variants.list[0].trade-usdt", () -> "2000");
        r.add("trendpilot.variants.list[0].overrides.min-buy-rel1h", () -> "1.0");
        r.add("trendpilot.variants.list[1].name", () -> "V6");
        r.add("trendpilot.variants.list[1].trade-usdt", () -> "1500");
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
    @Autowired com.trendpilot.job.AppState state;

    static final Instant T0 = Instant.parse("2026-10-14T10:17:00Z");

    @BeforeEach
    void reset() {
        market.reset();
        bybit.reset();
        markets.invalidate();
        registry.all(); // 先建好变体持仓表
        for (String t : new String[]{"position", "v5_position", "v6_position", "shadow_event", "early_seen", "notification_outbox",
                "equity_curve", "decision_log"}) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        jdbc.sql("DELETE FROM app_state WHERE key LIKE 'risk:%'").update();
        Instant fresh = Instant.parse("2026-12-31T00:00:00Z");
        List<CoinGeckoClient.ExchangeTicker> listed = java.util.stream.Stream.of("ABC", "AAA", "BBB", "CCC", "STKX")
                .map(b -> new CoinGeckoClient.ExchangeTicker(b, "USDT", b.toLowerCase() + "-coin")).toList();
        research.saveIdentity(Venue.BINANCE, listed, fresh);
        research.saveIdentity(Venue.BYBIT, listed, fresh);
        research.saveCategory("tokenized-stock", List.of("stkx-coin"), fresh);
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(T0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, T0));
        market.put("ABCUSDT", 5.0, TestData.strongQuarters(T0), TestData.book("ABCUSDT", 104, 2, 100, T0));
        for (String s : new String[]{"AAAUSDT", "BBBUSDT", "CCCUSDT"}) market.put(s, 2.0, TestData.flatQuarters(T0, 10), null);
    }

    /** 某本账在 T0 前几小时有 n 笔亏损卖出。 */
    private void losses(String book, int n) {
        for (int i = 0; i < n; i++) {
            jdbc.sql("""
                    INSERT INTO shadow_event(event_id, book, venue, symbol, type, pnl_usdt, reason_code, generation, params_version, created_at)
                    VALUES (:id, :b, 'BINANCE', 'L' || :i || 'USDT', 'SELL', '-50', 'STOP_LOSS', 'g', 'v', :t)
                    """).param("id", java.util.UUID.randomUUID().toString()).param("b", book).param("i", i)
                    .param("t", T0.minusSeconds(3600L * (i + 1)).toString()).update();
        }
    }

    private boolean holds(String book, String sym) {
        return registry.get(book).orElseThrow().book().position(sym).isPresent();
    }

    @Test
    void v2FreezeDoesNotStopVariants() {
        losses("V2", 3);
        DiscoveryJob.Report d = discovery.runOnce(T0);
        assertThat(d.entries()).isEmpty(); // V2 自己冻结
        assertThat(v2.positions()).isEmpty();
        assertThat(holds("V5", "ABCUSDT")).isTrue();
        assertThat(holds("V6", "ABCUSDT")).isTrue();
        assertThat(state.get(VariantTracker.statusKey("V5"))).contains("NORMAL");
        assertThat(state.get("risk:recovering")).contains("true"); // V2 的状态键没被变体改动
        assertThat(state.get("risk:V5:recovering").orElse("false")).isEqualTo("false");
    }

    @Test
    void variantLossesFreezeOnlyThatVariant() {
        losses("V6", 3);
        DiscoveryJob.Report d = discovery.runOnce(T0);
        assertThat(d.entries()).containsExactly("BUY:ABCUSDT");
        assertThat(holds("V5", "ABCUSDT")).isTrue();
        assertThat(holds("V6", "ABCUSDT")).isFalse();
        assertThat(state.get(VariantTracker.statusKey("V6"))).contains("LOSS_FREEZE");
        assertThat(state.get(VariantTracker.statusKey("V5"))).contains("NORMAL");
        assertThat(VariantTracker.statusLabel("LOSS_FREEZE")).isEqualTo("亏损冻结");
    }

    @Test
    void variantHalfBudgetFollowsV2RuleWithItsOwnBudget() {
        losses("V6", 2); // 2 笔亏损：额度减半，但仍可以买
        discovery.runOnce(T0);
        assertThat(state.get(VariantTracker.statusKey("V6"))).contains("LOSS_HALF_BUDGET");
        assertThat(holds("V6", "ABCUSDT")).isTrue();
    }
}
