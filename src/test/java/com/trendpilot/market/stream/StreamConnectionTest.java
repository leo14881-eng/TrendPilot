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

    @Test
    void overlappingPacedBatchesShareOneQueue() {
        var scheduler = org.mockito.Mockito.mock(java.util.concurrent.ScheduledExecutorService.class);
        var c = new StreamConnection("X", java.net.URI.create("wss://x.test"), m -> { }, x -> { }, scheduler);
        // 连上后的全量重订阅和订阅更新同时到来：合起来仍是每 300ms 一条，而不是两路叠加
        c.sendPaced(java.util.List.of("a", "b", "c"), 300, 10_000L);
        c.sendPaced(java.util.List.of("d", "e"), 300, 10_040L); // 第二路晚到 40ms
        var delays = org.mockito.ArgumentCaptor.forClass(Long.class);
        org.mockito.Mockito.verify(scheduler, org.mockito.Mockito.times(5))
                .schedule(org.mockito.ArgumentMatchers.any(Runnable.class), delays.capture(),
                        org.mockito.ArgumentMatchers.eq(java.util.concurrent.TimeUnit.MILLISECONDS));
        java.util.List<Long> d = delays.getAllValues();
        for (int i = 1; i < d.size(); i++) {
            assertThat(d.get(i) - d.get(i - 1)).isBetween(260L, 300L);
        }
        assertThat(d.get(4)).isEqualTo(1160L);
    }
}
