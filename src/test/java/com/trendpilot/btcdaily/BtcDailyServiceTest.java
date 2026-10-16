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
        // 正式配置已固定为 V2；这里仍按 AUTO 测试"按回测结论选策略"的机制
        r.add("trendpilot.btc-daily.strategy", () -> "AUTO");
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
        service.forgetLiveExit();
        jdbc.sql("DELETE FROM btc_daily_backtest").update();
        for (String t : new String[]{"btc_daily_plan", "btc_daily_state", "btc_daily_trade", "btc_daily_series", "btc_daily_fetch", "btc_daily_round", "notification_outbox", "kline_15m"}) {
            jdbc.sql("DELETE FROM " + t).update();
        }
    }

    /** 计划日需要的市场数据都在（资金费率、恐惧贪婪、ETF 资金流），按正常口径出计划。 */
    void seedContext(LocalDate day) {
        String sql = "INSERT OR REPLACE INTO btc_daily_series(source, day, value) VALUES (:s, :d, :v)";
        jdbc.sql(sql).param("s", "funding").param("d", day.minusDays(1).toString()).param("v", 0.01).update();
        jdbc.sql(sql).param("s", "fng").param("d", day.minusDays(1).toString()).param("v", 50.0).update();
        for (int i = 2; i <= 4; i++) {
            jdbc.sql(sql).param("s", "etf").param("d", day.minusDays(i).toString()).param("v", 10.0).update();
        }
    }

    @Test
    void missingEtfFlowMeansNoNewRound() {
        LocalDate day = LocalDate.parse("2026-10-15");
        long start = BtcDailyRules.dayStart(day);
        List<Kline> daily = new ArrayList<>(BtcDailyRulesTest.daily(day.minusDays(1), 80, 60_000, 2_000));
        daily.add(new Kline(start, 60_000, 60_000, 60_000, 60_000, 1e9, start + 86_399_999L));
        market.daily.put("BTCUSDT", daily);
        market.quarters.put("BTCUSDT", quarters(start - 120 * Q, start, 60_000));
        service.tick(Instant.ofEpochMilli(start + 60_000)); // 外部数据源全部拿不到
        assertThat(jdbc.sql("SELECT skip_new FROM btc_daily_plan").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT reasons FROM btc_daily_plan").query(String.class).single())
                .contains("数据缺失（ETF 资金流）：不开新一轮");
    }

    @Test
    void planThenBuyAddAndTakeProfit() {
        LocalDate day = LocalDate.parse("2026-10-15");
        long start = BtcDailyRules.dayStart(day);
        List<Kline> daily = new ArrayList<>(BtcDailyRulesTest.daily(day.minusDays(1), 80, 60_000, 2_000));
        daily.add(new Kline(start, 60_000, 60_000, 60_000, 60_000, 1e9, start + 86_399_999L)); // 今天未收盘
        market.daily.put("BTCUSDT", daily);
        seedContext(day);

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
    void liveExitStaysFixedWhileConfigSaysFixed() {
        // 配置为 FIXED（等用户确认新设计）：即使回测结论是 TRAIL 也不切换
        assertThat(service.live().exit().mode()).isEqualTo("FIXED");
        store.saveBacktest(service.live().version(), "{\"liveExit\":\"TRAIL\"}", Instant.now());
        assertThat(service.live().exit().mode()).isEqualTo("FIXED");
        assertThat(service.summary(Instant.now())).containsEntry("exitMode", "FIXED");
    }

    @Test
    void strategyFollowsBacktestChoice() {
        assertThat(service.live().strategy()).isEqualTo("V2"); // 回测前用 V2
        store.saveBacktest(service.live().version(), "{\"liveStrategy\":\"V3\",\"liveExit\":\"FIXED\"}", Instant.now());
        assertThat(service.live().isV3()).isTrue();
        assertThat(service.summary(Instant.now())).containsEntry("strategy", "V3");
    }

    @Test
    void v3PlanUsesHourlyBarsWhenLive() {
        LocalDate day = LocalDate.parse("2026-10-15");
        long start = BtcDailyRules.dayStart(day);
        List<Kline> daily = new ArrayList<>(BtcDailyRulesTest.daily(day.minusDays(1), 80, 60_000, 2_000));
        daily.add(new Kline(start, 60_000, 60_000, 60_000, 60_000, 1e9, start + 86_399_999L));
        market.daily.put("BTCUSDT", daily);
        market.hourly.put("BTCUSDT", BtcDailyRulesTest.hourlyFlat(day, 72, 60_000, 400));
        market.quarters.put("BTCUSDT", quarters(start - 120 * Q, start, 60_000));
        store.saveBacktest(service.live().version(), "{\"liveStrategy\":\"V3\"}", Instant.now());
        service.tick(Instant.ofEpochMilli(start + 60_000));
        DailyPlan p = store.plan(day).orElseThrow();
        assertThat(p.strategy()).isEqualTo("V3");
        assertThat(p.regime()).isEqualTo("SIDE");
        assertThat(p.hourAtr()).isCloseTo(400, org.assertj.core.api.Assertions.within(1e-6));
        assertThat(jdbc.sql("SELECT text FROM notification_outbox WHERE event_key = 'btc-daily-plan-2026-10-15'")
                .query(String.class).single()).contains("策略 v3", "先卖 1/3", "1h ATR");
    }

    @Test
    void newStrategyNeedsEnoughCompletedRounds() {
        BtcDailyBacktest.Result base = result(-800, 3900, 271);
        BtcDailyBacktest.Result idle = new BtcDailyBacktest.Result("v", null, null, 365, 0, 0, 0, 0, 268, 0, -800, 10000,
                1, 0, 0, 0, List.of(), "X", -700, -7, 2300, 23, 0, 0, 0, 0, 0, 0, 0, "V3", Map.of(), Map.of());
        assertThat(BtcDailyService.notWorse(idle, base)).isFalse(); // 一年只做 1 笔、被套：不算更好
        assertThat(BtcDailyService.notWorse(result(-700, 2300, 268), base)).isTrue();
    }

    @Test
    void decideExitRequiresNotWorseThanFixed() {
        BtcDailyProperties.Exit x = BtcDailyProperties.defaults().exit().withMode("AUTO");
        Map<String, BtcDailyBacktest.Result> exits = new java.util.LinkedHashMap<>();
        exits.put("FIXED", result(500, 800, 30));
        exits.put("TRAIL", result(650, 820, 31));
        assertThat(BtcDailyService.decideExit(x, exits)).isEqualTo("TRAIL");
        exits.put("TRAIL", result(450, 700, 20));   // 赚得少
        assertThat(BtcDailyService.decideExit(x, exits)).isEqualTo("FIXED");
        exits.put("TRAIL", result(700, 1000, 30));  // 回撤大很多
        assertThat(BtcDailyService.decideExit(x, exits)).isEqualTo("FIXED");
        exits.put("TRAIL", result(700, 800, 40));   // 被套更久
        assertThat(BtcDailyService.decideExit(x, exits)).isEqualTo("FIXED");
        // AUTO：达标的里选总盈亏最高
        exits.put("TRAIL", result(600, 800, 30));
        exits.put("PARTIAL", result(900, 850, 31));
        assertThat(BtcDailyService.decideExit(x, exits)).isEqualTo("PARTIAL");
        exits.put("PARTIAL", result(900, 2000, 31));
        assertThat(BtcDailyService.decideExit(x, exits)).isEqualTo("TRAIL");
        assertThat(BtcDailyService.decideExit(x.withMode("FIXED"), exits)).isEqualTo("FIXED");
    }

    static BtcDailyBacktest.Result result(double total, double dd, long hold) {
        return new BtcDailyBacktest.Result("v", null, null, 365, 100, total, 0, 0, hold, 1, 0, 0, 100, 0, 0, 0,
                List.of(), "X", total, total / 100, dd, dd / 100, 90, 10, -5, 2, 1, 0, 0, "V2", Map.of(), Map.of());
    }

    @Test
    void trailPeakSurvivesRestart() {
        Book b = new Book();
        b.qty = 0.1;
        b.cost = 6000;
        b.trailPeak = 61_234.5;
        store.save(b, 1L, List.of(), "v", Instant.now());
        assertThat(store.state().book().trailPeak).isEqualTo(61_234.5);
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
    void midGapStopsBeforeTheHoleUntilItIsFilled() {
        LocalDate day = LocalDate.parse("2026-10-15");
        long start = BtcDailyRules.dayStart(day);
        List<Kline> daily = new ArrayList<>(BtcDailyRulesTest.daily(day.minusDays(1), 80, 60_000, 2_000));
        daily.add(new Kline(start, 60_000, 60_000, 60_000, 60_000, 1e9, start + 86_399_999L));
        market.daily.put("BTCUSDT", daily);
        market.quarters.put("BTCUSDT", quarters(start - 120 * Q, start, 60_000));
        service.tick(Instant.ofEpochMilli(start + 60_000));

        // 交易所暂时少了第 2 根：停在缺口前，不跳过
        List<Kline> q = new ArrayList<>(quarters(start - 120 * Q, start + 4 * Q, 60_000));
        q.removeIf(k -> k.openTime() == start + Q);
        market.quarters.put("BTCUSDT", q);
        service.tick(Instant.ofEpochMilli(start + 4 * Q + 60_000));
        assertThat(store.state().lastBarOpen()).isEqualTo(start);

        // 补到了：接着处理到最新
        market.quarters.put("BTCUSDT", quarters(start - 120 * Q, start + 4 * Q, 60_000));
        service.tick(Instant.ofEpochMilli(start + 4 * Q + 120_000));
        assertThat(store.state().lastBarOpen()).isEqualTo(start + 3 * Q);
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
