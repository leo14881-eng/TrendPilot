package com.trendpilot.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.trendpilot.support.TestData;

class ExecutionSimulatorTest {

    private final OrderBook book = TestData.book("X", 100, 5, 10, Instant.now()); // 每档约 1000U

    @Test
    void buyWalksAsksAcrossLevels() {
        var fill = ExecutionSimulator.buy(book, new BigDecimal("2500"), 0.001);
        assertThat(fill.complete()).isTrue();
        assertThat(fill.notional()).isEqualByComparingTo("2500");
        assertThat(fill.fee().doubleValue()).isCloseTo(2.5, offset(1e-6));
        // 吃了三档，均价高于最优卖价
        assertThat(fill.avgPrice().doubleValue()).isGreaterThan(100.05);
        assertThat(fill.slippageBps()).isGreaterThan(5);
    }

    @Test
    void sellReportsIncompleteWhenBookTooThin() {
        var fill = ExecutionSimulator.sell(book, new BigDecimal("500"), 0.001);
        assertThat(fill.complete()).isFalse();
        assertThat(fill.qty()).isEqualByComparingTo("200");
    }

    @Test
    void sellNetProceedsSubtractFee() {
        var fill = ExecutionSimulator.sell(book, new BigDecimal("5"), 0.001);
        assertThat(fill.netProceeds().doubleValue()).isCloseTo(fill.notional().doubleValue() * 0.999, offset(1e-6));
        assertThat(fill.slippageBps()).isCloseTo(5, offset(0.01));
    }
}
