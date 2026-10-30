package com.trendpilot.lpshadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trendpilot.support.TestData;

/** 日报（越南时间 00:00 后生成一次、Telegram 一行）、周报（bootstrap、回撤、决策标准进度）、快照清理。时间全部用固定值。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class LpReportsTest {

    static final Path dataDir = TestData.dataDir();
    static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
    }

    @Autowired LpStore store;
    @Autowired JdbcClient jdbc;

    List<String[]> texts = new ArrayList<>();
    List<String[]> docs = new ArrayList<>();
    Path reports;
    LpReports lp;

    @BeforeEach
    void setUp() throws Exception {
        for (String t : List.of("lp_pool_snapshot", "lp_state", "lp_position", "lp_event", "lp_daily", "lp_pool_tvl")) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        reports = Files.createTempDirectory(dataDir, "lp-reports");
        lp = new LpReports(store, VN, reports, (k, t) -> texts.add(new String[] {k, t}),
                (k, f, c, cap) -> docs.add(new String[] {k, f, c, cap}));
    }

    @Test
    void bootstrapStatsAndDrawdown() {
        LpReports.Stats s = LpReports.stats(new double[] {10_100, 10_000, 10_200, 9_900, 10_300}, 1);
        assertThat(s.days()).isEqualTo(5);
        double[] r = {0.01, 10_000.0 / 10_100 - 1, 0.02, 9_900.0 / 10_200 - 1, 10_300.0 / 9_900 - 1};
        double mean = (r[0] + r[1] + r[2] + r[3] + r[4]) / 5;
        assertThat(s.mean()).isCloseTo(mean, within(1e-12));
        assertThat(s.lo()).isLessThan(mean);
        assertThat(s.hi()).isGreaterThan(mean);
        assertThat(s.maxDd()).isCloseTo(1 - 9_900.0 / 10_200, within(1e-12));
        assertThat(s.longestDdDays()).isEqualTo(1);
        // 同样的数据、同样的种子 → 同样的区间
        assertThat(LpReports.stats(new double[] {10_100, 10_000, 10_200, 9_900, 10_300}, 1)).isEqualTo(s);
    }

    /** 在 day 的 23:57~23:59（越南时间）跑 3 分钟，返回引擎。 */
    LpEngine runLastMinutes(LocalDate day) {
        PoolInfo p = LpMathTest.W0;
        LpEngine e = new LpEngine(store);
        long end = day.plusDays(1).atStartOfDay(VN).toEpochSecond();
        BigInteger liq = BigInteger.TEN.pow(24);
        BigInteger step = BigInteger.ONE.shiftLeft(128).divide(BigInteger.valueOf(1_000_000_000));
        for (int i = 3; i >= 1; i--) {
            Snapshot s = LpBookTest.snap(end - 60L * i, 3000 + i, liq, BigInteger.ZERO, step.multiply(BigInteger.valueOf(3 - i)));
            store.insertSnapshots(List.of(s));
            e.onSnapshots(Map.of(p.key(), p), List.of(s));
        }
        return e;
    }

    @Test
    void dailyReportOnceAfterMidnightWithOneTelegramLine() throws Exception {
        LocalDate day = LocalDate.of(2026, 11, 3);
        LpEngine e = runLastMinutes(day);
        // 当天还没结束：不生成
        lp.maybeRun(day.atTime(23, 59, 30).atZone(VN).toInstant(), e.books());
        assertThat(texts).isEmpty();
        Instant after = day.plusDays(1).atStartOfDay(VN).toInstant().plusSeconds(7);
        lp.maybeRun(after, e.books());
        lp.maybeRun(after.plusSeconds(60), e.books());
        assertThat(texts).hasSize(1);
        String line = texts.getFirst()[1];
        assertThat(line).startsWith("【LP】2026-11-03 前3 ").contains("｜后3 ").contains("｜持有50/50 ");
        assertThat(line.lines().count()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lp_daily WHERE date = '2026-11-03'").query(Long.class).single()).isEqualTo(23L);
        // 覆盖率：开始后 3 分钟里 3 条都有效
        assertThat(jdbc.sql("SELECT MIN(coverage) FROM lp_daily").query(Double.class).single()).isEqualTo(1.0);
        String md = Files.readString(reports.resolve("lp-daily-2026-11-03.md"));
        assertThat(md).contains("# LP 影子盘日报 2026-11-03").contains("基准：持有 50/50").contains("基准：只持有 USDC")
                .contains("m_avg").contains("数据覆盖率");
        // 前一天（还没开始运行）不会补出一份空日报
        assertThat(Files.exists(reports.resolve("lp-daily-2026-11-02.md"))).isFalse();
    }

    @Test
    void weeklyReportOnMondayWithBootstrapAndCriteria() throws Exception {
        LocalDate sunday = LocalDate.of(2026, 11, 8);
        for (int i = 0; i < 5; i++) {
            String d = sunday.minusDays(4 - i).toString();
            store.saveDaily(d, "w0-w05-U-c0", 10_000 + 20 * (i + 1), 1, 0, 0, 0, 0, 0, 1.0);
            store.saveDaily(d, "w0-usdc", 10_000, 0, 0, 0, 0, 0, 0, 1.0);
        }
        LpEngine e = runLastMinutes(sunday);
        Instant monday = sunday.plusDays(1).atStartOfDay(VN).toInstant().plusSeconds(7);
        lp.maybeRun(monday, e.books());
        lp.maybeRun(monday.plusSeconds(60), e.books());
        assertThat(docs).hasSize(1);
        assertThat(docs.getFirst()[1]).isEqualTo("lp-weekly-2026-11-08.md");
        assertThat(docs.getFirst()[3]).startsWith("【LP】周报");
        String md = Files.readString(reports.resolve("lp-weekly-2026-11-08.md"));
        assertThat(md).contains("95% 区间").contains("w0-w05-U-c0").contains("最长回撤").contains("至少 2 个池子");
        // 每天稳定 +0.2%：区间下限 > 0，但只在 1 个池子 → 不算可以实盘
        assertThat(md).contains("目前没有设置在 2 个以上池子同时达标");
    }

    @Test
    void oldSnapshotsArePrunedAfterDailyReport() {
        LocalDate day = LocalDate.of(2026, 11, 3);
        long old = day.minusDays(100).atStartOfDay(VN).toEpochSecond();
        store.insertSnapshots(List.of(Snapshot.gap(old, "w0", null, null, "old")));
        LpEngine e = runLastMinutes(day);
        lp.maybeRun(day.plusDays(1).atStartOfDay(VN).toInstant().plusSeconds(7), e.books());
        assertThat(store.snapshots("w0", old, old + 60)).isEmpty();
        assertThat(store.snapshots("w0", day.atStartOfDay(VN).toEpochSecond(), day.plusDays(1).atStartOfDay(VN).toEpochSecond())).hasSize(3);
    }
}
