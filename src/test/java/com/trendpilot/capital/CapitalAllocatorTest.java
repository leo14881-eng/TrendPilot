package com.trendpilot.capital;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.trendpilot.market.ExecutionSimulator.Fill;
import com.trendpilot.risk.Regime;
import com.trendpilot.risk.RiskState;
import com.trendpilot.support.TestData;

class CapitalAllocatorTest {

    private final CapitalAllocator allocator = new CapitalAllocator(TestData.params());

    private RiskState risk(Regime regime, double factor) {
        return new RiskState(regime, true, factor, 0, 0, 0, 0.5, 0, List.of());
    }

    @Test
    void availableFollowsRegimeAndNormalPool() {
        assertThat(allocator.available(risk(Regime.RISK_OFF, 1), 0, 0)).isEqualTo(12000);
        assertThat(allocator.available(risk(Regime.NEUTRAL, 1), 0, 0)).isEqualTo(15000);
        // STRONG 95% = 19000，但普通资金池只有 17000
        assertThat(allocator.available(risk(Regime.STRONG, 1), 0, 0)).isEqualTo(17000);
    }

    @Test
    void realizedLossShrinksPoolButProfitDoesNotExpandIt() {
        assertThat(allocator.available(risk(Regime.NEUTRAL, 1), 0, -500)).isEqualTo(14500);
        assertThat(allocator.available(risk(Regime.NEUTRAL, 1), 0, 800)).isEqualTo(15000);
        assertThat(allocator.available(risk(Regime.NEUTRAL, 0.5), 2000, 0)).isEqualTo(5500);
    }

    @Test
    void highestScoreIsFundedFirstAndRestWait() {
        ReviewResult a = result("A", 8.5);
        ReviewResult b = result("B", 9.5);
        ReviewResult c = result("C", 9.0);
        var alloc = allocator.allocate(List.of(a, b, c), risk(Regime.NEUTRAL, 1), 13000, 0); // 剩 2000
        assertThat(alloc.funded()).extracting(ReviewResult::symbol).containsExactly("B");
        assertThat(alloc.unfunded()).extracting(ReviewResult::symbol).containsExactlyInAnyOrder("A", "C");
    }

    private ReviewResult result(String symbol, double score) {
        Fill fill = new Fill(BigDecimal.TEN, new BigDecimal("1000"), new BigDecimal("100"), new BigDecimal("1"), 1, true);
        return new ReviewResult(symbol, Action.BUY, Decision.BUY, List.of(), List.of(), score, 2, 1, 1, 1e6, 1e6, fill);
    }
}
