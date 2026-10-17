package com.trendpilot.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trendpilot.market.ExecutionSimulator.Fill;
import com.trendpilot.market.Venue;
import com.trendpilot.support.TestData;

/** 冻结修复：深度浮亏的持仓只在第一次跌破后的窗口内计入亏损冻结，之后不再计入。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class FreezeFixTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
    }

    @Autowired ShadowBook book;

    @Test
    void deepLossCountsOnlyWithinWindowAfterFirstBreach() {
        Instant t0 = Instant.parse("2026-10-17T00:00:00Z");
        Fill buy = new Fill(new BigDecimal("10"), new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("1"), 0, true);
        Position p = book.open(Venue.BINANCE, "FRZUSDT", buy, "G", t0);
        Duration w = Duration.ofHours(24);
        book.update(p, new BigDecimal("-20"), t0);
        assertThat(book.underwaterCountWithin(5, t0, w)).isZero();
        Instant t1 = t0.plusSeconds(3600);
        book.update(p, new BigDecimal("-80"), t1);
        assertThat(book.underwaterCountWithin(5, t1, w)).isEqualTo(1);
        assertThat(book.underwaterCountWithin(5, t1.plus(Duration.ofHours(23)), w)).isEqualTo(1);
        assertThat(book.underwaterCountWithin(5, t1.plus(Duration.ofHours(25)), w)).isZero();
        assertThat(book.underwaterCount(5)).isEqualTo(1);
    }
}
