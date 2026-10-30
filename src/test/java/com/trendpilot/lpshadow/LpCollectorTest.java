package com.trendpilot.lpshadow;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

/** 每分钟采集：每条链一个 batch；链不通时写 GAP 行（不补旧数据）；每小时一次 TVL；校验不通过的池子停用并告警一次。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class LpCollectorTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
    }

    @Autowired LpStore store;
    @Autowired JdbcClient jdbc;

    static final BigInteger SQRT = new BigInteger("4339505179874779672736325");   // ≈ ETH 3000 USDC（WETH 是 token0）
    static final int TICK = -196_257;
    static final String ARB_WETH = "0x82aF49447D8a07e3bd95BD0d56f35241523fBab1";
    static final String ARB_USDC = "0xaf88d065e77c8cC2239327C5EDb3A432268e5831";
    static final String ARB_FACTORY = "0x1F98431c8aD98523631AE4a59f267346ea31F984";

    FakeChain base;
    FakeChain arb;
    List<String[]> alerts = new ArrayList<>();
    double perpMark = 3001.5;
    LpCollector collector;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM lp_pool_snapshot").update();
        jdbc.sql("DELETE FROM lp_pool_tvl").update();
        jdbc.sql("DELETE FROM lp_pool").update();
        base = FakeChain.base();
        base.state(FakeChain.POOL_005, SQRT, TICK, BigInteger.valueOf(5_000_000_000_000_000L), BigInteger.ONE, BigInteger.TWO);
        base.state(FakeChain.POOL_030, SQRT, TICK, BigInteger.valueOf(1_000_000_000_000_000L), BigInteger.TEN, BigInteger.TEN);
        base.on(FakeChain.WETH, Abi.balanceOf(FakeChain.POOL_005), FakeChain.uint(new BigInteger("1000000000000000000000")));
        base.on(FakeChain.USDC, Abi.balanceOf(FakeChain.POOL_005), FakeChain.uint(3_000_000_000_000L));
        base.on(FakeChain.WETH, Abi.balanceOf(FakeChain.POOL_030), FakeChain.uint(new BigInteger("100000000000000000000")));
        base.on(FakeChain.USDC, Abi.balanceOf(FakeChain.POOL_030), FakeChain.uint(300_000_000_000L));
        arb = new FakeChain();
        arb.failWith = new RpcClient.RpcException("connect timed out");
        LpShadowProperties props = new LpShadowProperties(true, null, null, Map.of(
                "base", FakeChain.baseChain(),
                "arbitrum", new LpShadowProperties.Chain("http://fake", ARB_FACTORY, ARB_WETH, ARB_USDC, null, 0, null, 0, 0)),
                List.of(new LpShadowProperties.Pool("base-030", "base", 3000), new LpShadowProperties.Pool("base-005", "base", 500),
                        new LpShadowProperties.Pool("arb-005", "arbitrum", 500)));
        Map<String, RpcClient> rpc = new LinkedHashMap<>();
        rpc.put("base", new RpcClient("base", base, 0, () -> 0L));
        rpc.put("arbitrum", new RpcClient("arbitrum", arb, 0, () -> 0L));
        collector = new LpCollector(props, rpc, new PerpQuotes(() -> new PerpQuotes.Quote(perpMark, 0.0001)), store,
                (k, t) -> alerts.add(new String[] {k, t}));
    }

    @Test
    void collectsOkRowsAndGapRowsPerChain() {
        long ts = Instant.parse("2026-11-01T00:00:00Z").getEpochSecond();
        List<Snapshot> rows = collector.collect(ts, Instant.ofEpochSecond(ts + 7));
        assertThat(rows).hasSize(3);
        Snapshot b5 = rows.stream().filter(s -> s.pool().equals("base-005")).findFirst().orElseThrow();
        assertThat(b5.ok()).isTrue();
        assertThat(b5.sqrtPriceX96()).isEqualTo(SQRT);
        assertThat(b5.tick()).isEqualTo(TICK);
        assertThat(b5.fg1()).isEqualTo(BigInteger.TWO);
        assertThat(b5.gasPriceWei()).isEqualTo(BigInteger.valueOf(100_000_000));
        assertThat(b5.perpMark()).isEqualTo(3001.5);
        Snapshot a5 = rows.stream().filter(s -> s.pool().equals("arb-005")).findFirst().orElseThrow();
        assertThat(a5.ok()).isFalse();
        assertThat(a5.note()).contains("还没解析");

        // 第 1 分钟：Base 解析 2 个 batch + 采集 1 个 + TVL 1 个
        assertThat(base.requests).hasSize(4);
        assertThat(base.requests.get(2)).hasSize(2 * 4 + 1 + 2);   // 两个池子 × 4 + gasPrice + 两个 L1 费只读调用
        assertThat(b5.l1FeeWei()).isEqualTo(BigInteger.valueOf(3 * 40_000_000_000L));   // 每笔 4e10 × 3 笔
        assertThat(b5.l1Source()).isEqualTo("getL1FeeUpperBound");
        assertThat(store.snapshots("base-005", ts, ts + 60).getFirst().l1FeeWei()).isEqualTo(b5.l1FeeWei());
        // 数据库里 3 行
        assertThat(store.snapshots("base-005", ts, ts + 60)).hasSize(1);
        assertThat(store.okCounts(ts, ts + 60).get("arb-005")[0]).isZero();
        assertThat(store.okCounts(ts, ts + 60).get("base-030")).containsExactly(1L, 1L);

        // TVL：1000 WETH × 3000 + 3,000,000 USDC ≈ 6,000,000；m 是正数
        Map<String, double[]> m = store.mAvg(ts, ts + 3600);
        assertThat(m.get("base-005")[1]).isBetween(5_990_000.0, 6_010_000.0);
        assertThat(m.get("base-005")[0]).isPositive();

        // 第 2 分钟：Base 不通 → GAP，不用旧数据；同一小时不再读 TVL
        base.failWith = new RpcClient.RpcException("HTTP 503");
        List<Snapshot> rows2 = collector.collect(ts + 60, Instant.ofEpochSecond(ts + 67));
        assertThat(rows2).allMatch(s -> !s.ok());
        assertThat(store.snapshots("base-005", ts + 60, ts + 120).getFirst().status()).isEqualTo("GAP");
        base.failWith = null;
        int before = base.requests.size();
        collector.collect(ts + 120, Instant.ofEpochSecond(ts + 127));
        assertThat(base.requests.size() - before).isEqualTo(1);   // 只有采集 batch
        assertThat(store.activePools()).extracting(PoolInfo::key).containsExactly("base-005", "base-030");
    }

    @Test
    void rejectedPoolStopsAndAlertsOnce() {
        base.on(FakeChain.FACTORY, Abi.getPool(FakeChain.WETH, FakeChain.USDC, 3000), FakeChain.uint(0));
        long ts = Instant.parse("2026-11-01T00:00:00Z").getEpochSecond();
        collector.collect(ts, Instant.ofEpochSecond(ts + 7));
        List<Snapshot> rows = collector.collect(ts + 60, Instant.ofEpochSecond(ts + 67));
        assertThat(rows).extracting(Snapshot::pool).containsExactlyInAnyOrder("base-005", "arb-005");
        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst()[1]).startsWith("【LP】").contains("base-030");
        assertThat(jdbc.sql("SELECT status FROM lp_pool WHERE pool = 'base-030'").query(String.class).single()).isEqualTo("REJECTED");
    }

    @Test
    void perpMissingStillRecordsPoolData() {
        perpMark = -1;
        long ts = Instant.parse("2026-11-01T00:00:00Z").getEpochSecond();
        Snapshot s = collector.collect(ts, Instant.ofEpochSecond(ts + 7)).stream().filter(x -> x.pool().equals("base-005"))
                .findFirst().orElseThrow();
        assertThat(s.ok()).isTrue();
        assertThat(s.perpOk()).isFalse();
        assertThat(store.okCounts(ts, ts + 60).get("base-005")).containsExactly(1L, 0L);
    }
}
