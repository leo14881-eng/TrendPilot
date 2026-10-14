package com.trendpilot.fastwatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trendpilot.market.Venue;
import com.trendpilot.portfolio.Position;
import com.trendpilot.portfolio.ShadowBook;
import com.trendpilot.support.TestData;

/** FastWatch 只写观察表，不改正式组合。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class FastWatchTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
    }

    @Autowired FastWatch fastWatch;
    @Autowired ShadowBook book;
    @Autowired JdbcClient jdbc;

    private Position armed() {
        Instant t = Instant.parse("2026-10-14T10:00:00Z");
        // 10 个，成本 1001（含费），保护底线 20 U
        return new Position(Venue.BINANCE, "ABCUSDT", new BigDecimal("10"), new BigDecimal("1001"), new BigDecimal("1000"),
                1, new BigDecimal("100"), t, "D1", t, true, new BigDecimal("20"), new BigDecimal("40"), 0, false);
    }

    @Test
    void observesProtectionWithoutTouchingLedger() {
        Instant now = Instant.parse("2026-10-14T11:00:00Z");
        Position p = armed();
        assertThat(fastWatch.observe(p, 104, 104.1, now)).isEqualTo("HOLD");          // 净利约 38 U
        assertThat(fastWatch.observe(p, 102, 102.1, now)).isEqualTo("WOULD_PROTECT_SELL"); // 约 17.98 U < 20
        assertThat(fastWatch.observe(p, 102, 102.1, now)).isEqualTo("WOULD_PROTECT_SELL"); // 重复不再记录
        assertThat(fastWatch.observe(p, 99, 99.1, now)).isEqualTo("GAPPED");

        Integer rows = jdbc.sql("SELECT COUNT(*) FROM fastwatch_observation").query(Integer.class).single();
        assertThat(rows).isEqualTo(2);
        assertThat(book.positions()).isEmpty(); // formal_writer = false
        Integer events = jdbc.sql("SELECT COUNT(*) FROM shadow_event").query(Integer.class).single();
        assertThat(events).isZero();
    }
}
