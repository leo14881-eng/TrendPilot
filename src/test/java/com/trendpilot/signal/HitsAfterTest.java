package com.trendpilot.signal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.trendpilot.market.SymbolBars;
import com.trendpilot.support.TestData;

/** 再入场：命中条件要有一项完全基于卖出后的 K 线。 */
class HitsAfterTest {

    private final Instant now = Instant.parse("2026-10-14T10:17:00Z");
    private final SymbolBars bars = TestData.strong("ABCUSDT", now);
    private final EarlySignal s = new EarlySignal("ABCUSDT", 1, 2, 3, 1, 2, 1, 0.1, 1,
            List.of("REL_1H", "REL_4H", "VOLUME"), true, 8);

    @Test
    void exitBeforeTheWindowsCountsShortHits() {
        assertThat(EarlySignalCalculator.hitsAfter(bars, s, now.minus(Duration.ofHours(5))))
                .containsExactly("REL_1H", "VOLUME");
    }

    @Test
    void exitInsideTheWindowsCountsNothing() {
        assertThat(EarlySignalCalculator.hitsAfter(bars, s, now.minus(Duration.ofMinutes(30)))).isEmpty();
    }
}
