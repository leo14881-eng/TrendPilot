package com.trendpilot.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trendpilot.market.ExecutionSimulator.Fill;
import com.trendpilot.market.Venue;
import com.trendpilot.support.TestData;
import com.trendpilot.variant.VariantRegistry;

/** 配置化变体（V6 起，不是 Spring bean）的开仓 / 平仓记账要么全部生效，要么全部不生效。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class VariantBookAtomicTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
    }

    @Autowired VariantRegistry registry;
    @Autowired JdbcClient jdbc;

    @AfterEach
    void dropTrigger() {
        jdbc.sql("DROP TRIGGER IF EXISTS fail_sell").update();
    }

    @Test
    void sellIsAllOrNothing() {
        Ledger book = registry.get("V6").orElseThrow().book();
        assertThat(book).isInstanceOf(VariantBook.class);
        Instant now = Instant.parse("2026-10-30T00:00:00Z");
        Fill buy = new Fill(new BigDecimal("1000"), new BigDecimal("1000"), new BigDecimal("1"), new BigDecimal("1"), 0, true);
        Position p = book.open(Venue.BINANCE, "ATOMUSDT", buy, "G1", now);
        // 写卖出记录时数据库出错：持仓不能凭空消失
        jdbc.sql("""
                CREATE TRIGGER fail_sell BEFORE INSERT ON shadow_event WHEN NEW.type = 'SELL' AND NEW.symbol = 'ATOMUSDT'
                BEGIN SELECT RAISE(ABORT, 'disk full'); END""").update();
        Fill sell = new Fill(new BigDecimal("1000"), new BigDecimal("1100"), new BigDecimal("1.1"), new BigDecimal("1.1"), 0, true);
        assertThatThrownBy(() -> book.close(p, sell, new BigDecimal("97.9"), "TEST", "G2", now.plusSeconds(60)))
                .isInstanceOf(RuntimeException.class);
        assertThat(book.position("ATOMUSDT")).isPresent();
        dropTrigger();
        book.close(p, sell, new BigDecimal("97.9"), "TEST", "G2", now.plusSeconds(60));
        assertThat(book.position("ATOMUSDT")).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM shadow_event WHERE book = 'V6' AND symbol = 'ATOMUSDT' AND type = 'SELL'")
                .query(Integer.class).single()).isEqualTo(1);
    }
}
