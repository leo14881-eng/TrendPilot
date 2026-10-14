package com.trendpilot.btcdaily;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

import com.trendpilot.market.BarsLoader;
import com.trendpilot.market.Kline;
import com.trendpilot.market.KlineStore;
import com.trendpilot.market.Markets;
import com.trendpilot.market.Venue;
import com.trendpilot.support.FakeMarket;
import com.trendpilot.support.TestData;

/** 端到端：生成当天计划并推送 → 后续 15m K 线触发买入、加仓、止盈，账本独立落库。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class BtcDailyServiceTest {

    static final Path dataDir = TestData.dataDir();
    static final long Q = 15 * 60_000L;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
        r.add("trendpilot.telegram.bot-token", () -> "");
        r.add("trendpilot.telegram.chat-id", () -> "");
        // 外部数据源指向本机不存在的端口：立即失败，记为缺失
        r.add("trendpilot.btc-daily.filters.binance-futures-url", () -> "http://127.0.0.1:9");
        r.add("trendpilot.btc-daily.filters.fear-greed-url", () -> "http://127.0.0.1:9");
        r.add("trendpilot.btc-daily.filters.farside-url", () -> "http://127.0.0.1:9/btc/");
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
    @Autowired BtcDailyService service;
    @Autowired JdbcClient jdbc;
    @Autowired KlineStore klines;
    @Autowired BtcDailyStore store;
    @Autowired Markets markets;
    @Autowired ContextService context;

    @BeforeEach
    void reset() {
        market.reset();
        markets.invalidate();
        for (String t : new String[]{"btc_daily_plan", "btc_daily_state", "btc_daily_trade", "btc_daily_series", "btc_daily_fetch", "btc_daily_round", "notification_outbox", "kline_15m"}) {
            jdbc.sql("DELETE FROM " + t).update();
        }
    }

    @Test
    void planThenBuyAddAndTakeProfit() {
        LocalDate day = LocalDate.parse("2026-10-15");
        long start = BtcDailyRules.dayStart(day);
        List<Kline> daily = new ArrayList<>(BtcDailyRulesTest.daily(day.minusDays(1), 80, 60_000, 2_000));
        daily.add(new Kline(start, 60_000, 60_000, 60_000, 60_000, 1e9, start + 86_399_999L)); // 今天未收盘
        market.daily.put("BTCUSDT", daily);

        // 07:01（UTC 00:01）：首次运行，生成计划并推送
        Instant t0 = Instant.ofEpochMilli(start + 60_000);
        market.quarters.put("BTCUSDT", quarters(start - 120 * Q, start, 60_000));
        service.tick(t0);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM btc_daily_plan").query(Integer.class).single()).isEqualTo(1);
        String planText = jdbc.sql("SELECT text FROM notification_outbox WHERE event_key = 'btc-daily-plan-2026-10-15'")
                .query(String.class).single();
        assertThat(planText).contains("买入策略", "卖出策略", "不止损", "58,800.00", "数据缺失");

        // 当天：跌到第 2 档以下，再反弹到止盈价以上
        List<Kline> q = new ArrayList<>(quarters(start - 120 * Q, start, 60_000));
        q.add(new Kline(start, 60_000, 60_000, 57_700, 57_900, 1e6, start + Q - 1));
        q.add(new Kline(start + Q, 57_900, 60_000, 57_850, 59_900, 1e6, start + 2 * Q - 1));
        market.quarters.put("BTCUSDT", q);
        service.tick(Instant.ofEpochMilli(start + 2 * Q + 60_000));

        List<String> sides = jdbc.sql("SELECT side FROM btc_daily_trade ORDER BY id").query(String.class).list();
        assertThat(sides).containsExactly("BUY", "BUY", "SELL");
        Map<String, Object> s = service.summary(Instant.ofEpochMilli(start + 2 * Q + 60_000));
        assertThat((double) s.get("realized")).isPositive();
        assertThat(s.get("rounds")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE event_key LIKE 'btc-daily-fill-%'")
                .query(Integer.class).single()).isEqualTo(3);
        Map<String, Object> round = jdbc.sql("SELECT * FROM btc_daily_round").query().singleRow();
        assertThat(round.get("buys")).isEqualTo(2);
        assertThat(((Number) round.get("hold_minutes")).longValue()).isEqualTo(30);
        assertThat((String) round.get("open_filters")).contains("数据缺失");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM btc_daily_trade WHERE filled_at IS NOT NULL AND filters LIKE '%数据缺失%'")
                .query(Integer.class).single()).isEqualTo(3);
        assertThat(s).containsKeys("roundStats", "recentRounds");
        // 独立账本：不写 V1/V2 的表
        assertThat(jdbc.sql("SELECT COUNT(*) FROM position").query(Integer.class).single()).isZero();
        assertThat(klines.latestOpenTime(Venue.BINANCE, "BTCUSDT")).isEqualTo(start + Q);
    }

    @Test
    void longDowntimeBackfillsMissedBars() {
        LocalDate day = LocalDate.parse("2026-10-15");
        long start = BtcDailyRules.dayStart(day);
        List<Kline> daily = new ArrayList<>(BtcDailyRulesTest.daily(day.minusDays(1), 80, 60_000, 2_000));
        for (int d = 0; d < 4; d++) {
            long s = start + d * 86_400_000L;
            daily.add(new Kline(s, 60_000, 60_000, 60_000, 60_000, 1e9, s + 86_399_999L));
        }
        market.daily.put("BTCUSDT", daily);
        market.quarters.put("BTCUSDT", quarters(start - 120 * Q, start + 3 * 96 * Q + Q, 60_000));
        service.tick(Instant.ofEpochMilli(start + 60_000));
        // 停机 3 天（288 根，超过缓存自动补的 104 根）后再运行：中间的 K 线全部按时间段补齐并处理
        Instant later = Instant.ofEpochMilli(start + 3 * 96 * Q + 60_000);
        service.tick(later);
        long lastClosed = BarsLoader.lastClosedOpenTime(later);
        assertThat(klines.range(Venue.BINANCE, "BTCUSDT", start, lastClosed)).hasSize((int) ((lastClosed - start) / Q) + 1);
        assertThat(store.state().lastBarOpen()).isEqualTo(lastClosed);
    }

    @Test
    void contextUsesOnlyPublishedData() {
        LocalDate day = LocalDate.parse("2026-10-15");
        context.save(ContextService.FUNDING, java.util.Map.of(day.minusDays(1), 0.04, day, 0.5));
        context.save(ContextService.FNG, java.util.Map.of(day.minusDays(1), 30.0));
        context.save(ContextService.ETF, java.util.Map.of(day.minusDays(4), -200.0, day.minusDays(3), -500.0,
                day.minusDays(2), -400.0, day.minusDays(1), 900.0));
        MarketContext c = context.context(day);
        assertThat(c.fundingPct()).isEqualTo(0.04);     // 用前一天，不用当天
        assertThat(c.fearGreed()).isEqualTo(30.0);
        assertThat(c.etfDay()).isEqualTo(day.minusDays(2)); // 前一天的 ETF 数据计划时还没公布
        assertThat(c.etf3dM()).isEqualTo(-1100.0);
        MarketContext.Gate g = c.gate(false, BtcDailyProperties.Filters.defaults());
        assertThat(g.skipNew()).isTrue();
        assertThat(g.multiplier()).isEqualTo(0.25);
    }

    static List<Kline> quarters(long from, long to, double price) {
        List<Kline> out = new ArrayList<>();
        for (long t = from; t < to; t += Q) out.add(new Kline(t, price, price, price, price, 1e6, t + Q - 1));
        return out;
    }
}
