package com.trendpilot.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class SymbolDirectoryTest {

    static final class Source implements MarketDataSource {
        List<SymbolInfo> next = List.of();
        final AtomicInteger calls = new AtomicInteger();

        public Venue venue() { return Venue.BINANCE; }
        public List<SymbolInfo> symbols() { calls.incrementAndGet(); return next; }
        public List<Ticker24h> tickers24h() { return List.of(); }
        public List<Kline> klines(String s, String i, Long st, int l) { return List.of(); }
        public OrderBook depth(String s, int l) { return null; }
    }

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-15T00:00:00Z");
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

    private static List<SymbolInfo> symbols(int n) {
        List<SymbolInfo> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new SymbolInfo("S" + i + "USDT", "S" + i, "USDT", true, ""));
        return out;
    }

    @Test
    void rejectsEmptyOrShrunkListsInsteadOfMarkingEverythingDelisted() {
        Source src = new Source();
        MutableClock clock = new MutableClock();
        SymbolDirectory dir = new SymbolDirectory(src, clock);

        src.next = List.of();
        assertThatThrownBy(() -> dir.get("S1USDT")).isInstanceOf(MarketDataException.class);
        // 失败记 30 秒：同一轮不重复请求
        assertThatThrownBy(() -> dir.get("S1USDT")).isInstanceOf(MarketDataException.class);
        assertThat(src.calls).hasValue(1);

        clock.now = clock.now.plusSeconds(31);
        src.next = symbols(100);
        assertThat(dir.get("S1USDT").trading()).isTrue();

        // 30 分钟后一份只剩一半的列表（维护或半截响应）：拒绝，持仓不会因此被当成下架
        clock.now = clock.now.plusSeconds(31 * 60);
        src.next = symbols(50);
        assertThatThrownBy(() -> dir.get("S1USDT")).hasMessageContaining("骤降");
        clock.now = clock.now.plusSeconds(31);
        src.next = symbols(95);
        assertThat(dir.get("S99USDT")).isNull(); // 正常的少量下架照常反映
        assertThat(dir.get("S1USDT")).isNotNull();
    }
}
