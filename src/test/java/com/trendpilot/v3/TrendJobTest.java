package com.trendpilot.v3;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

import com.trendpilot.market.Kline;
import com.trendpilot.market.Markets;
import com.trendpilot.portfolio.TrendBook;
import com.trendpilot.support.FakeMarket;
import com.trendpilot.support.TestData;

/** V3 端到端：大盘多头时买入创 20 日新高的币，1h 收盘跌破止损就按盘口卖出；大盘不好不买；数据缺失不买。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class TrendJobTest {

    static final Path dataDir = TestData.dataDir();
    static final long D = 86_400_000L;
    static final long H = 3_600_000L;
    static final Instant NOW = Instant.parse("2026-10-17T00:03:00Z");
    static final long TODAY = Instant.parse("2026-10-17T00:00:00Z").toEpochMilli();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
        r.add("trendpilot.v3.enabled", () -> "false"); // 不让定时任务自己跑
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
    @Autowired Markets markets;
    @Autowired TrendJob job;
    @Autowired TrendBook book;
    @Autowired JdbcClient jdbc;

    /** n 根已收盘日线，收盘从 start 每天乘 (1+step)；最后一根可以单独指定收盘和成交额。 */
    static List<Kline> daily(int n, double start, double step, double lastClose, double lastVolume) {
        List<Kline> out = new ArrayList<>();
        double c = start;
        for (int i = 0; i < n; i++) {
            long open = TODAY - (n - i) * D;
            double prev = c;
            c = i == n - 1 ? lastClose : c * (1 + step);
            double v = i == n - 1 ? lastVolume : 1_000_000;
            out.add(new Kline(open, prev, Math.max(prev, c) * 1.01, Math.min(prev, c) * 0.99, c, v, open + D - 1));
        }
        return out;
    }

    void coin(String sym, List<Kline> d) {
        market.put(sym, 1, List.of(d.getLast()), TestData.book(sym, d.getLast().close(), 2, 1_000_000, NOW));
        market.daily.put(sym, d);
    }

    @BeforeEach
    void reset() {
        market.reset();
        markets.invalidate();
        jdbc.sql("DELETE FROM v3_position").update();
        jdbc.sql("DELETE FROM v3_state").update();
    }

    void bullMarket() {
        market.daily.put("BTCUSDT", daily(62, 50_000, 0.003, 60_500, 1e9));
        market.put("BTCUSDT", 1, List.of(market.daily.get("BTCUSDT").getLast()), null);
        // 突破：前 60 天缓涨到约 1.35，最后一天收 1.6（超过前 20 天高点），成交额 3 倍
        coin("ABCUSDT", daily(62, 1.0, 0.005, 1.6, 3_000_000));
        coin("DEFUSDT", daily(62, 2.0, 0.002, 2.25, 1_000_000)); // 上涨但没突破
    }

    @Test
    void buysBreakoutInBullMarketAndStopsOnHourClose() {
        bullMarket();
        List<String> done = job.runDaily(NOW);
        assertThat(done).hasSize(1);
        assertThat(done.getFirst()).contains("ABCUSDT", "V3 影子买入");
        assertThat(book.positions()).extracting(p -> p.symbol()).containsExactly("ABCUSDT");
        TrendJob.State st = job.state(book.positions().getFirst());
        double dist = 1 - st.initStop() / st.entry();
        assertThat(dist).isBetween(0.05, 0.15);
        assertThat(book.positions().getFirst().costBasis().doubleValue()).isBetween(1_300.0, 3_010.0); // 200 U 风险 / 距离，上限 3,000

        // 1h 收盘还在止损线上方：持有，只更新最高价
        market.hourly.put("ABCUSDT", List.of(new Kline(TODAY + H, 1.6, 1.7, 1.58, 1.65, 1e5, TODAY + 2 * H - 1)));
        assertThat(job.runHourly(Instant.ofEpochMilli(TODAY + 2 * H + 120_000))).isEmpty();
        assertThat(job.state(book.positions().getFirst()).peak()).isEqualTo(1.7);
        // 跌破止损线：按盘口卖出
        double below = st.initStop() * 0.98;
        market.books.put("ABCUSDT", TestData.book("ABCUSDT", below, 2, 1_000_000, NOW));
        markets.invalidate();
        market.hourly.put("ABCUSDT", List.of(new Kline(TODAY + 2 * H, 1.65, 1.66, below, below, 1e5, TODAY + 3 * H - 1)));
        List<String> sold = job.runHourly(Instant.ofEpochMilli(TODAY + 3 * H + 120_000));
        assertThat(sold).hasSize(1);
        assertThat(sold.getFirst()).contains("止损");
        assertThat(book.positions()).isEmpty();
    }

    @Test
    void noEntriesWhenBtcBelowFiftyDayAverage() {
        bullMarket();
        market.daily.put("BTCUSDT", daily(62, 70_000, -0.003, 55_000, 1e9));
        assertThat(job.runDaily(NOW)).isEmpty();
        assertThat(book.positions()).isEmpty();
    }

    @Test
    void noEntriesWhenBtcDataMissing() {
        bullMarket();
        market.daily.remove("BTCUSDT");
        assertThat(job.runDaily(NOW)).isEmpty();
        assertThat(book.positions()).isEmpty();
    }
}
