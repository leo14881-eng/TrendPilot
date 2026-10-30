package com.trendpilot.lpshadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigInteger;
import java.nio.file.Path;
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

/** 状态写回 lp_state、重启后恢复并接着算；停机那段记为 gap；一个池子出错不影响另一个。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class LpEngineTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
    }

    @Autowired LpStore store;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() {
        for (String t : List.of("lp_pool_snapshot", "lp_state", "lp_position", "lp_event", "lp_daily")) {
            jdbc.sql("DELETE FROM " + t).update();
        }
    }

    @Test
    void stateSurvivesRestartAndDowntimeIsGap() {
        PoolInfo p = LpMathTest.W0;
        Map<String, PoolInfo> active = Map.of(p.key(), p);
        BigInteger liq = BigInteger.TEN.pow(24);
        BigInteger step = BigInteger.ONE.shiftLeft(128).divide(BigInteger.valueOf(1_000_000_000));
        long t0 = 1_800_000_000L - 1_800_000_000L % 60;
        LpEngine e1 = new LpEngine(store);
        for (int i = 0; i < 3; i++) {
            Snapshot s = LpBookTest.snap(t0 + 60L * i, 3000, liq, BigInteger.ZERO, step.multiply(BigInteger.valueOf(i)));
            store.insertSnapshots(List.of(s));
            e1.onSnapshots(active, List.of(s));
        }
        double fees = e1.books().get(p.key()).states.get("w0-w05-U-c0").fees;
        assertThat(fees).isPositive();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lp_state").query(Long.class).single()).isEqualTo(23L);  // 20 个做市 + 3 个基准
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lp_position WHERE closed_ts IS NULL").query(Long.class).single()).isEqualTo(20L);

        // "重启"：新引擎从数据库恢复；停机 2 分钟（t0+180、t0+240 没有快照）
        LpEngine e2 = new LpEngine(store);
        Snapshot s5 = LpBookTest.snap(t0 + 300, 3000, liq, BigInteger.ZERO, step.multiply(BigInteger.valueOf(5)));
        store.insertSnapshots(List.of(s5));
        e2.onSnapshots(active, List.of(s5));
        PositionState st = e2.books().get(p.key()).states.get("w0-w05-U-c0");
        assertThat(st.fees).isCloseTo(fees, within(1e-12));   // 停机那段不计
        assertThat(st.positionId).isPositive();
        Snapshot s6 = LpBookTest.snap(t0 + 360, 3000, liq, BigInteger.ZERO, step.multiply(BigInteger.valueOf(6)));
        e2.onSnapshots(active, List.of(s6));
        assertThat(e2.books().get(p.key()).states.get("w0-w05-U-c0").fees).isCloseTo(fees * 1.5, within(1e-9));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lp_event WHERE type = 'GAP'").query(Long.class).single()).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lp_position").query(Long.class).single()).isEqualTo(20L);   // 没有重复开仓
    }

    @Test
    void brokenPoolDoesNotStopOtherPool() {
        PoolInfo good = LpMathTest.W0;
        PoolInfo bad = new PoolInfo("bad", "x", 500, "0xp", "0xw", "0xu", 18, 6, true, 0);   // tickSpacing 0 → 计算出错
        LpEngine e = new LpEngine(store);
        BigInteger liq = BigInteger.TEN.pow(24);
        Snapshot g = LpBookTest.snap(1_800_000_000L - 1_800_000_000L % 60, 3000, liq, BigInteger.ZERO, BigInteger.ZERO);
        Snapshot b = new Snapshot(g.ts(), "bad", g.sqrtPriceX96(), g.tick(), liq, BigInteger.ZERO, BigInteger.ZERO, null, 3000.0, 0.0001,
                Snapshot.OK, null);
        e.onSnapshots(Map.of(good.key(), good, "bad", bad), List.of(b, g));
        assertThat(e.books()).containsKey(good.key()).doesNotContainKey("bad");
        assertThat(e.books().get(good.key()).started()).isTrue();
    }
}
