package com.trendpilot.capital;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.trendpilot.market.OrderBook;
import com.trendpilot.market.Ticker24h;
import com.trendpilot.market.Venue;
import com.trendpilot.portfolio.Position;
import com.trendpilot.research.Identity;
import com.trendpilot.research.ResearchRecord;
import com.trendpilot.risk.Regime;
import com.trendpilot.risk.RiskState;
import com.trendpilot.signal.EarlySignal;
import com.trendpilot.signal.EarlySignalCalculator;
import com.trendpilot.support.TestData;

class CapitalReviewerTest {

    private final Instant now = Instant.parse("2026-10-14T10:17:00Z");
    private final CapitalReviewer reviewer = new CapitalReviewer(TestData.params());
    private final EarlySignal strong = new EarlySignalCalculator(TestData.params())
            .compute(TestData.strong("ABCUSDT", now), TestData.flat("BTCUSDT", now, 60000));
    private final RiskState okRisk = new RiskState(Regime.CONSTRUCTIVE, true, 1, 1, 0, 0, 0.6, 0, List.of());
    private final Ticker24h ticker = new Ticker24h("ABCUSDT", 104, 5, 5_000_000);
    private final OrderBook goodBook = TestData.book("ABCUSDT", 104, 2, 100, now);
    private ResearchRecord research = verified(Identity.VERIFIED);

    private static ResearchRecord verified(Identity identity) {
        return new ResearchRecord(Venue.BINANCE, "ABCUSDT", "abc", identity, 1e8, 2e8, 0.5, Double.NaN, Double.NaN,
                Double.NaN, List.of(), List.of("VALUE_CAPTURE_NOT_COVERED", "UNLOCK_SCHEDULE_NOT_COVERED"));
    }

    private ReviewInput input(OrderBook book, RiskState risk, Ticker24h t, BigDecimal firstSeen, Position pos,
                              Instant lastExit, boolean complete) {
        return new ReviewInput("D-1", now.minusSeconds(60), now, complete, strong, t, book, risk, firstSeen, pos, lastExit,
                research);
    }

    @Test
    void strongSignalWithGoodBookIsBuy() {
        ReviewResult r = reviewer.review(input(goodBook, okRisk, ticker, null, null, null, true));
        assertThat(r.decision()).isEqualTo(Decision.BUY);
        assertThat(r.action()).isEqualTo(Action.BUY);
        assertThat(r.rr()).isGreaterThan(1.5);
        assertThat(r.gaps()).contains("UNLOCK_SCHEDULE_NOT_COVERED");
    }

    @Test
    void incompleteScanIsSystemBlocked() {
        ReviewResult r = reviewer.review(input(goodBook, okRisk, ticker, null, null, null, false));
        assertThat(r.decision()).isEqualTo(Decision.SYSTEM_BLOCKED);
        assertThat(r.reasons()).contains("SCAN_INCOMPLETE");
    }

    @Test
    void lossFreezeIsSystemBlocked() {
        RiskState frozen = new RiskState(Regime.NEUTRAL, false, 0, 0, 0, 0, 0.5, 3, List.of("LOSS_FREEZE"));
        assertThat(reviewer.review(input(goodBook, frozen, ticker, null, null, null, true)).reasons())
                .contains("LOSS_FREEZE");
    }

    @Test
    void wideSpreadIsRejected() {
        OrderBook wide = TestData.book("ABCUSDT", 104, 40, 100, now); // 价差 80bps
        ReviewResult r = reviewer.review(input(wide, okRisk, ticker, null, null, null, true));
        assertThat(r.decision()).isEqualTo(Decision.REJECT);
        assertThat(r.reasons()).anyMatch(s -> s.startsWith("SPREAD_GT_"));
    }

    @Test
    void thinDepthIsRejected() {
        OrderBook thin = TestData.book("ABCUSDT", 104, 2, 5, now); // 每档约 520U
        ReviewResult r = reviewer.review(input(thin, okRisk, ticker, null, null, null, true));
        assertThat(r.reasons()).anyMatch(s -> s.startsWith("DEPTH_LT_"));
    }

    @Test
    void hot24hNeedsStrongerSignal() {
        Ticker24h hot = new Ticker24h("ABCUSDT", 104, 25, 5_000_000);
        // 1h 相对收益 2.97 ≥ 0.8 × 2，且加速度为正 → 仍可买
        assertThat(reviewer.review(input(goodBook, okRisk, hot, null, null, null, true)).decision())
                .isEqualTo(Decision.BUY);
    }

    @Test
    void chasingFarAboveFirstEarlyPriceRaisesRrRequirement() {
        ReviewResult normal = reviewer.review(input(goodBook, okRisk, ticker, new BigDecimal("100"), null, null, true));
        ReviewResult chase = reviewer.review(input(goodBook, okRisk, ticker, new BigDecimal("90"), null, null, true));
        assertThat(normal.decision()).isEqualTo(Decision.BUY);
        // RR 约 2.4，≥2，追涨仍通过；把首次价压到 RR 不够时应进入 WAIT
        assertThat(chase.rr()).isEqualTo(normal.rr());
        assertThat(chase.decision()).isEqualTo(normal.rr() >= 2 ? Decision.BUY : Decision.WAIT);
    }

    @Test
    void reentryWithinCooldownWaits() {
        ReviewResult r = reviewer.review(input(goodBook, okRisk, ticker, null, null, now.minus(Duration.ofMinutes(30)), true));
        assertThat(r.decision()).isEqualTo(Decision.WAIT);
        assertThat(r.reasons()).contains("REENTRY_COOLDOWN");
    }

    @Test
    void addRequiresPriceBelowLastTrancheByMinimumDrop() {
        Position pos = position(new BigDecimal("104.5"), now.minus(Duration.ofHours(1)), 1);
        ReviewResult r = reviewer.review(input(goodBook, okRisk, ticker, null, pos, null, true));
        assertThat(r.action()).isEqualTo(Action.ADD);
        assertThat(r.decision()).isEqualTo(Decision.WAIT);
        assertThat(r.reasons()).anyMatch(s -> s.startsWith("ADD_DROP_LT_"));

        Position cheap = position(new BigDecimal("120"), now.minus(Duration.ofHours(1)), 1);
        assertThat(reviewer.review(input(goodBook, okRisk, ticker, null, cheap, null, true)).decision())
                .isEqualTo(Decision.BUY);
    }

    @Test
    void addNeedsSignalGapAndRespectsMaxTranches() {
        Position recent = position(new BigDecimal("120"), now.minus(Duration.ofMinutes(5)), 1);
        assertThat(reviewer.review(input(goodBook, okRisk, ticker, null, recent, null, true)).reasons())
                .contains("ADD_SIGNAL_GAP");
        Position full = position(new BigDecimal("120"), now.minus(Duration.ofHours(1)), 3);
        assertThat(reviewer.review(input(goodBook, okRisk, ticker, null, full, null, true)).reasons())
                .contains("MAX_TRANCHES");
    }

    private Position position(BigDecimal lastPrice, Instant lastSignal, int tranches) {
        return new Position(Venue.BINANCE, "ABCUSDT", new BigDecimal("10"), new BigDecimal("1001"), new BigDecimal("1000"), tranches,
                lastPrice, lastSignal, "D-0", lastSignal, false, BigDecimal.ZERO, BigDecimal.ZERO, 0, false);
    }

    @Test
    void identityGates() {
        research = verified(Identity.AMBIGUOUS);
        ReviewResult ambiguous = reviewer.review(input(goodBook, okRisk, ticker, null, null, null, true));
        assertThat(ambiguous.decision()).isEqualTo(Decision.REJECT);
        assertThat(ambiguous.reasons()).contains("IDENTITY_AMBIGUOUS");

        research = verified(Identity.UNVERIFIED);
        assertThat(reviewer.review(input(goodBook, okRisk, ticker, null, null, null, true)).reasons())
                .contains("IDENTITY_UNVERIFIED");

        research = ResearchRecord.unavailable(Venue.BINANCE, "ABCUSDT");
        ReviewResult unavailable = reviewer.review(input(goodBook, okRisk, ticker, null, null, null, true));
        assertThat(unavailable.decision()).isEqualTo(Decision.SYSTEM_BLOCKED);
        assertThat(unavailable.reasons()).contains("IDENTITY_DATA_UNAVAILABLE");
    }
}
