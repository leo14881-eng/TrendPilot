package com.trendpilot.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.trendpilot.market.OrderBook;
import com.trendpilot.market.SymbolInfo;
import com.trendpilot.market.Venue;
import com.trendpilot.portfolio.Position;
import com.trendpilot.signal.EarlySignal;
import com.trendpilot.support.TestData;

class ExitPolicyTest {

    private final ExitPolicy policy = new ExitPolicy(TestData.params());
    private final Instant now = Instant.parse("2026-10-14T10:20:00Z");
    private final SymbolInfo trading = new SymbolInfo("ABCUSDT", "ABC", "USDT", true, "");
    private final EarlySignal neutral = signal(0.1, 0.1, 0.1, 0.1);
    private final EarlySignal weak = signal(-0.5, -1, -0.3, -0.2);

    /** 买入 10 个，均价 100，成本 1001。 */
    private Position bought() {
        return new Position(Venue.BINANCE, "ABCUSDT", new BigDecimal("10"), new BigDecimal("1001"), new BigDecimal("1000"), 1,
                new BigDecimal("100"), now, "D-0", now, false, BigDecimal.ZERO, BigDecimal.ZERO, 0, false);
    }

    private OrderBook book(double mid) {
        return TestData.book("ABCUSDT", mid, 1, 1000, now);
    }

    @Test
    void protectionArmsAtTwoPercentThenSellsWhenNetFallsBelowFloor() {
        ExitDecision up = policy.evaluate(bought(), trading, book(103), neutral);
        assertThat(up.sell()).isFalse();
        assertThat(up.notes()).containsExactly("PROTECT_ARM");
        assertThat(up.updated().protectArmed()).isTrue();
        // 底线 = max(本金 0.35%, 最高净利 − 本金 1%, 最高净利 × 60%) ≈ max(3.5, 27.9 − 10.0, 16.7)
        assertThat(up.updated().protectFloor().doubleValue()).isBetween(17.5, 18.5);

        ExitDecision stillAbove = policy.evaluate(up.updated(), trading, book(102.4), neutral);
        assertThat(stillAbove.sell()).isFalse();

        ExitDecision below = policy.evaluate(stillAbove.updated(), trading, book(101.5), neutral);
        assertThat(below.sell()).isTrue();
        assertThat(below.reason()).isEqualTo("PROFIT_PROTECTION");
        assertThat(below.netPnl().signum()).isPositive();
    }

    @Test
    void gapThroughProtectionDoesNotSellAtLoss() {
        ExitDecision up = policy.evaluate(bought(), trading, book(103), neutral);
        ExitDecision gap = policy.evaluate(up.updated(), trading, book(99), neutral);
        assertThat(gap.sell()).isFalse();
        assertThat(gap.notes()).contains("PROTECTION_GAPPED");
    }

    @Test
    void largePeakLocksSixtyPercent() {
        // 最高净利约 57.8：底线 = max(57.8 − 10, 57.8 × 60%) ≈ 47.8，盈利 35 U 时已经卖出
        ExitDecision a = policy.evaluate(bought(), trading, book(106), neutral);
        assertThat(a.updated().protectFloor().doubleValue()).isBetween(47.0, 49.0);
        ExitDecision b = policy.evaluate(a.updated(), trading, book(104.5), neutral);
        assertThat(b.sell()).isTrue();
        assertThat(b.netPnl().doubleValue()).isGreaterThan(35.0);
    }

    @Test
    void floorOnlyMovesUp() {
        ExitDecision a = policy.evaluate(bought(), trading, book(106), neutral);
        ExitDecision b = policy.evaluate(a.updated(), trading, book(105.5), neutral);
        assertThat(b.updated().protectFloor()).isEqualByComparingTo(a.updated().protectFloor());
    }

    @Test
    void hardInvalidSellsEvenAtLoss() {
        SymbolInfo halted = new SymbolInfo("ABCUSDT", "ABC", "USDT", false, "");
        ExitDecision d = policy.evaluate(bought(), halted, book(95), neutral);
        assertThat(d.sell()).isTrue();
        assertThat(d.reason()).isEqualTo("HARD_INVALID_NOT_TRADING");
        assertThat(d.netPnl().signum()).isNegative();
    }

    @Test
    void liquidityHardInvalidNeedsTwoSeparateMonitorRounds() {
        Position p = bought();
        OrderBook thin = TestData.book("ABCUSDT", 95, 400, 1000, now);   // 价差 800bps
        ExitDecision first = policy.evaluate(p, trading, thin, neutral);
        assertThat(first.sell()).isFalse();
        assertThat(first.notes()).contains("LIQUIDITY_STRIKE_1");
        // 1 分钟后的快速确认不算第二轮
        assertThat(policy.evaluate(p, trading, TestData.book("ABCUSDT", 95, 400, 1000, now.plusSeconds(60)), neutral).sell())
                .isFalse();
        ExitDecision second = policy.evaluate(p, trading, TestData.book("ABCUSDT", 95, 400, 1000, now.plusSeconds(300)), neutral);
        assertThat(second.sell()).isTrue();
        assertThat(second.reason()).isEqualTo("HARD_INVALID_LIQUIDITY");
    }

    @Test
    void liquidityRecoveryResetsStrike() {
        Position p = bought();
        policy.evaluate(p, trading, TestData.book("ABCUSDT", 95, 400, 1000, now), neutral);
        policy.evaluate(p, trading, TestData.book("ABCUSDT", 95, 1, 1000, now.plusSeconds(300)), neutral);
        assertThat(policy.evaluate(p, trading, TestData.book("ABCUSDT", 95, 400, 1000, now.plusSeconds(600)), neutral).sell())
                .isFalse();
    }

    @Test
    void thesisDecayNeedsThreeConsecutiveWeakObservations() {
        Position p = bought();
        ExitDecision d1 = policy.evaluate(p, trading, book(101), weak);
        ExitDecision d2 = policy.evaluate(d1.updated(), trading, book(101), weak);
        assertThat(d2.sell()).isFalse();
        ExitDecision d3 = policy.evaluate(d2.updated(), trading, book(101), weak);
        assertThat(d3.sell()).isTrue();
        assertThat(d3.reason()).isEqualTo("THESIS_DECAY");
    }

    @Test
    void sameHourlyBarsCountOnlyOnce() {
        // 5 分钟轮次里同一份小时 K 线：只有第一次算有效观察
        ExitDecision d1 = policy.evaluate(bought(), trading, book(101), weak, true);
        ExitDecision d2 = policy.evaluate(d1.updated(), trading, book(101), weak, false);
        ExitDecision d3 = policy.evaluate(d2.updated(), trading, book(101), weak, false);
        assertThat(d3.sell()).isFalse();
        assertThat(d3.updated().weakStreak()).isEqualTo(1);
    }

    @Test
    void weakAndLosingEntersLossRecoveryInsteadOfSelling() {
        Position p = bought();
        ExitDecision d = null;
        for (int i = 0; i < 3; i++) {
            d = policy.evaluate(p, trading, book(97), weak);
            p = d.updated();
        }
        assertThat(d.sell()).isFalse();
        assertThat(d.notes()).contains("LOSS_RECOVERY");
        assertThat(d.updated().lossRecovery()).isTrue();
    }

    @Test
    void healthyObservationResetsWeakStreak() {
        ExitDecision d1 = policy.evaluate(bought(), trading, book(101), weak);
        ExitDecision d2 = policy.evaluate(d1.updated(), trading, book(101), neutral);
        assertThat(d2.updated().weakStreak()).isZero();
    }

    @Test
    void eightPercentReviewHoldsWhenStillStrongOtherwiseExits() {
        Position armedHigh = bought().withProtection(true, new BigDecimal("50"), new BigDecimal("85"));
        EarlySignal strong = signal(1, 2, 0.5, 0.1);
        assertThat(policy.evaluate(armedHigh, trading, book(109), strong).sell()).isFalse();
        EarlySignal fading = signal(-0.2, 2, -0.5, 0.1);
        ExitDecision d = policy.evaluate(armedHigh, trading, book(109), fading);
        assertThat(d.sell()).isTrue();
        assertThat(d.reason()).isEqualTo("TARGET_REVIEW_EXIT");
    }

    @Test
    void noFixedStopLoss() {
        ExitDecision d = policy.evaluate(bought(), trading, book(90), neutral);
        assertThat(d.sell()).isFalse();
    }

    private static EarlySignal signal(double rel1h, double rel4h, double accel, double ret15m) {
        return new EarlySignal("ABCUSDT", 100, rel1h, rel4h, accel, 1, 1, ret15m, 1, List.of(), false, 0);
    }
}
