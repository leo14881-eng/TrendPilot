package com.trendpilot.market.stream;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StreamConnectionTest {

    @Test
    void backoffDoublesWithJitterAndCaps() {
        assertThat(StreamConnection.backoffMs(1, 0.5)).isEqualTo(2_000);
        assertThat(StreamConnection.backoffMs(2, 0.5)).isEqualTo(4_000);
        assertThat(StreamConnection.backoffMs(1, 0.0)).isEqualTo(1_600);
        assertThat(StreamConnection.backoffMs(1, 1.0)).isEqualTo(2_400);
        assertThat(StreamConnection.backoffMs(20, 0.5)).isEqualTo(300_000);
        assertThat(StreamConnection.backoffMs(20, 1.0)).isEqualTo(360_000);
    }

    @Test
    void staleOnlyWhenConnectedAndSilent() {
        long now = 1_000_000_000L;
        assertThat(StreamConnection.stale(true, now - StreamConnection.STALE_MS - 1, now)).isTrue();
        assertThat(StreamConnection.stale(true, now - 30_000, now)).isFalse();
        assertThat(StreamConnection.stale(false, 0, now)).isFalse(); // 没连上时由退避重连负责
    }
}
