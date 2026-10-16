package com.trendpilot.observation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.trendpilot.market.ExecutionSimulator.Fill;
import com.trendpilot.market.Venue;
import com.trendpilot.monitor.ExitDecision;
import com.trendpilot.portfolio.Position;

/** V1 观察仓位持有满 72 小时按参考价到期结算；规则已要卖、盘口异常时不走到期结算。 */
class V1SettleTest {

    static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");

    static Position opened(Duration ago) {
        return new Position(Venue.BINANCE, "ABCUSDT", new BigDecimal("10"), new BigDecimal("1001"), new BigDecimal("1000"),
                1, new BigDecimal("100"), NOW.minus(ago), "G", NOW.minus(ago), false, BigDecimal.ZERO, BigDecimal.ZERO, 0, false);
    }

    static ExitDecision hold(Position p, List<String> notes, boolean complete) {
        Fill fill = new Fill(new BigDecimal("10"), new BigDecimal("950"), new BigDecimal("95"), new BigDecimal("1"), 0, complete);
        return new ExitDecision(p.symbol(), false, "HOLD", p, notes, new BigDecimal("-52"), -5, fill);
    }

    @Test
    void settlesOnlyAfter72HoursOfHolding() {
        Position young = opened(Duration.ofHours(71).plusMinutes(59));
        Position due = opened(Duration.ofHours(72));
        assertThat(V1Tracker.settleDue(young, hold(young, List.of(), true), NOW)).isFalse();
        assertThat(V1Tracker.settleDue(due, hold(due, List.of(), true), NOW)).isTrue();
    }

    @Test
    void doesNotSettleOnAbnormalBookOrWhenRulesAlreadySell() {
        Position due = opened(Duration.ofHours(100));
        assertThat(V1Tracker.settleDue(due, hold(due, List.of("ABNORMAL_BOOK_HOLD"), true), NOW)).isFalse();
        assertThat(V1Tracker.settleDue(due, hold(due, List.of(), false), NOW)).isFalse();
        ExitDecision sell = new ExitDecision(due.symbol(), true, "PROFIT_PROTECTION", due, List.of(), BigDecimal.TEN, 1,
                hold(due, List.of(), true).fill());
        assertThat(V1Tracker.settleDue(due, sell, NOW)).isFalse();
    }
}
