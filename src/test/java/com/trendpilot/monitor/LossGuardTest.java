package com.trendpilot.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trendpilot.market.ExecutionSimulator.Fill;
import com.trendpilot.market.Venue;
import com.trendpilot.portfolio.Position;
import com.trendpilot.support.TestData;

/** 山寨止损：V2 / V2D15 各自独立记状态；盘口异常、成交不完整、K 线缺失时不动作。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class LossGuardTest {

    static final Path dataDir = TestData.dataDir();
    static final long H = 3_600_000L;
    static final Instant OPEN = Instant.parse("2026-10-10T00:00:00Z");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
    }

    @Autowired LossGuard guard;

    static Position pos(String sym) {
        return new Position(Venue.BINANCE, sym, new BigDecimal("1000"), new BigDecimal("1001"), new BigDecimal("1000"), 1,
                new BigDecimal("1"), OPEN, "G", OPEN, false, BigDecimal.ZERO, BigDecimal.ZERO, 0, false);
    }

    static ExitDecision hold(Position p, double net, boolean complete, List<String> notes) {
        Fill fill = new Fill(new BigDecimal("1000"), new BigDecimal("900"), new BigDecimal("0.9"), new BigDecimal("1"), 0, complete);
        return new ExitDecision(p.symbol(), false, "HOLD", p, notes, BigDecimal.valueOf(net), net / 10, fill);
    }

    @Test
    void stopsBelowAtrDistanceAndStaysQuietWithoutTrustedData() {
        Position p = pos("GUARDAUSDT");
        assertThat(guard.needDailyAtr("V2", List.of(p))).containsExactly(p);
        guard.provideDailyAtr("V2", p, 6); // 距离 9%
        Instant now = OPEN.plus(Duration.ofHours(5));
        assertThat(guard.check("V2", p, hold(p, -82, true, List.of()), 0.92, 3 * H, now)).isNull();
        assertThat(guard.needDailyAtr("V2", List.of(p))).isEmpty();
        // 盘口异常 / 成交不完整 / 没有 K 线：不动作
        assertThat(guard.check("V2", p, hold(p, -102, true, List.of("ABNORMAL_BOOK_HOLD")), 0.90, 4 * H, now)).isNull();
        assertThat(guard.check("V2", p, hold(p, -102, false, List.of()), 0.90, 4 * H, now)).isNull();
        assertThat(guard.check("V2", p, hold(p, -102, true, List.of()), Double.NaN, 4 * H, now)).isNull();
        assertThat(guard.check("V2", p, hold(p, -102, true, List.of()), 0.90, 4 * H, now)).isEqualTo(LossGuard.STOP_LOSS);
        // 另一个账本的同一个币状态独立：还没有距离，按默认 15.5%，−10% 不止损
        assertThat(guard.check("V2D15", p, hold(p, -102, true, List.of()), 0.90, 4 * H, now)).isNull();
        ExitDecision sold = LossGuard.asExit(hold(p, -102, true, List.of()), LossGuard.STOP_LOSS);
        assertThat(sold.sell()).isTrue();
        assertThat(sold.reason()).isEqualTo("STOP_LOSS");
    }

    @Test
    void threeDaysWithoutProfitExitsOnBounceHigh() {
        Position p = pos("GUARDBUSDT");
        guard.provideDailyAtr("V2", p, 10); // 距离 15%
        Instant t72 = OPEN.plus(Duration.ofHours(72));
        assertThat(guard.check("V2", p, hold(p, -50, true, List.of()), 0.95, 71 * H, t72)).isNull();
        assertThat(guard.check("V2", p, hold(p, -40, true, List.of()), 0.96, 72 * H, t72.plus(Duration.ofHours(1)))).isNull();
        assertThat(guard.check("V2", p, hold(p, -45, true, List.of()), 0.955, 73 * H, t72.plus(Duration.ofHours(2))))
                .isEqualTo(LossGuard.STALE_EXIT);
    }
}
