package com.trendpilot.signal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.trendpilot.support.TestData;

class EarlySignalCalculatorTest {

    private final EarlySignalCalculator calc = new EarlySignalCalculator(TestData.params());
    private final Instant now = Instant.parse("2026-10-14T10:17:00Z");

    @Test
    void strongSymbolHitsAllConditionsWithFullScore() {
        EarlySignal s = calc.compute(TestData.strong("ABCUSDT", now), TestData.flat("BTCUSDT", now, 60000));

        assertThat(s.hits()).containsExactlyInAnyOrder("REL_1H", "REL_4H", "ACCEL", "VOLUME", "COMPRESSION", "MOMENTUM");
        assertThat(s.early()).isTrue();
        assertThat(s.rel1h()).isCloseTo(2.97, org.assertj.core.data.Offset.offset(0.01));
        assertThat(s.rel4h()).isCloseTo(4.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(s.volumeAccel()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(s.score()).isEqualTo(10.0);
    }

    @Test
    void flatSymbolIsNotEarly() {
        EarlySignal s = calc.compute(TestData.flat("XYZUSDT", now, 5), TestData.flat("BTCUSDT", now, 60000));
        assertThat(s.early()).isFalse();
        assertThat(s.hits()).isEmpty();
    }

    @Test
    void returnsAreRelativeToBtc() {
        // BTC 与标的同涨，相对收益为 0
        var sym = TestData.strong("ABCUSDT", now);
        var btc = TestData.strong("BTCUSDT", now);
        EarlySignal s = calc.compute(sym, btc);
        assertThat(s.rel1h()).isCloseTo(0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(s.hits()).doesNotContain("REL_1H", "REL_4H", "ACCEL");
    }

    @Test
    void notEnoughBarsMeansMissingNotNoSignal() {
        var b = TestData.strong("ABCUSDT", now);
        var shortBars = new com.trendpilot.market.SymbolBars("ABCUSDT", b.hourly().subList(0, 10), b.quarter());
        assertThat(calc.compute(shortBars, TestData.flat("BTCUSDT", now, 60000))).isNull();
    }
}
