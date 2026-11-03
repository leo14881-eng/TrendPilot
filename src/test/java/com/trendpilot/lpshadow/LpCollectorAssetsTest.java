package com.trendpilot.lpshadow;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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

/**
 * BTC 池和稳定币池的采集（2026-11-03）：规模够才上线、不够 INACTIVE 不运行并告警一次、数据取不到不上线稍后再试；
 * BTC 池用 BTCUSDT 永续、gas 按 ETHUSDT 换算；已有 ETH 池子的快照口径、状态和 60 天起点完全不变。时间全部固定。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class LpCollectorAssetsTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
    }

    @Autowired LpStore store;
    @Autowired JdbcClient jdbc;
    @Autowired LpShadowProperties appProps;

    static final long TS = Instant.parse("2026-11-03T03:00:00Z").getEpochSecond();

    FakeChain base;
    List<String[]> alerts = new ArrayList<>();
    Map<String, Double> volumes = new HashMap<>();
    List<String> volumeCalls = new ArrayList<>();
    List<String> perpCalls = new ArrayList<>();
    LpCollector collector;

    @BeforeEach
    void setUp() {
        for (String t : List.of("lp_pool_snapshot", "lp_pool_tvl", "lp_pool", "lp_state", "lp_position", "lp_event", "lp_day_close")) {
            jdbc.sql("DELETE FROM " + t).update();
        }
        base = FakeChain.base().withBtcAndStable();
        base.state(FakeChain.POOL_005, LpCollectorTest.SQRT, LpCollectorTest.TICK, BigInteger.TEN.pow(15), BigInteger.ONE, BigInteger.TWO);
        // cbBTC 池：BTC 100,000；池子里 50 个 cbBTC + 5,000,000 USDC ≈ TVL 1,000 万
        base.state(FakeChain.POOL_BTC, LpBtcStableTest.sqrtX96(LpBtcStableTest.BASE_BTC, 100_000), -69_082, BigInteger.TEN.pow(12),
                BigInteger.ONE, BigInteger.ONE);
        base.on(FakeChain.USDC, Abi.balanceOf(FakeChain.POOL_BTC), FakeChain.uint(5_000_000_000_000L));
        base.on(FakeChain.CBBTC, Abi.balanceOf(FakeChain.POOL_BTC), FakeChain.uint(5_000_000_000L));
        // 稳定币池：只有 20 万 USDC + 10 万 USDT → TVL 30 万，低于 50 万门槛
        base.state(FakeChain.POOL_STB, LpBtcStableTest.sqrtX96(LpBtcStableTest.STB, 1.0), 0, BigInteger.TEN.pow(12), BigInteger.ONE,
                BigInteger.ONE);
        base.on(FakeChain.USDC, Abi.balanceOf(FakeChain.POOL_STB), FakeChain.uint(200_000_000_000L));
        base.on(FakeChain.USDT, Abi.balanceOf(FakeChain.POOL_STB), FakeChain.uint(100_000_000_000L));
        base.on(FakeChain.WETH, Abi.balanceOf(FakeChain.POOL_005), FakeChain.uint(new BigInteger("1000000000000000000000")));
        base.on(FakeChain.USDC, Abi.balanceOf(FakeChain.POOL_005), FakeChain.uint(3_000_000_000_000L));
        volumes.put(FakeChain.POOL_BTC.toLowerCase(), 5_000_000.0);
        volumes.put(FakeChain.POOL_STB.toLowerCase(), 2_000_000.0);
        LpShadowProperties props = new LpShadowProperties(true, null, null, Map.of("base", FakeChain.baseChain()),
                LpBtcStableTest.MIXED, 0, 0, 0, 500_000, 100_000, null);
        Map<String, RpcClient> rpc = new LinkedHashMap<>();
        rpc.put("base", new RpcClient("base", base, 0, () -> 0L));
        PerpQuotes perp = new PerpQuotes((String sym) -> {
            perpCalls.add(sym);
            return new PerpQuotes.Quote(sym.equals("BTCUSDT") ? 100_050.0 : 3_001.5, 0.0001);
        });
        collector = new LpCollector(props, rpc, perp, store, (k, t) -> alerts.add(new String[] {k, t}), (chain, addr) -> {
            volumeCalls.add(addr.toLowerCase());
            return volumes.get(addr.toLowerCase());
        });
    }

    Snapshot row(List<Snapshot> rows, String pool) {
        return rows.stream().filter(s -> s.pool().equals(pool)).findFirst().orElse(null);
    }

    @Test
    void bigPoolGoesActiveSmallPoolStaysInactiveAndEthPoolIsUnchanged() {
        List<Snapshot> rows = collector.collect(TS, Instant.ofEpochSecond(TS + 7));
        // BTC 池：ACTIVE，对冲报价是 BTCUSDT，gas 用 ETHUSDT 标记价
        Snapshot btc = row(rows, "base-btc-005");
        assertThat(btc.ok()).isTrue();
        assertThat(btc.perpMark()).isEqualTo(100_050.0);
        assertThat(btc.gasTokenUsd()).isEqualTo(3_001.5);
        assertThat(store.snapshots("base-btc-005", TS, TS + 60).getFirst().gasTokenUsd()).isEqualTo(3_001.5);
        assertThat(jdbc.sql("SELECT status || ' ' || asset FROM lp_pool WHERE pool = 'base-btc-005'").query(String.class).single())
                .isEqualTo("ACTIVE BTC");
        assertThat(jdbc.sql("SELECT tvl_usd FROM lp_pool WHERE pool = 'base-btc-005'").query(Double.class).single())
                .isBetween(9_990_000.0, 10_010_000.0);
        // 稳定币池：TVL 30 万 < 50 万 → INACTIVE，不写快照、不运行，告警一次写明原因
        assertThat(row(rows, "base-stb-001")).isNull();
        assertThat(jdbc.sql("SELECT status FROM lp_pool WHERE pool = 'base-stb-001'").query(String.class).single()).isEqualTo("INACTIVE");
        assertThat(alerts).hasSize(1);
        assertThat(alerts.getFirst()[1]).startsWith("【LP】").contains("base-stb-001").contains("TVL").contains("50.0 万");
        // ETH 池：口径不变（没有 gasTokenUsd，永续是 ETHUSDT）
        Snapshot eth = row(rows, "base-005");
        assertThat(eth.ok()).isTrue();
        assertThat(eth.gasTokenUsd()).isNull();
        assertThat(eth.perpMark()).isEqualTo(3_001.5);
        assertThat(jdbc.sql("SELECT asset FROM lp_pool WHERE pool = 'base-005'").query(String.class).single()).isEqualTo("ETH");
        // 每个合约每分钟最多请求一次
        assertThat(perpCalls).containsExactlyInAnyOrder("ETHUSDT", "BTCUSDT");

        // 下一分钟：INACTIVE 的池子仍然不跑，不再告警；规模检查不再请求
        List<Snapshot> rows2 = collector.collect(TS + 60, Instant.ofEpochSecond(TS + 67));
        assertThat(row(rows2, "base-stb-001")).isNull();
        assertThat(rows2).extracting(Snapshot::pool).containsExactlyInAnyOrder("base-005", "base-btc-005");
        assertThat(alerts).hasSize(1);
        assertThat(volumeCalls).hasSize(2);
        assertThat(store.snapshots("base-stb-001", TS, TS + 3600)).isEmpty();
        assertThat(store.activePools()).extracting(PoolInfo::key).containsExactly("base-005", "base-btc-005");
    }

    @Test
    void lowVolumeIsInactiveAndMissingVolumeWaitsWithoutRunning() {
        volumes.put(FakeChain.POOL_BTC.toLowerCase(), 50_000.0);   // 成交量 5 万 < 10 万
        volumes.remove(FakeChain.POOL_STB.toLowerCase());
        base.on(FakeChain.USDC, Abi.balanceOf(FakeChain.POOL_STB), FakeChain.uint(2_000_000_000_000L));   // TVL 够了，但成交量取不到
        List<Snapshot> rows = collector.collect(TS, Instant.ofEpochSecond(TS + 7));
        assertThat(jdbc.sql("SELECT status FROM lp_pool WHERE pool = 'base-btc-005'").query(String.class).single()).isEqualTo("INACTIVE");
        assertThat(alerts.getFirst()[1]).contains("24 小时成交量");
        assertThat(jdbc.sql("SELECT status FROM lp_pool WHERE pool = 'base-stb-001'").query(String.class).single()).isEqualTo("PENDING");
        assertThat(rows).extracting(Snapshot::pool).containsExactly("base-005");
        // 30 分钟内不重试、不写快照
        collector.collect(TS + 60, Instant.ofEpochSecond(TS + 67));
        assertThat(volumeCalls.stream().filter(a -> a.equalsIgnoreCase(FakeChain.POOL_STB))).hasSize(1);
        assertThat(store.snapshots("base-stb-001", TS, TS + 3600)).isEmpty();
        // 30 分钟后成交量有了：上线（稳定币池没有永续报价，gas 仍按 ETHUSDT）
        volumes.put(FakeChain.POOL_STB.toLowerCase(), 3_000_000.0);
        long later = TS + LpCollector.SIZE_RETRY_SECONDS + 60;
        List<Snapshot> rows3 = collector.collect(later, Instant.ofEpochSecond(later + 7));
        Snapshot stb = row(rows3, "base-stb-001");
        assertThat(stb.ok()).isTrue();
        assertThat(stb.perpMark()).isNull();
        assertThat(stb.gasTokenUsd()).isEqualTo(3_001.5);
        assertThat(jdbc.sql("SELECT status FROM lp_pool WHERE pool = 'base-stb-001'").query(String.class).single()).isEqualTo("ACTIVE");
    }

    @Test
    void poolThatAlreadyStartedSkipsTheSizeCheckSoItsSixtyDaysAreNeverReset() {
        // 稳定币池已经在运行（有状态）：即使现在规模变小，重启后也不检查规模、不停，起点不变
        store.saveStates(List.<String[]>of(new String[] {"base-stb-001-usdc", "base-stb-001", "{\"started\":true,\"startTs\":1}"}), TS - 60);
        List<Snapshot> rows = collector.collect(TS, Instant.ofEpochSecond(TS + 7));
        assertThat(row(rows, "base-stb-001").ok()).isTrue();
        assertThat(volumeCalls).doesNotContain(FakeChain.POOL_STB.toLowerCase());
        assertThat(store.states("base-stb-001").get("base-stb-001-usdc")).contains("\"startTs\":1");
    }

    @Test
    void addingNewPoolsLeavesEthPoolStateAndStartUntouched() {
        // ETH 池已经跑了一段：先只有 ETH 池的引擎跑两分钟
        PoolInfo eth = LpMathTest.W0;
        LpEngine before = new LpEngine(store);
        BigInteger liq = BigInteger.TEN.pow(24);
        for (int i = 0; i < 2; i++) {
            Snapshot s = LpBookTest.snap(TS + 60L * i, 3000, liq, BigInteger.ZERO, BigInteger.ZERO);
            store.insertSnapshots(List.of(s));
            before.onSnapshots(Map.of(eth.key(), eth), List.of(s));
        }
        Map<String, String> ethStates = store.states(eth.key());
        assertThat(ethStates).hasSize(23);
        // 重启后加上 BTC、稳定币池：同一分钟一起处理
        LpEngine after = new LpEngine(store);
        PoolInfo btc = LpBtcStableTest.ARB_BTC;
        PoolInfo stb = LpBtcStableTest.STB;
        long t = TS + 120;
        Snapshot e2 = LpBookTest.snap(t, 3000, liq, BigInteger.ZERO, BigInteger.ZERO);
        store.insertSnapshots(List.of(e2));
        after.onSnapshots(Map.of(eth.key(), eth, btc.key(), btc, stb.key(), stb),
                List.of(e2, LpBtcStableTest.snap(btc, t, 100_000, 100_010.0, 3_000.0), LpBtcStableTest.snap(stb, t, 1.0, null, 3_000.0)));
        // ETH 池：起点和状态接着原来的走（没有重新开始）
        PositionState e = after.books().get(eth.key()).states.get("w0-w05-U-c0");
        assertThat(e.startTs).isEqualTo(TS);
        assertThat(e.validMinutes).isEqualTo(2);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM lp_event WHERE type = 'START' AND strategy LIKE 'w0-%'").query(Long.class).single())
                .isEqualTo(23L);
        // 新池子从这一分钟开始，单独计时
        assertThat(after.books().get(btc.key()).states.get("arb-btc-005-w05-H-c0").startTs).isEqualTo(t);
        assertThat(after.books().get(stb.key()).states).hasSize(7);
        assertThat(store.states(btc.key())).hasSize(23);
        assertThat(store.states(stb.key())).hasSize(7);
    }

    @Test
    void productionConfigListsBtcAndStablePoolsWithTheirTokens() {
        assertThat(appProps.minTvlUsd()).isEqualTo(500_000);
        assertThat(appProps.minVolume24hUsd()).isEqualTo(100_000);
        Map<String, LpShadowProperties.Pool> byKey = new HashMap<>();
        appProps.pools().forEach(p -> byKey.put(p.key(), p));
        assertThat(byKey.keySet()).contains("base-030", "base-005", "arb-005", "base-btc-005", "arb-btc-005", "base-stb-001", "arb-stb-001");
        // 原有 3 个 ETH 池配置不变
        assertThat(byKey.get("base-005")).isEqualTo(new LpShadowProperties.Pool("base-005", "base", 500));
        assertThat(byKey.get("base-030").sizeCheck()).isFalse();
        LpShadowProperties.Pool b = byKey.get("base-btc-005");
        assertThat(b.asset()).isEqualTo("BTC");
        assertThat(b.baseDecimals()).isEqualTo(8);
        assertThat(b.base()).isEqualToIgnoringCase("0xcbB7C0000aB88B473b1f5aFd9ef808440eed33Bf");
        assertThat(appProps.perpFor(b)).isEqualTo("BTCUSDT");
        assertThat(byKey.get("arb-btc-005").baseSymbol()).isEqualTo("WBTC");
        LpShadowProperties.Pool s = byKey.get("arb-stb-001");
        assertThat(s.fee()).isEqualTo(100);
        assertThat(s.baseSymbol()).isEqualTo("USD₮0|USDT0|USDT");
        assertThat(appProps.perpFor(s)).isNull();
        assertThat(appProps.perpFor(byKey.get("arb-005"))).isEqualTo("ETHUSDT");
    }
}
