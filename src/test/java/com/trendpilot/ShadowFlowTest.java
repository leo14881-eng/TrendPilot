package com.trendpilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trendpilot.job.DiscoveryJob;
import com.trendpilot.job.MonitorJob;
import com.trendpilot.market.Markets;
import com.trendpilot.market.Ticker24h;
import com.trendpilot.market.Venue;
import com.trendpilot.portfolio.ObservationBook;
import com.trendpilot.portfolio.Position;
import com.trendpilot.portfolio.ShadowBook;
import com.trendpilot.support.FakeMarket;
import com.trendpilot.support.TestData;

/** 端到端：Discovery 影子买入 → Monitor 启动利润保护 → 回落触发保护卖出。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ShadowFlowTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
        r.add("trendpilot.telegram.bot-token", () -> "");
        r.add("trendpilot.telegram.chat-id", () -> "");
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
    @Autowired @Qualifier("bybitMarket") FakeMarket bybit;
    @Autowired DiscoveryJob discovery;
    @Autowired MonitorJob monitor;
    @Autowired ShadowBook book;
    @Autowired ObservationBook v1Book;
    @Autowired JdbcClient jdbc;
    @Autowired Markets markets;

    @org.junit.jupiter.api.BeforeEach
    void reset() {
        market.reset();
        bybit.reset();
        markets.invalidate();
        for (String t : new String[]{"position", "shadow_event", "early_seen", "notification_outbox", "app_state",
                "kline_15m", "v1_position"}) {
            jdbc.sql("DELETE FROM " + t).update();
        }
    }

    @Test
    void buyProtectAndSell() {
        Instant t0 = Instant.parse("2026-10-14T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        market.put("ABCUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("ABCUSDT", 104, 2, 100, t0));
        for (String s : new String[]{"AAAUSDT", "BBBUSDT", "CCCUSDT"}) {
            market.put(s, 2.0, TestData.flatQuarters(t0, 10), null);
        }
        market.put("USDCUSDT", 0.0, TestData.flatQuarters(t0, 1), null); // 稳定币应被排除

        DiscoveryJob.Report d = discovery.runOnce(t0);
        assertThat(d.scanComplete()).isTrue();
        assertThat(d.universe()).isEqualTo(5);
        assertThat(d.entries()).containsExactly("BUY:ABCUSDT");
        // 首次运行：每个标的一次 15m 全量请求，没有 1h 请求
        assertThat(d.klineRequests()).isEqualTo(5);
        assertThat(book.positions()).hasSize(1);

        // 同一 generation 再跑不会重复买入（加仓条件不满足），K 线全部命中缓存，交易对信息也走缓存
        DiscoveryJob.Report again = discovery.runOnce(t0);
        assertThat(again.entries()).isEmpty();
        assertThat(again.klineRequests()).isZero();
        assertThat(market.symbolRequests.get()).isEqualTo(1);

        // 价格上涨 3%：启动利润保护
        Instant t1 = t0.plusSeconds(300);
        market.books.put("ABCUSDT", TestData.book("ABCUSDT", 107.2, 2, 100, t1));
        markets.invalidate(); // 模拟 5 分钟后盘口缓存已过期
        int klinesBefore = market.klineRequests.get();
        MonitorJob.Report m1 = monitor.runOnce(t1);
        assertThat(m1.exits()).isEmpty();
        assertThat(market.klineRequests.get()).isEqualTo(klinesBefore); // 没有新收盘的 15m K 线，不请求
        assertThat(book.positions().getFirst().protectArmed()).isTrue();

        // 回落到底线以下但仍有净利：保护卖出
        Instant t2 = t1.plusSeconds(300);
        market.books.put("ABCUSDT", TestData.book("ABCUSDT", 104.7, 2, 100, t2));
        markets.invalidate();
        MonitorJob.Report m2 = monitor.runOnce(t2);
        assertThat(m2.exits()).containsExactly("PROFIT_PROTECTION:ABCUSDT");
        assertThat(book.positions()).isEmpty();
        assertThat(book.realizedPnl()).isPositive();

        Integer events = jdbc.sql("SELECT COUNT(*) FROM shadow_event WHERE book = 'V2' AND type IN ('BUY','SELL','PROTECT_ARM')")
                .query(Integer.class).single();
        assertThat(events).isEqualTo(3);
        Integer outbox = jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE status = 'PREPARED'")
                .query(Integer.class).single();
        assertThat(outbox).isGreaterThanOrEqualTo(3);
    }

    @Test
    void monitorStopsWhenRequiredBookMissing() {
        Instant t0 = Instant.parse("2026-10-15T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        market.put("XYZUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("XYZUSDT", 104, 2, 100, t0));
        discovery.runOnce(t0);
        assertThat(book.position("XYZUSDT")).isPresent();

        market.books.remove("XYZUSDT");
        markets.invalidate();
        MonitorJob.Report m = monitor.runOnce(t0.plusSeconds(300));
        assertThat(m.dataStatus()).startsWith("STOPPED_MISSING");
        assertThat(book.position("XYZUSDT")).isPresent();
    }

    @Test
    void bybitPoolOnlyCoversSymbolsMissingOnBinanceAndUsesItsOwnBtc() {
        Instant t0 = Instant.parse("2026-10-16T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        market.put("ABCUSDT", 1.0, TestData.flatQuarters(t0, 10), null);
        bybit.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        // ABC 两边都有：只用 Binance（Binance 上没有信号），Bybit 上的强势不算
        bybit.put("ABCUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("ABCUSDT", 104, 2, 100, t0));
        // BYO 只在 Bybit：进入 Bybit 独立池，用 Bybit 的盘口影子买入
        bybit.put("BYOUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("BYOUSDT", 104, 2, 100, t0));

        DiscoveryJob.Report d = discovery.runOnce(t0);
        assertThat(d.entries()).containsExactly("BUY:BYOUSDT");
        assertThat(book.position("BYOUSDT").orElseThrow().venue()).isEqualTo(Venue.BYBIT);
        assertThat(d.pools()).contains("BINANCE=2/COMPLETE", "BYBIT=2/COMPLETE");

        // Monitor 用 Bybit 的盘口管理这个持仓
        bybit.books.put("BYOUSDT", TestData.book("BYOUSDT", 107.2, 2, 100, t0.plusSeconds(300)));
        markets.invalidate();
        monitor.runOnce(t0.plusSeconds(300));
        assertThat(book.position("BYOUSDT").orElseThrow().protectArmed()).isTrue();
    }

    @Test
    void bybitWithoutItsOwnBtcProducesNoSignals() {
        Instant t0 = Instant.parse("2026-10-17T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        bybit.put("BYOUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("BYOUSDT", 104, 2, 100, t0));

        DiscoveryJob.Report d = discovery.runOnce(t0);
        assertThat(d.entries()).isEmpty();
        assertThat(d.early()).isZero();
        assertThat(d.pools()).contains("BYBIT=1/PARTIAL_DATA");
    }

    @Test
    void earlyBelowScoreEightGoesToV1OnlyAndExitsByReferencePrice() {
        Instant t0 = Instant.parse("2026-10-18T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flatQuarters(t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        market.put("ABCUSDT", 5.0, TestData.strongQuarters(t0), TestData.book("ABCUSDT", 104, 2, 100, t0));
        market.put("WEAKUSDT", 1.0, TestData.flatQuarters(t0, 10), null);

        DiscoveryJob.Report d = discovery.runOnce(t0);
        assertThat(d.v1Entries()).contains("ABCUSDT");
        Position v1 = v1Book.position("ABCUSDT").orElseThrow();
        assertThat(v1.costBasis().doubleValue()).isEqualTo(1001.0, org.assertj.core.data.Offset.offset(0.01));
        String v1Note = jdbc.sql("SELECT text FROM notification_outbox WHERE event_key LIKE 'v1-entry:%'")
                .query(String.class).single();
        assertThat(v1Note).startsWith("🟢 V1 观察买入").contains("ABCUSDT");
        // V1 不占用 V2 资金
        assertThat(book.deployedCost()).isEqualTo(book.positions().stream()
                .mapToDouble(p -> p.costBasis().doubleValue()).sum());

        // 参考价涨 3% 后回落到保护底线以下：V1 按参考价保护卖出
        Ticker24h up = market.tickers.get("ABCUSDT");
        market.tickers.put("ABCUSDT", new Ticker24h("ABCUSDT", up.lastPrice() * 1.03, 5, 1e7));
        monitor.runOnce(t0.plusSeconds(300));
        assertThat(v1Book.position("ABCUSDT").orElseThrow().protectArmed()).isTrue();
        market.tickers.put("ABCUSDT", new Ticker24h("ABCUSDT", up.lastPrice() * 1.007, 5, 1e7));
        monitor.runOnce(t0.plusSeconds(600));
        assertThat(v1Book.position("ABCUSDT")).isEmpty();
        assertThat(v1Book.realizedPnl()).isPositive();
        var stats = v1Book.stats();
        assertThat(stats.closed()).isEqualTo(1);
        assertThat(stats.winRatePct()).isEqualTo(100);
        assertThat(stats.avgHoldHours()).isEqualTo(10 / 60.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(stats.line()).startsWith("V1：买入 1 笔，平仓 1 笔，胜率 100%");
    }
}
