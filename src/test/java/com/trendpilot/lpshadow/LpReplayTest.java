package com.trendpilot.lpshadow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 回放测试：用一段录好的快照（src/test/resources/lpshadow/replay_snapshots.csv，两个池子各 11 小时，
 * 含 gap、缺分钟、永续报价缺失、gas 缺失、L1 费缺 140 分钟、feeGrowth 跨 2^256 回绕、两次资金费结算、先涨 12% 再回落）跑 {@link LpBook}，
 * 每个策略的结果必须和独立的参考实现 replay_reference.py 预先算好的数值（replay_expected.csv）一致。
 */
class LpReplayTest {

    static final Map<String, PoolInfo> POOLS = Map.of(
            "rw0", new PoolInfo("rw0", "replay", 500, "0xp", "0xw", "0xu", 18, 6, true, 10),
            "ru0", new PoolInfo("ru0", "replay", 3000, "0xp", "0xu", "0xw", 6, 18, false, 60));

    static List<String[]> csv(String name) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                LpReplayTest.class.getResourceAsStream("/lpshadow/" + name), StandardCharsets.UTF_8))) {
            List<String[]> out = new ArrayList<>();
            r.readLine();
            for (String line; (line = r.readLine()) != null; ) {
                if (!line.isBlank()) out.add(line.split(",", -1));
            }
            return out;
        }
    }

    static Snapshot parse(String[] c) {
        boolean ok = c[10].equals("OK");
        Double mark = c[8].isEmpty() ? null : Double.parseDouble(c[8]);
        Double rate = c[9].isEmpty() ? null : Double.parseDouble(c[9]);
        if (!ok) return Snapshot.gap(Long.parseLong(c[0]), c[1], mark, rate, "replay gap");
        return new Snapshot(Long.parseLong(c[0]), c[1], new BigInteger(c[2]), Integer.parseInt(c[3]), new BigInteger(c[4]),
                new BigInteger(c[5]), new BigInteger(c[6]), c[7].isEmpty() ? null : new BigInteger(c[7]), mark, rate, Snapshot.OK, null,
                c[11].isEmpty() ? null : new BigInteger(c[11]), c[11].isEmpty() ? null : "replay");
    }

    static void close(String what, double actual, double expected) {
        assertThat(Math.abs(actual - expected)).as(what + " 实际 " + actual + " 期望 " + expected)
                .isLessThanOrEqualTo(1e-6 + 1e-9 * Math.abs(expected));
    }

    @Test
    void replayMatchesReferenceImplementation() throws IOException {
        Map<String, LpBook> books = new LinkedHashMap<>();
        Map<String, LpBookTest.FakeLedger> ledgers = new LinkedHashMap<>();
        for (PoolInfo p : POOLS.values()) {
            LpBookTest.FakeLedger l = new LpBookTest.FakeLedger();
            ledgers.put(p.key(), l);
            books.put(p.key(), new LpBook(p, Strategy.forPool(p.key()), Map.of(), null, l));
        }
        List<String[]> rows = csv("replay_snapshots.csv");
        BigInteger lastFg1 = null;
        boolean wrapped = false;
        for (String[] c : rows) {
            Snapshot s = parse(c);
            if (s.pool().equals("rw0") && s.ok()) {
                if (lastFg1 != null && s.fg1().compareTo(lastFg1) < 0) wrapped = true;
                lastFg1 = s.fg1();
            }
            books.get(s.pool()).onSnapshot(s);
        }
        assertThat(wrapped).as("数据里有 feeGrowth 回绕").isTrue();

        List<String[]> expected = csv("replay_expected.csv");
        assertThat(expected).hasSize(46);
        for (String[] e : expected) {
            String pool = e[0].substring(0, 3);
            PositionState st = books.get(pool).states.get(e[0]);
            assertThat(st).as(e[0]).isNotNull();
            close(e[0] + " value", st.lastValue, Double.parseDouble(e[1]));
            close(e[0] + " fees", st.fees, Double.parseDouble(e[2]));
            close(e[0] + " il", st.lastIl, Double.parseDouble(e[3]));
            close(e[0] + " gas", st.gasCost, Double.parseDouble(e[4]));
            close(e[0] + " swap", st.swapCost, Double.parseDouble(e[5]));
            close(e[0] + " hedge", st.lastHedgePnl, Double.parseDouble(e[6]));
            close(e[0] + " funding", st.funding, Double.parseDouble(e[7]));
            assertThat(st.rebalances).as(e[0] + " rebalances").isEqualTo(Integer.parseInt(e[8]));
            assertThat(st.hedgeTrades).as(e[0] + " hedge trades").isEqualTo(Integer.parseInt(e[9]));
            assertThat(st.riskEvents).as(e[0] + " risk").isEqualTo(Integer.parseInt(e[10]));
            assertThat(st.validMinutes).as(e[0] + " valid minutes").isEqualTo(Integer.parseInt(e[11]));
        }
        // 事件：两个结算点都有资金费；gap 记成池子级别事件
        List<String> ev = ledgers.get("rw0").events;
        assertThat(ev.stream().filter(x -> x.equals("FUNDING rw0-w05-H-c0")).count()).isEqualTo(2);
        assertThat(ev.stream().filter(x -> x.equals("GAP rw0")).count()).isEqualTo(2);
        assertThat(ev.stream().filter(x -> x.startsWith("REBALANCE rw0-w02-U-c0")).count()).isEqualTo(10);
        // L1 费缺了 140 分钟：前 1 小时沿用最近值，之后出区间也不调仓（记 COST_GAP）
        assertThat(ev).contains("COST_GAP rw0");
        assertThat(ledgers.get("ru0").events).doesNotContain("COST_GAP ru0");
    }
}
