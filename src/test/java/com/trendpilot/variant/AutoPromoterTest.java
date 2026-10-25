package com.trendpilot.variant;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trendpilot.support.TestData;

/**
 * 自动转正、淘汰、两周限制、回滚、最多 3 个活跃变体、真钱开关、每周回测候选。全部用固定时间（不看系统时钟）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class AutoPromoterTest {

    static final Path dataDir = TestData.dataDir();
    /** 评估时间：上次正式改动（10-23）之后 30 天，满足两周限制。 */
    static final Instant NOW = Instant.parse("2026-11-22T13:40:00Z");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
        r.add("trendpilot.telegram.bot-token", () -> "");
        r.add("trendpilot.telegram.chat-id", () -> "");
        r.add("trendpilot.optimize.candidates-url", () -> "");
        r.add("trendpilot.variants.list[0].name", () -> "V5");
        r.add("trendpilot.variants.list[0].trade-usdt", () -> "2000");
        r.add("trendpilot.variants.list[0].overrides.min-buy-rel1h", () -> "5.855");
        r.add("trendpilot.variants.list[0].backtest-passed", () -> "true");
        r.add("trendpilot.variants.list[1].name", () -> "V6");
        r.add("trendpilot.variants.list[1].overrides.early-exit-hours", () -> "24");
        r.add("trendpilot.variants.list[1].overrides.early-exit-max-mfe-pct", () -> "1");
        r.add("trendpilot.variants.list[1].overrides.early-exit-loss-pct", () -> "4");
        r.add("trendpilot.variants.list[1].backtest-passed", () -> "true");
    }

    @Autowired AutoPromoter promoter;
    @Autowired VariantRegistry registry;
    @Autowired ParamSets paramSets;
    @Autowired CandidateSync candidates;
    @Autowired OptimizeProperties opt;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void reset() {
        registry.all();
        for (String t : new String[]{"shadow_event", "equity_curve", "param_switch", "variant_eval", "notification_outbox"}) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        jdbc.sql("DELETE FROM app_state WHERE key LIKE 'params:%' OR key LIKE 'optimize:%'").update();
        jdbc.sql("DELETE FROM variant_registry WHERE source != 'CONFIG'").update();
        jdbc.sql("UPDATE variant_registry SET status = 'ACTIVE', backtest_passed = 1").update();
        start("V5", NOW.minus(Duration.ofDays(31)));
        start("V6", NOW.minus(Duration.ofDays(31)));
    }

    private void start(String v, Instant at) {
        jdbc.sql("UPDATE variant_registry SET started_at = :t WHERE name = :n").param("t", at.toString()).param("n", v).update();
    }

    /** 一本账在 [from, NOW) 里平仓 n 笔、每笔 pnl，权益曲线按笔累加，中间回撤 dip。 */
    private void history(String book, Instant from, int n, double pnl, double dip) {
        history(book, from, NOW, n, pnl, dip);
    }

    private void history(String book, Instant from, Instant to, int n, double pnl, double dip) {
        double eq = 0;
        long step = Duration.between(from, to).toMinutes() / (n + 2);
        for (int i = 1; i <= n; i++) {
            Instant at = from.plus(Duration.ofMinutes(step * i));
            jdbc.sql("""
                    INSERT INTO shadow_event(event_id, book, venue, symbol, type, pnl_usdt, reason_code, generation, params_version, created_at)
                    VALUES (:id, :b, 'BINANCE', 'X' || :i || 'USDT', 'SELL', :p, 'PROFIT_PROTECTION', 'g', 'v', :t)
                    """).param("id", java.util.UUID.randomUUID().toString()).param("b", book).param("i", i)
                    .param("p", String.valueOf(pnl)).param("t", at.toString()).update();
            eq += pnl;
            double mark = i == n / 2 ? eq - dip : eq;
            jdbc.sql("INSERT INTO equity_curve(book, at, realized, unrealized) VALUES (:b, :t, :r, :u)")
                    .param("b", book).param("t", at.plusSeconds(1).toString()).param("r", eq).param("u", mark - eq).update();
        }
    }

    private String status(String v) {
        return registry.get(v).orElseThrow().status();
    }

    @Test
    void promotesWhenEveryConditionHoldsAndRollbackRestoresBaseline() {
        Instant from = NOW.minus(Duration.ofDays(31));
        history("V2", from, 25, 2, 40);
        history("V6", from, 25, 10, 30);
        history("V5", from, 25, 1, 5); // 合计低于 V2 → 满 30 天停开新仓

        List<AutoPromoter.Eval> e = promoter.evaluate(NOW);
        assertThat(e).extracting(AutoPromoter.Eval::variant, AutoPromoter.Eval::decision)
                .contains(org.assertj.core.groups.Tuple.tuple("V6", "PROMOTED"), org.assertj.core.groups.Tuple.tuple("V5", "STOP"));
        assertThat(paramSets.current()).isEqualTo(registry.get("V6").orElseThrow().overrides());
        assertThat(status("V6")).isEqualTo(VariantRegistry.PROMOTED);
        assertThat(status("V5")).isEqualTo(VariantRegistry.STOPPED);
        // 一条 Telegram，日报能读到评估
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE event_key = 'optimize:promote:V6'").query(Integer.class)
                .single()).isEqualTo(1);
        assertThat(promoter.latestLines()).anyMatch(l -> l.startsWith("V6 已转正"));
        // 同一天再评估不重复发
        promoter.evaluate(NOW);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE event_key LIKE 'optimize:promote:%'").query(Integer.class)
                .single()).isEqualTo(1);

        // 回滚：恢复基准，切换写进记录
        assertThat(paramSets.rollback(NOW.plusSeconds(60))).isPresent();
        assertThat(paramSets.current().isEmpty()).isTrue();
        assertThat(paramSets.history(5)).extracting(ParamSets.Switch::kind).containsExactly("ROLLBACK", "PROMOTE");
    }

    @Test
    void doesNotPromoteBeforeThirtyDaysWithFewTradesOrWithoutBacktest() {
        Instant from = NOW.minus(Duration.ofDays(20));
        start("V6", from);
        history("V2", from, 25, 2, 40);
        history("V6", from, 25, 10, 30);
        assertThat(decision("V6")).isEqualTo("WAIT");

        start("V6", NOW.minus(Duration.ofDays(31)));
        jdbc.sql("DELETE FROM shadow_event WHERE book = 'V6'").update();
        jdbc.sql("DELETE FROM equity_curve WHERE book = 'V6'").update();
        history("V6", NOW.minus(Duration.ofDays(31)), 3, 100, 10);
        assertThat(decision("V6")).isEqualTo("KEEP"); // 合计、回撤、每笔都好，但只有 3 笔平仓
        assertThat(lastReason("V6")).contains("不到 20 笔");

        jdbc.sql("DELETE FROM shadow_event WHERE book = 'V6'").update();
        jdbc.sql("DELETE FROM equity_curve WHERE book = 'V6'").update();
        history("V6", NOW.minus(Duration.ofDays(31)), 25, 10, 30);
        jdbc.sql("UPDATE variant_registry SET backtest_passed = 0 WHERE name = 'V6'").update();
        assertThat(decision("V6")).isEqualTo("KEEP");
        assertThat(lastReason("V6")).contains("回测发布前检查没有通过");
        assertThat(paramSets.current().isEmpty()).isTrue();
    }

    @Test
    void respectsTheTwoWeekLimitBetweenFormalChanges() {
        Instant early = Instant.parse("2026-11-01T13:40:00Z"); // 距 10-23 不满 14 天
        Instant from = early.minus(Duration.ofDays(31));
        start("V6", from);
        start("V5", from);
        history("V2", from, early, 25, 2, 40);
        history("V6", from, early, 25, 10, 30);
        List<AutoPromoter.Eval> e = promoter.evaluate(early);
        AutoPromoter.Eval v6 = e.stream().filter(x -> x.variant().equals("V6")).findFirst().orElseThrow();
        assertThat(v6.decision()).isEqualTo("CANDIDATE");
        assertThat(v6.reason()).contains("最早 2026-11-06");
        assertThat(paramSets.current().isEmpty()).isTrue();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE event_key = 'optimize:candidate:V6'").query(Integer.class)
                .single()).isEqualTo(1);

        // 刚转正过一次：下一次至少等 14 天
        paramSets.promote("V5", VariantOverrides.parseInline("min-buy-rel1h=5"), NOW.minus(Duration.ofDays(3)), "测试");
        assertThat(paramSets.changeAllowed(NOW)).isFalse();
        assertThat(paramSets.changeAllowed(NOW.plus(Duration.ofDays(11)))).isTrue();
    }

    @Test
    void stopsClearLosersEarlyAndKeepsAtMostThreeActive() {
        Instant from = NOW.minus(Duration.ofDays(15));
        start("V6", from);
        history("V2", from, 10, 2, 20);
        history("V6", from, 10, -5, 60); // 15 天，回撤已超 V2 × 1.5
        assertThat(decision("V6")).isEqualTo("STOP");
        assertThat(status("V6")).isEqualTo(VariantRegistry.STOPPED);

        // 最多 3 个活跃：V5 + 两个新候选，第三个新候选不上线；同样参数不重复登记
        jdbc.sql("UPDATE variant_registry SET status = 'ACTIVE' WHERE name IN ('V5', 'V6')").update();
        assertThat(registry.register(VariantOverrides.parseInline("max-volume-accel=4"), "CANDIDATES", true, "", null, NOW)).isPresent();
        assertThat(registry.activeCount()).isEqualTo(3);
        assertThat(registry.register(VariantOverrides.parseInline("max-volume-accel=6"), "CANDIDATES", true, "", null, NOW)).isEmpty();
        jdbc.sql("UPDATE variant_registry SET status = 'STOPPED' WHERE name = 'V6'").update();
        assertThat(registry.register(VariantOverrides.parseInline("max-volume-accel=4"), "CANDIDATES", true, "", null, NOW)).isEmpty();
    }

    @Test
    void realMoneySwitchIsOffByDefaultAndOnlyV2CanBeChanged() {
        assertThat(opt.realMoneyAutoPromote()).isFalse();
        assertThat(opt.autoPromote()).isTrue();
        assertThat(opt.minDays()).isEqualTo(30);
        assertThat(AutoPromoter.mayAutoChange("V2", false, opt)).isTrue();
        assertThat(AutoPromoter.mayAutoChange("V2", true, opt)).isFalse();
        for (String other : new String[]{"BTC_DAILY", "BTC_TREND", "V1", "V3", "V2D15", "V5"}) {
            assertThat(AutoPromoter.mayAutoChange(other, false, opt)).as(other).isFalse();
        }
        OptimizeProperties allowed = new OptimizeProperties(true, true, 30, 14, 1.5, 20, 1.1, 1.3, 3, 14, "2026-10-23", "");
        assertThat(AutoPromoter.mayAutoChange("V2", true, allowed)).isTrue();
        assertThat(AutoPromoter.mayAutoChange("BTC_DAILY", true, allowed)).isFalse();
    }

    @Test
    void weeklyCandidatesBringOnlyPassingSetsOnlineWithEvidence() {
        String body = """
                {"generatedAt":"2026-10-31T10:00:00Z","candidates":[
                  {"id":"W_H4","status":"PASS","overrides":{"max-volume-accel":"4"},"validation":{"equity":100},
                   "evidence":"https://github.com/x/report.md"},
                  {"id":"W_H2","status":"PROMISING","overrides":{"early-exit-hours":"12","early-exit-max-mfe-pct":"1","early-exit-loss-pct":"4"}},
                  {"id":"W_BAD","status":"PASS","overrides":{"no-such-key":"1"}}
                ]}""";
        jdbc.sql("UPDATE variant_registry SET status = 'STOPPED' WHERE name = 'V6'").update();
        List<String> added = candidates.ingest(body, NOW);
        assertThat(added).hasSize(1);
        VariantRegistry.Variant v = registry.get(added.getFirst()).orElseThrow();
        assertThat(v.name()).isEqualTo("V7");
        assertThat(v.source()).isEqualTo("CANDIDATES");
        assertThat(v.backtestPassed()).isTrue();
        assertThat(v.evidence()).isEqualTo("https://github.com/x/report.md");
        assertThat(v.overrides().maxVolumeAccel()).isEqualTo(4.0);
        // 同一份文件不再处理；读不懂的文件什么都不做
        assertThat(candidates.ingest(body, NOW)).isEmpty();
        assertThat(candidates.ingest("not json", NOW)).isEmpty();
    }

    private String decision(String v) {
        return promoter.evaluate(NOW).stream().filter(x -> x.variant().equals(v)).findFirst().orElseThrow().decision();
    }

    private String lastReason(String v) {
        return jdbc.sql("SELECT reason FROM variant_eval WHERE variant = :v ORDER BY id DESC LIMIT 1").param("v", v)
                .query(String.class).single();
    }
}
