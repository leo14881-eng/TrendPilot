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
    @Autowired Markets markets;

    @BeforeEach
    void reset() {
        market.reset();
        markets.invalidate();
        for (String t : new String[]{"btc_daily_plan", "btc_daily_state", "btc_daily_trade", "notification_outbox", "kline_15m"}) {
            jdbc.sql("DELETE FROM " + t).update();
        }
    }

    @Test
    void planThenBuyAddAndTakeProfit() {
        LocalDate day = LocalDate.parse("2026-10-15");
        long start = BtcDailyRules.dayStart(day);
        List<Kline> daily = new ArrayList<>(BtcDailyRulesTest.daily(day.minusDays(1), 40, 60_000, 2_000));
        daily.add(new Kline(start, 60_000, 60_000, 60_000, 60_000, 1e9, start + 86_399_999L)); // 今天未收盘
        market.daily.put("BTCUSDT", daily);

        // 07:01（UTC 00:01）：首次运行，生成计划并推送
        Instant t0 = Instant.ofEpochMilli(start + 60_000);
        market.quarters.put("BTCUSDT", quarters(start - 120 * Q, start, 60_000));
        service.tick(t0);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM btc_daily_plan").query(Integer.class).single()).isEqualTo(1);
        String planText = jdbc.sql("SELECT text FROM notification_outbox WHERE event_key = 'btc-daily-plan-2026-10-15'")
                .query(String.class).single();
        assertThat(planText).contains("买入策略", "卖出策略", "不止损", "58,800.00");

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
        // 独立账本：不写 V1/V2 的表
        assertThat(jdbc.sql("SELECT COUNT(*) FROM position").query(Integer.class).single()).isZero();
        assertThat(klines.latestOpenTime(Venue.BINANCE, "BTCUSDT")).isEqualTo(start + Q);
    }

    static List<Kline> quarters(long from, long to, double price) {
        List<Kline> out = new ArrayList<>();
        for (long t = from; t < to; t += Q) out.add(new Kline(t, price, price, price, price, 1e6, t + Q - 1));
        return out;
    }
}
