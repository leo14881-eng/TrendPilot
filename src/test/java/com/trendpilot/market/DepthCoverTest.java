package com.trendpilot.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 盘口先取 100 档，覆盖不了 ±2.5% 或全数量卖出时才取 500 档。 */
class DepthCoverTest {

    private static OrderBook book(int levels, double stepPct, double qty) {
        List<OrderBook.Level> bids = new ArrayList<>();
        List<OrderBook.Level> asks = new ArrayList<>();
        for (int i = 0; i < levels; i++) {
            bids.add(new OrderBook.Level(BigDecimal.valueOf(100 * (1 - stepPct * (i + 1) / 100)), BigDecimal.valueOf(qty)));
            asks.add(new OrderBook.Level(BigDecimal.valueOf(100 * (1 + stepPct * (i + 1) / 100)), BigDecimal.valueOf(qty)));
        }
        return new OrderBook("ABCUSDT", bids, asks, Instant.now());
    }

    @Test
    void shortBookIsWholeBook() {
        assertThat(DepthCache.covers(book(30, 0.01, 1), 2.5, 1e9)).isTrue();
    }

    @Test
    void fullHundredLevelsMustSpanTheBand() {
        assertThat(DepthCache.covers(book(100, 0.03, 10), 2.5, 0)).isTrue();    // 覆盖 ±3%
        assertThat(DepthCache.covers(book(100, 0.01, 10), 2.5, 0)).isFalse();   // 只覆盖 ±1%
        assertThat(DepthCache.covers(book(100, 0.03, 10), 2.5, 1e6)).isFalse(); // 买盘不够全数量卖出
    }
}
