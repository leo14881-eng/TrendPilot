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
import com.trendpilot.portfolio.Position;
import com.trendpilot.portfolio.ShadowBook;
import com.trendpilot.portfolio.StrongVariantBook;
import com.trendpilot.research.CoinGeckoClient;
import com.trendpilot.research.ResearchService;
import com.trendpilot.support.FakeMarket;
import com.trendpilot.support.TestData;

/**
 * 强信号对照账本 V5：门槛压到测试信号（1h 相对 BTC 约 3%）以下，验证 V5 按自己的每笔金额独立开仓、不加仓、按相同规则退出，
 * 且不动 V2 的持仓和资金。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class StrongVariantFlowTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
        r.add("trendpilot.telegram.bot-token", () -> "");
        r.add("trendpilot.telegram.chat-id", () -> "");
        r.add("trendpilot.variants.strong-min-rel1h", () -> "1.0");
        r.add("trendpilot.variants.strong-trade-usdt", () -> "2000");
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
    @Autowired StrongVariantBook v5;
    @Autowired JdbcClient jdbc;
    @Autowired Markets markets;
    @Autowired ResearchService research;
    @Autowired com.trendpilot.report.HoldingsReport holdings;

    @BeforeEach
    void reset() {
        market.reset();
        bybit.reset();
        markets.invalidate();
        for (String t : new String[]{"position", "v5_position", "shadow_event", "early_seen", "notification_outbox",
                "kline_15m", "equity_curve", "decision_log"}) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        Instant fresh = Instant.parse("2026-12-31T00:00:00Z");
        List<CoinGeckoClient.ExchangeTicker> listed = java.util.stream.Stream.of("ABC", "AAA", "BBB", "CCC", "STKX")
                .map(b -> new CoinGeckoClient.ExchangeTicker(b, "USDT", b.toLowerCase() + "-coin")).toList();
        research.saveIdentity(Venue.BINANCE, listed, fresh);
        research.saveIdentity(Venue.BYBIT, listed, fresh);
        research.saveCategory("tokenized-stock", List.of("stkx-coin"), fresh);
    }

    @Test
    void opensIndependentlyWhenRel1hClearsThresholdAndLeavesV2Alone() {
        Instant t0 = Instant.parse("2026-10-14T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        market.put("ABCUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("ABCUSDT", 104, 2, 100, t0));
        for (String s : new String[]{"AAAUSDT", "BBBUSDT", "CCCUSDT"}) {
            market.put(s, 2.0, TestData.flatQuarters(t0, 10), null);
        }

        DiscoveryJob.Report d = discovery.runOnce(t0);
        assertThat(d.entries()).containsExactly("BUY:ABCUSDT");
        // V5 自己开一笔 2,000U（同一个审核盘口按 2,000U 重新算成交），V2 仍是 1,000U
        Position p5 = v5.position("ABCUSDT").orElseThrow();
        Position p2 = v2.position("ABCUSDT").orElseThrow();
        assertThat(p5.tranches()).isEqualTo(1);
        assertThat(p5.costBasis().doubleValue()).isBetween(2000.0, 2010.0);
        assertThat(p2.costBasis().doubleValue()).isBetween(1000.0, 1005.0);
        assertThat(p5.qty().doubleValue()).isGreaterThan(p2.qty().doubleValue() * 1.9);
        // V2 资金只算 V2 自己的一笔
        assertThat(v2.positions()).hasSize(1);
        assertThat(v2.deployedCost()).isCloseTo(p2.costBasis().doubleValue(), org.assertj.core.data.Offset.offset(1e-6));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM shadow_event WHERE book = 'V5' AND type = 'BUY'")
                .query(Integer.class).single()).isEqualTo(1);
        // 不发 Telegram 买入消息：只有 V2 的一条 entry
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE event_key LIKE 'entry:%'")
                .query(Integer.class).single()).isEqualTo(1);

        // V2 日报：正文只有 V5 概览和对比一行，持仓明细只在 .md 版
        String md = holdings.markdown(t0.plusSeconds(60));
        String v5md = md.substring(md.indexOf("## 五、V5"), md.indexOf("## 六、BTC"));
        assertThat(v5md).contains("持仓 1 个", "当天买入 1 笔", "对比（同口径，V5 / V2）", "| ABC | BINANCE |")
                .doesNotContain("V5 暂无持仓");
        String tg = holdings.telegramText(t0.plusSeconds(60));
        String v5tg = tg.substring(tg.indexOf("【五、V5"), tg.indexOf("【六、BTC"));
        assertThat(v5tg).contains("持仓 1 个", "对比（同口径，V5 / V2）").doesNotContain("ABC · BINANCE");

        // 再跑一轮：V5 不加仓
        discovery.runOnce(t0);
        assertThat(v5.position("ABCUSDT").orElseThrow().tranches()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM shadow_event WHERE book = 'V5' AND type = 'BUY'")
                .query(Integer.class).single()).isEqualTo(1);

        // 上涨 3% 启动利润保护，回落后两本账按相同规则各自卖出
        Instant t1 = t0.plusSeconds(300);
        market.books.put("ABCUSDT", TestData.book("ABCUSDT", 107.2, 2, 100, t1));
        markets.invalidate();
        monitor.runOnce(t1);
        assertThat(v5.position("ABCUSDT").orElseThrow().protectArmed()).isTrue();
        Instant t2 = t1.plusSeconds(300);
        market.books.put("ABCUSDT", TestData.book("ABCUSDT", 104.7, 2, 100, t2));
        markets.invalidate();
        MonitorJob.Report m2 = monitor.runOnce(t2);
        assertThat(m2.exits()).containsExactly("PROFIT_PROTECTION:ABCUSDT");
        assertThat(v5.positions()).isEmpty();
        assertThat(v2.positions()).isEmpty();
        assertThat(v5.realizedPnl()).isPositive();
        assertThat(jdbc.sql("SELECT reason_code FROM shadow_event WHERE book = 'V5' AND type = 'SELL'")
                .query(String.class).single()).isEqualTo("PROFIT_PROTECTION");
        assertThat(v5.realizedPnl()).isGreaterThan(v2.realizedPnl());
    }
}
