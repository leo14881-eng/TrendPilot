package com.trendpilot.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trendpilot.support.TestData;

/** K 线缓存只收价格合理的 K 线；库里已有的坏数据读出来时也当作缺失。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class KlineStoreValidityTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
    }

    @Autowired KlineStore store;
    @Autowired JdbcClient jdbc;

    @Test
    void brokenBarsAreNotStoredOrServed() {
        long q = BarsLoader.QUARTER_MS;
        store.upsert(Venue.BINANCE, "KSVUSDT", List.of(
                new Kline(0, 1, 1.1, 0.9, 1, 10, q - 1),
                new Kline(q, 0, 0, 0, 0, 0, 2 * q - 1),
                new Kline(2 * q, 1, 1.1, 0.9, 1, 10, 3 * q - 1)));
        assertThat(store.range(Venue.BINANCE, "KSVUSDT", 0, 2 * q)).extracting(Kline::openTime).containsExactly(0L, 2 * q);
        jdbc.sql("""
                INSERT INTO kline_15m(venue, symbol, open_time, open, high, low, close, quote_volume, close_time)
                VALUES ('BINANCE', 'KSVUSDT', :t, 0, 0, 0, 0, 0, :c)""").param("t", 3 * q).param("c", 4 * q - 1).update();
        assertThat(store.range(Venue.BINANCE, "KSVUSDT", 0, 3 * q)).extracting(Kline::openTime).containsExactly(0L, 2 * q);
    }
}
