package com.trendpilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import com.trendpilot.portfolio.ShadowBook;
import com.trendpilot.support.FakeMarket;
import com.trendpilot.support.TestData;

/** 端到端：Discovery 影子买入 → Monitor 启动利润保护 → 回落触发保护卖出。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ShadowFlowTest {

    @TempDir
    static Path dataDir;

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
    }

    @Autowired FakeMarket market;
    @Autowired DiscoveryJob discovery;
    @Autowired MonitorJob monitor;
    @Autowired ShadowBook book;
    @Autowired JdbcClient jdbc;

    @org.junit.jupiter.api.BeforeEach
    void reset() {
        market.symbols.clear();
        market.tickers.clear();
        market.bars.clear();
        market.books.clear();
        for (String t : new String[]{"position", "shadow_event", "early_seen", "notification_outbox", "app_state"}) {
            jdbc.sql("DELETE FROM " + t).update();
        }
    }

    @Test
    void buyProtectAndSell() {
        Instant t0 = Instant.parse("2026-10-14T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flat("BTCUSDT", t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        market.put("ABCUSDT", 5.0, TestData.strong("ABCUSDT", t0), TestData.book("ABCUSDT", 104, 2, 100, t0));
        for (String s : new String[]{"AAAUSDT", "BBBUSDT", "CCCUSDT"}) {
            market.put(s, 2.0, TestData.flat(s, t0, 10), null);
        }
        market.put("USDCUSDT", 0.0, TestData.flat("USDCUSDT", t0, 1), null); // 稳定币应被排除

        DiscoveryJob.Report d = discovery.runOnce(t0);
        assertThat(d.scanComplete()).isTrue();
        assertThat(d.universe()).isEqualTo(5);
        assertThat(d.entries()).containsExactly("BUY:ABCUSDT");
        assertThat(book.positions()).hasSize(1);

        // 同一 generation 再跑不会重复买入（加仓条件不满足）
        assertThat(discovery.runOnce(t0).entries()).isEmpty();

        // 价格上涨 3%：启动利润保护
        Instant t1 = t0.plusSeconds(300);
        market.books.put("ABCUSDT", TestData.book("ABCUSDT", 107.2, 2, 100, t1));
        MonitorJob.Report m1 = monitor.runOnce(t1);
        assertThat(m1.exits()).isEmpty();
        assertThat(book.positions().getFirst().protectArmed()).isTrue();

        // 回落到底线以下但仍有净利：保护卖出
        Instant t2 = t1.plusSeconds(300);
        market.books.put("ABCUSDT", TestData.book("ABCUSDT", 104.7, 2, 100, t2));
        MonitorJob.Report m2 = monitor.runOnce(t2);
        assertThat(m2.exits()).containsExactly("PROFIT_PROTECTION:ABCUSDT");
        assertThat(book.positions()).isEmpty();
        assertThat(book.realizedPnl()).isPositive();

        Integer events = jdbc.sql("SELECT COUNT(*) FROM shadow_event WHERE type IN ('BUY','SELL','PROTECT_ARM')")
                .query(Integer.class).single();
        assertThat(events).isEqualTo(3);
        Integer outbox = jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE status = 'PREPARED'")
                .query(Integer.class).single();
        assertThat(outbox).isGreaterThanOrEqualTo(3);
    }

    @Test
    void monitorStopsWhenRequiredBookMissing() {
        Instant t0 = Instant.parse("2026-10-15T10:17:00Z");
        market.put("BTCUSDT", 1.0, TestData.flat("BTCUSDT", t0, 60000), TestData.book("BTCUSDT", 60000, 1, 100, t0));
        market.put("XYZUSDT", 5.0, TestData.strong("XYZUSDT", t0), TestData.book("XYZUSDT", 104, 2, 100, t0));
        discovery.runOnce(t0);
        assertThat(book.position("XYZUSDT")).isPresent();

        market.books.remove("XYZUSDT");
        MonitorJob.Report m = monitor.runOnce(t0.plusSeconds(300));
        assertThat(m.dataStatus()).startsWith("STOPPED_MISSING");
        assertThat(book.position("XYZUSDT")).isPresent();
    }
}
