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
        r.add("trendpilot.optimize.max-active", () -> "3"); // this test checks the slot limit with 3 slots
        r.add("trendpilot.telegram.bot-token", () -> "");
        r.add("trendpilot.telegram.chat-id", () -> "");
        r.add("trendpilot.optimize.candidates-url", () -> "");
        // 这些用例只测转正规则本身：评估起点不受 FastWatch 时间影响、两个变体都已完成影子对账（新口径单独测）
        r.add("trendpilot.optimize.fastwatch-since", () -> "2026-01-01T00:00:00Z");
        r.add("trendpilot.optimize.reconciled[V5].execution-reconciled", () -> "true");
        r.add("trendpilot.optimize.reconciled[V6].execution-reconciled", () -> "true");
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
    void sameTelegramMessageRollsBackOnlyOnce() {
        paramSets.promote("V6", VariantOverrides.parseInline("max-volume-accel=4"), NOW.minus(Duration.ofDays(30)), "A");
        paramSets.promote("V7", VariantOverrides.parseInline("max-volume-accel=5"), NOW, "B");
        // 同一条"回滚"消息被处理两次（回复没发出去重试、或重启后 Telegram 重新投递）：只撤销一次
        var first = paramSets.rollback(NOW.plusSeconds(60), 777L);
        var again = paramSets.rollback(NOW.plusSeconds(120), 777L);
        assertThat(first).map(ParamSets.Switch::variant).contains("V7");
        assertThat(again).map(ParamSets.Switch::variant).contains("V7");
        assertThat(paramSets.current()).isEqualTo(VariantOverrides.parseInline("max-volume-accel=4"));
        assertThat(paramSets.history(10)).extracting(ParamSets.Switch::kind).containsExactly("ROLLBACK", "PROMOTE", "PROMOTE");
        // 新的一条"回滚"消息照常再撤一次
        assertThat(paramSets.rollback(NOW.plusSeconds(180), 778L)).map(ParamSets.Switch::variant).contains("V6");
        assertThat(paramSets.current().isEmpty()).isTrue();
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

    @Autowired VariantTracker tracker;

    @Test
    void shadowDrawdownBeyondTwentyFivePercentStopsNewBuysImmediately() {
        Instant from = NOW.minus(Duration.ofDays(3));
        start("V6", from);
        history("V6", from, 10, -560, 0); // 合计 −5,600 U：净值 20,000 → 14,400，回撤 28%
        tracker.checkDrawdown(registry.get("V6").orElseThrow(), NOW);
        assertThat(status("V6")).isEqualTo(VariantRegistry.STOPPED);
        assertThat(registry.get("V6").orElseThrow().status()).isEqualTo(VariantRegistry.STOPPED);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE event_key = 'optimize:stop:V6'").query(Integer.class)
                .single()).isEqualTo(1);
        // 回撤没超过上限的不动
        history("V5", from, 10, -100, 0);
        tracker.checkDrawdown(registry.get("V5").orElseThrow(), NOW);
        assertThat(status("V5")).isEqualTo(VariantRegistry.ACTIVE);
        // 每天的评估里也一样：超过 25% 的直接停，不会成为候选
        jdbc.sql("UPDATE variant_registry SET status = 'ACTIVE' WHERE name = 'V6'").update();
        assertThat(decision("V6")).isEqualTo("STOP");
        assertThat(lastReason("V6")).contains("25%");
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
                  {"id":"W_H4","status":"PASS","overrides":{"max-volume-accel":"4"},"validation":{"equity":100},"executionSensitivity":{"pass":true},
                   "evidence":"https://github.com/x/report.md"},
                  {"id":"W_H2","status":"PROMISING","overrides":{"early-exit-hours":"12","early-exit-max-mfe-pct":"1","early-exit-loss-pct":"4"}},
                  {"id":"W_BAD","status":"PASS","overrides":{"no-such-key":"1"},"executionSensitivity":{"pass":true}}
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

    @Autowired com.trendpilot.portfolio.ShadowBook v2book;
    @Autowired com.trendpilot.notify.Notifier notifier;

    /** 同一套转正参数，只换 FastWatch 起点和影子对账结论。 */
    private AutoPromoter promoterWith(Instant fastwatchSince, java.util.Map<String, OptimizeProperties.Reconciliation> rec) {
        OptimizeProperties o = new OptimizeProperties(opt.autoPromote(), false, opt.minDays(), opt.earlyStopDays(), opt.earlyStopDdRatio(),
                opt.minClosed(), opt.promoteDdRatio(), opt.retireDdRatio(), opt.maxActive(), opt.formalChangeDays(), opt.lastFormalChange(), "",
                fastwatchSince.toString(), rec);
        return new AutoPromoter(registry, paramSets, v2book, jdbc, notifier, o, "Asia/Ho_Chi_Minh");
    }

    private static final OptimizeProperties.Reconciliation OK = new OptimizeProperties.Reconciliation(true, null, null);

    /** 连续的权益曲线：从 startEq 接着累加。 */
    private void curve(String book, Instant from, Instant to, int n, double pnl, double startEq) {
        double eq = startEq;
        long step = Duration.between(from, to).toMinutes() / (n + 1);
        for (int i = 1; i <= n; i++) {
            Instant at = from.plus(Duration.ofMinutes(step * i));
            jdbc.sql("""
                    INSERT INTO shadow_event(event_id, book, venue, symbol, type, pnl_usdt, reason_code, generation, params_version, created_at)
                    VALUES (:id, :b, 'BINANCE', 'Y' || :i || 'USDT', 'SELL', :p, 'PROFIT_PROTECTION', 'g', 'v', :t)
                    """).param("id", java.util.UUID.randomUUID().toString()).param("b", book).param("i", i)
                    .param("p", String.valueOf(pnl)).param("t", at.toString()).update();
            eq += pnl;
            jdbc.sql("INSERT INTO equity_curve(book, at, realized, unrealized) VALUES (:b, :t, :r, 0)")
                    .param("b", book).param("t", at.plusSeconds(1).toString()).param("r", eq).update();
        }
    }

    @Test
    void evaluationCountsOnlyFromFastWatchAndListsTheEarlierPartSeparately() {
        Instant started = NOW.minus(Duration.ofDays(31));
        Instant fw = NOW.minus(Duration.ofDays(20));
        start("V6", started);
        start("V5", NOW.minus(Duration.ofDays(5))); // 晚于 FastWatch 上线：评估起点就是上线时间
        curve("V6", started, fw, 10, -40, 0);          // 接入前亏 400 U：不计入转正
        Instant later = fw.plus(Duration.ofDays(30)).plus(Duration.ofHours(1));
        curve("V6", fw, later, 25, 10, -400);
        history("V2", started, later, 25, 2, 500); // V2 中途回撤 500 U：接入前 −400 U 没到提前停开新仓的线
        AutoPromoter p = promoterWith(fw, java.util.Map.of("V6", OK, "V5", OK));

        AutoPromoter.Eval now = p.evaluate(NOW).stream().filter(x -> x.variant().equals("V6")).findFirst().orElseThrow();
        assertThat(now.decision()).isEqualTo("WAIT"); // 上线已 31 天，但接入 FastWatch 才 20 天
        assertThat(now.daysLive()).isBetween(19.9, 20.1);
        String fwLocal = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(fw.atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")));
        assertThat(now.reason()).contains("评估起点 " + fwLocal).contains("评估日 2026-12-02")
                .contains("单列、不计入转正：合计 -400 U，平仓 10 笔");
        AutoPromoter.Eval v5 = p.evaluate(NOW).stream().filter(x -> x.variant().equals("V5")).findFirst().orElseThrow();
        assertThat(v5.reason()).contains("上线即接入 FastWatch").doesNotContain("单列");

        // 新起点满 30 天：只看接入后的 25 笔（+250 U），接入前的 −400 U 不拖累转正
        AutoPromoter.Eval due = p.evaluate(later).stream().filter(x -> x.variant().equals("V6")).findFirst().orElseThrow();
        assertThat(due.mine().total()).isCloseTo(250, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(due.mine().closed()).isEqualTo(25);
        assertThat(due.decision()).isIn("CANDIDATE", "PROMOTED");
    }

    @Test
    void safetyStopsStillLookAtEverythingSinceLaunch() {
        Instant started = NOW.minus(Duration.ofDays(16));
        Instant fw = NOW.minus(Duration.ofDays(2));
        start("V6", started);
        curve("V2", started, NOW, 10, 2, 0);
        curve("V6", started, fw, 10, -30, 0); // 回撤 300 U > V2 × 1.5，发生在接入 FastWatch 之前
        AutoPromoter p = promoterWith(fw, java.util.Map.of("V6", OK));
        assertThat(p.evaluate(NOW).stream().filter(x -> x.variant().equals("V6")).findFirst().orElseThrow().decision()).isEqualTo("STOP");
    }

    @Test
    void promotionNeedsAShadowReconciliationAndAnExecutionGapAboveMinusTwo() {
        Instant from = NOW.minus(Duration.ofDays(31));
        curve("V2", from, NOW, 25, 2, 0);
        curve("V6", from, NOW, 25, 10, 0);
        curve("V5", from, NOW, 25, 1, 0);
        Instant fw = from.minus(Duration.ofDays(1));

        AutoPromoter none = promoterWith(fw, java.util.Map.of());
        AutoPromoter.Eval e = none.evaluate(NOW).stream().filter(x -> x.variant().equals("V6")).findFirst().orElseThrow();
        assertThat(e.decision()).isEqualTo("KEEP");
        assertThat(e.reason()).contains("影子对账未确认（execution-reconciled = false），先查执行");

        AutoPromoter bad = promoterWith(fw, java.util.Map.of("V6", new OptimizeProperties.Reconciliation(true, -2.4, "x")));
        e = bad.evaluate(NOW).stream().filter(x -> x.variant().equals("V6")).findFirst().orElseThrow();
        assertThat(e.decision()).isEqualTo("KEEP");
        assertThat(e.reason()).contains("执行分项 -2.40 U/每千U 低于 −2，先查执行");
        assertThat(paramSets.current().isEmpty()).isTrue();

        AutoPromoter good = promoterWith(fw, java.util.Map.of("V6", new OptimizeProperties.Reconciliation(true, -1.5, "x")));
        assertThat(good.evaluate(NOW).stream().filter(x -> x.variant().equals("V6")).findFirst().orElseThrow().decision()).isEqualTo("PROMOTED");
    }

    @Test
    void configuredDefaultsKeepEveryVariantUnreconciledAndStartAtTheFastWatchDeploy() {
        assertThat(OptimizeProperties.FASTWATCH_SINCE).isEqualTo("2026-11-02T02:32:17Z");
        OptimizeProperties plain = new OptimizeProperties(true, false, 30, 14, 1.5, 20, 1.1, 1.3, 3, 14, "2026-10-23", "");
        assertThat(plain.fastwatchSinceInstant()).isEqualTo(Instant.parse("2026-11-02T02:32:17Z"));
        assertThat(plain.reconciliation("V5").ok()).isFalse();
        assertThat(opt.reconciliation("V6").ok()).isTrue(); // 本测试的配置
        assertThat(opt.reconciliation("v6").ok()).isTrue();
    }

    @Test
    void newVariantsMustBeOnFastWatch() {
        jdbc.sql("UPDATE variant_registry SET status = 'STOPPED' WHERE name = 'V6'").update();
        VariantOverrides runner = VariantOverrides.parseInline("runner-arm-pct=5,runner-giveback-pct=3");
        assertThat(VariantRegistry.fastWatchCovers(runner)).isFalse();
        assertThat(registry.register(runner, "CANDIDATES", true, "", null, NOW)).isEmpty();
        assertThat(registry.notWatched()).isEmpty();
        // 每个正在开新仓的变体都在 FastWatch 名单里
        jdbc.sql("UPDATE variant_registry SET status = 'ACTIVE' WHERE name = 'V6'").update();
        assertThat(fastWatchBooks.positions().keySet()).containsAll(registry.active().stream().map(VariantRegistry.Variant::name).toList());
    }

    @Autowired com.trendpilot.job.FastWatchBooks fastWatchBooks;

    @Test
    void weeklyCandidatesWithoutAPassingExecutionSensitivityStayOffline() {
        String body = """
                {"generatedAt":"2026-11-07T10:00:00Z","candidates":[
                  {"id":"W_OLD","status":"PASS","overrides":{"max-volume-accel":"4"}},
                  {"id":"W_THIN","status":"PASS","overrides":{"max-volume-accel":"5"},"executionSensitivity":{"pass":false}}
                ]}""";
        jdbc.sql("UPDATE variant_registry SET status = 'STOPPED' WHERE name = 'V6'").update();
        assertThat(candidates.ingest(body, NOW)).isEmpty();
    }

    private String decision(String v) {
        return promoter.evaluate(NOW).stream().filter(x -> x.variant().equals(v)).findFirst().orElseThrow().decision();
    }

    private String lastReason(String v) {
        return jdbc.sql("SELECT reason FROM variant_eval WHERE variant = :v ORDER BY id DESC LIMIT 1").param("v", v)
                .query(String.class).single();
    }
}
