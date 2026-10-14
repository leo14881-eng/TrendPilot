package com.trendpilot.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trendpilot.support.FakeMarket;
import com.trendpilot.support.TestData;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class BarsLoaderTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeMarket fakeMarket() {
            return new FakeMarket();
        }
    }

    @Autowired FakeMarket market;
    @Autowired BarsLoader loader;
    @Autowired JdbcClient jdbc;

    private final Instant t0 = Instant.parse("2026-10-14T10:17:00Z");

    @BeforeEach
    void reset() {
        market.reset();
        jdbc.sql("DELETE FROM kline_15m").update();
    }

    @Test
    void hourlyBarsAreBuiltFromQuarters() {
        List<Kline> q = TestData.strongQuarters(t0);
        List<Kline> hourly = BarsLoader.toHourly(q);
        assertThat(hourly).hasSize(25); // 104 根 15m 覆盖 25 个完整小时
        Kline last = hourly.getLast();
        assertThat(last.openTime() % BarsLoader.HOUR_MS).isZero();
        assertThat(last.open()).isEqualTo(102.5);
        assertThat(last.close()).isEqualTo(104);
        assertThat(last.closeTime()).isEqualTo(last.openTime() + BarsLoader.HOUR_MS - 1);
    }

    @Test
    void secondLoadInSameQuarterUsesCacheOnly() {
        market.put("ABCUSDT", 1, TestData.strongQuarters(t0), null);
        BarsLoader.Result first = loader.load(Set.of("ABCUSDT"), t0);
        assertThat(first.complete()).isTrue();
        assertThat(first.requests()).isEqualTo(1);

        BarsLoader.Result second = loader.load(Set.of("ABCUSDT"), t0.plusSeconds(600));
        assertThat(second.requests()).isZero();
        assertThat(second.bars().get("ABCUSDT")).isEqualTo(first.bars().get("ABCUSDT"));
    }

    @Test
    void nextQuarterFetchesOnlyNewBars() {
        market.put("ABCUSDT", 1, TestData.strongQuarters(t0), null);
        loader.load(Set.of("ABCUSDT"), t0);

        Instant t1 = t0.plusSeconds(15 * 60);
        market.put("ABCUSDT", 1, TestData.strongQuarters(t1), null);
        BarsLoader.Result r = loader.load(Set.of("ABCUSDT"), t1);
        assertThat(r.requests()).isEqualTo(1);
        assertThat(r.complete()).isTrue();
        assertThat(r.bars().get("ABCUSDT").quarter().getLast().openTime())
                .isEqualTo(BarsLoader.lastClosedOpenTime(t1));
    }

    @Test
    void gapInCacheTriggersOneFullRefetch() {
        List<Kline> q = TestData.strongQuarters(t0);
        List<Kline> withHole = new ArrayList<>(q);
        withHole.remove(50);
        market.put("ABCUSDT", 1, withHole, null);
        BarsLoader.Result broken = loader.load(Set.of("ABCUSDT"), t0);
        assertThat(broken.missing()).containsExactly("ABCUSDT"); // 交易所数据本身缺一根：记为缺失
        assertThat(broken.requests()).isEqualTo(1);

        market.put("ABCUSDT", 1, q, null);
        BarsLoader.Result fixed = loader.load(Set.of("ABCUSDT"), t0);
        assertThat(fixed.complete()).isTrue();
        assertThat(fixed.requests()).isEqualTo(1); // 缓存有缺口 → 整段重拉
    }

    @Test
    void staleSourceIsMissingNotSuccess() {
        // 交易所只给到 30 分钟前的 K 线
        market.put("ABCUSDT", 1, TestData.strongQuarters(t0.minusSeconds(30 * 60)), null);
        assertThat(loader.load(Set.of("ABCUSDT"), t0).missing()).containsExactly("ABCUSDT");
    }
}
