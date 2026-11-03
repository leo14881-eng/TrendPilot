package com.trendpilot.lpshadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * BTC 池和稳定币池（2026-11-03）：8 位 / 6 位精度的价格换算、池子解析和代币校验、策略组合、
 * 非 ETH 池子的 gas 换算、稳定币不需要永续报价、脱钩事件和提醒、报告分组和 15% 年化门槛。纯计算，不用数据库和时钟。
 */
class LpBtcStableTest {

    /** Base cbBTC/USDC：USDC（6 位）是 token0，cbBTC（8 位）是 token1。 */
    static final PoolInfo BASE_BTC = new PoolInfo("base-btc-005", "base", 500, FakeChain.POOL_BTC, FakeChain.USDC, FakeChain.CBBTC, 6, 8,
            false, 10, "BTC");
    /** Arbitrum WBTC/USDC：WBTC（8 位）是 token0，USDC（6 位）是 token1。 */
    static final PoolInfo ARB_BTC = new PoolInfo("arb-btc-005", "arbitrum", 500, "0xp", "0x2f2a", "0xaf88", 8, 6, true, 10, "BTC");
    static final PoolInfo STB = new PoolInfo("base-stb-001", "base", 100, FakeChain.POOL_STB, FakeChain.USDC, FakeChain.USDT, 6, 6,
            false, 1, "STABLE");

    static BigInteger sqrtX96(PoolInfo p, double price) {
        return new BigDecimal(Math.sqrt(LpMath.rawPrice(p, price)) * LpMath.Q96).toBigInteger();
    }

    static Snapshot snap(PoolInfo p, long ts, double price, Double perp, Double gasTokenUsd) {
        BigInteger s = sqrtX96(p, price);
        int tick = LpMath.tickAtRawPrice(Math.pow(LpMath.sqrtRaw(s), 2));
        BigInteger liq = BigInteger.TEN.pow(18);
        return new Snapshot(ts, p.key(), s, tick, liq, BigInteger.ZERO, BigInteger.ZERO, BigInteger.valueOf(10_000_000), perp,
                perp == null ? null : 0.0001, Snapshot.OK, null, LpBookTest.L1_WEI, "test", gasTokenUsd);
    }

    // ---------------- 精度 ----------------

    @Test
    void btcTickAndPriceWithEightAndSixDecimalsInBothTokenOrders() {
        // USDC token0 / cbBTC token1：原始价格 = (1e-5 BTC × 1e8) / (1 USDC × 1e6) = 1e-3
        assertThat(LpMath.rawPrice(BASE_BTC, 100_000)).isCloseTo(1e-3, within(1e-15));
        assertThat(LpMath.ethPrice(BASE_BTC, 1e-3)).isCloseTo(100_000, within(1e-6));
        int t = LpMath.tickAtRawPrice(1e-3);
        assertThat(t).isEqualTo(-69_082);
        assertThat(LpMath.ethPriceAtTick(BASE_BTC, t)).isCloseTo(100_000, within(100_000 * 1e-4));
        // WBTC token0 / USDC token1：原始价格 = 1e5 × 1e6 / 1e8 = 1e3，tick 为正
        assertThat(LpMath.rawPrice(ARB_BTC, 100_000)).isCloseTo(1e3, within(1e-9));
        assertThat(LpMath.tickAtRawPrice(1e3)).isEqualTo(69_081);
        for (PoolInfo p : new PoolInfo[] {BASE_BTC, ARB_BTC}) {
            for (double price : new double[] {20_000, 68_123.45, 100_000, 250_000}) {
                int tick = LpMath.tickAtRawPrice(LpMath.rawPrice(p, price));
                double back = LpMath.ethPriceAtTick(p, tick);
                assertThat(Math.abs(back / price - 1)).as(p.key() + " " + price).isLessThan(1.0001e-4);
            }
            // 10,000 USDC 在 ±5% 的居中区间：大约一半是 BTC（约 0.048 个），一半是 USDC
            double sqrt = LpMath.sqrtRaw(sqrtX96(p, 100_000));
            int[] r = LpMath.rangeTicks(p, sqrt, 0.05);
            assertThat(LpMath.ethPriceAtTick(p, p.wethIsToken0() ? r[0] : r[1])).isCloseTo(95_000, within(95_000 * 0.002));
            double l = LpMath.liquidityFor(p, 10_000, r[0], r[1], sqrt);
            double[] wu = LpMath.wethUsdc(p, l, r[0], r[1], sqrt);
            assertThat(wu[0]).isCloseTo(0.0484, within(0.0005));
            assertThat(wu[1]).isCloseTo(5_160, within(50.0));
            assertThat(wu[0] * 100_000 + wu[1]).isCloseTo(10_000, within(1e-6));
        }
    }

    @Test
    void stableTicksAroundParity() {
        assertThat(LpMath.tickAtRawPrice(LpMath.rawPrice(STB, 1.0))).isZero();
        int[] r = LpMath.rangeTicks(STB, 1.0, 0.0005);   // ±0.05% ≈ ±5 个 tick（tickSpacing 1）
        assertThat(r[0]).isBetween(-6, -4);
        assertThat(r[1]).isBetween(4, 6);
        assertThat(LpMath.ethPriceAtSqrt(STB, LpMath.sqrtRaw(sqrtX96(STB, 0.994)))).isCloseTo(0.994, within(1e-9));
    }

    // ---------------- 解析和校验 ----------------

    static final List<LpShadowProperties.Pool> MIXED = List.of(new LpShadowProperties.Pool("base-005", "base", 500),
            new LpShadowProperties.Pool("base-btc-005", "base", 500, "BTC", FakeChain.CBBTC, "cbBTC", 8, "BTCUSDT", true),
            new LpShadowProperties.Pool("base-stb-001", "base", 100, "STABLE", FakeChain.USDT, "USDT|USDT0", 6, null, true));

    @Test
    void resolvesBtcAndStablePoolsWithTheirOwnDecimals() {
        FakeChain c = FakeChain.base().withBtcAndStable();
        var r = PoolResolver.resolve(new RpcClient("base", c, 0, () -> 0L), FakeChain.baseChain(), "base", MIXED);
        PoolInfo btc = r.get("base-btc-005").info();
        assertThat(btc).isNotNull();
        assertThat(btc.address()).isEqualToIgnoringCase(FakeChain.POOL_BTC);
        assertThat(btc.wethIsToken0()).isFalse();
        assertThat(btc.dec0()).isEqualTo(6);
        assertThat(btc.dec1()).isEqualTo(8);
        assertThat(btc.wethDecimals()).isEqualTo(8);
        assertThat(btc.asset()).isEqualTo("BTC");
        PoolInfo stb = r.get("base-stb-001").info();
        assertThat(stb.tickSpacing()).isEqualTo(1);
        assertThat(stb.isStable()).isTrue();
        PoolInfo eth = r.get("base-005").info();
        assertThat(eth.asset()).isEqualTo("ETH");
        assertThat(eth.dec0()).isEqualTo(18);
        // 第一个 batch：3 种 base 代币 × 2 + USDC × 2 + 3 个 getPool
        assertThat(c.requests.getFirst()).hasSize(3 * 2 + 2 + 3);
    }

    @Test
    void wrongBtcDecimalsOrSymbolStopsOnlyThatPool() {
        FakeChain c = FakeChain.base().withBtcAndStable();
        c.on(FakeChain.CBBTC, Abi.DECIMALS, FakeChain.uint(18));
        var r = PoolResolver.resolve(new RpcClient("base", c, 0, () -> 0L), FakeChain.baseChain(), "base", MIXED);
        assertThat(r.get("base-btc-005").rejected()).isTrue();
        assertThat(r.get("base-btc-005").reason()).contains("decimals").contains("18");
        assertThat(r.get("base-005").info()).isNotNull();
        assertThat(r.get("base-stb-001").info()).isNotNull();

        FakeChain d = FakeChain.base().withBtcAndStable();
        d.on(FakeChain.USDT, Abi.SYMBOL, FakeChain.string("USD₮0"));   // 不在允许的列表里
        var r2 = PoolResolver.resolve(new RpcClient("base", d, 0, () -> 0L), FakeChain.baseChain(), "base", MIXED);
        assertThat(r2.get("base-stb-001").rejected()).isTrue();
        assertThat(r2.get("base-btc-005").info()).isNotNull();
        // symbol 允许写几个（Arbitrum USDT0 合约的 symbol 是 "USD₮0"）
        assertThat(PoolResolver.checkToken("x", "USD₮0", BigInteger.valueOf(6), "USD₮0|USDT0", 6)).isNull();
        // 没有 0.01% 档：只停用这个池子
        FakeChain e = FakeChain.base().withBtcAndStable();
        e.on(FakeChain.FACTORY, Abi.getPool(FakeChain.USDT, FakeChain.USDC, 100), FakeChain.uint(0));
        var r3 = PoolResolver.resolve(new RpcClient("base", e, 0, () -> 0L), FakeChain.baseChain(), "base", MIXED);
        assertThat(r3.get("base-stb-001").rejected()).isTrue();
        assertThat(r3.get("base-stb-001").reason()).contains("100").contains("USDT");
    }

    // ---------------- 策略组合 ----------------

    @Test
    void strategyMatrixPerAsset() {
        List<Strategy> eth = Strategy.forPool("base-005");
        assertThat(eth).hasSize(23);
        assertThat(eth).extracting(Strategy::name).contains("base-005-eth", "base-005-w05-H-c30");
        assertThat(Strategy.forPool("base-005", "ETH")).isEqualTo(eth);   // ETH 池的策略名不变
        List<Strategy> btc = Strategy.forPool("arb-btc-005", "BTC");
        assertThat(btc).hasSize(23);
        assertThat(btc.stream().filter(s -> !s.benchmark())).hasSize(20);
        assertThat(btc).extracting(Strategy::name).contains("arb-btc-005-btc", "arb-btc-005-hold5050", "arb-btc-005-usdc",
                "arb-btc-005-w50-H-c0").doesNotContain("arb-btc-005-eth");
        assertThat(LpReports.label(btc.get(22))).isEqualTo("基准：只持有 BTC");
        List<Strategy> stb = Strategy.forPool("base-stb-001", "STABLE");
        assertThat(stb).extracting(Strategy::name).containsExactly("base-stb-001-b05-U-c0", "base-stb-001-b05-U-c30",
                "base-stb-001-b10-U-c0", "base-stb-001-b10-U-c30", "base-stb-001-b50-U-c0", "base-stb-001-b50-U-c30", "base-stb-001-usdc");
        assertThat(stb).noneMatch(Strategy::hedged);
    }

    // ---------------- 账本 ----------------

    @Test
    void btcPoolPaysGasInEthAndSkipsRebalanceWithoutEthPrice() {
        LpBookTest.FakeLedger ledger = new LpBookTest.FakeLedger();
        LpBook book = new LpBook(ARB_BTC, Strategy.forPool(ARB_BTC.key(), "BTC"), Map.of(), null, ledger);
        long t0 = 1_800_000_000L - 1_800_000_000L % 60;
        book.onSnapshot(snap(ARB_BTC, t0, 100_000, 100_010.0, 3_000.0));
        assertThat(book.started()).isTrue();
        PositionState h = book.states.get("arb-btc-005-w50-H-c0");
        double btcQty = LpMath.wethUsdc(ARB_BTC, h.liquidity, h.tickLower, h.tickUpper, LpMath.sqrtRaw(sqrtX96(ARB_BTC, 100_000)))[0];
        assertThat(h.hedgeQty).isCloseTo(btcQty, within(1e-12));   // BTCUSDT 空单 = 仓位里的 BTC
        assertThat(book.states.get("arb-btc-005-btc").holdWeth).isCloseTo(0.1, within(1e-12));
        // 出 ±2% 区间：gas 按 ETH 价格 3,000 换算（不是 BTC 价格）
        book.onSnapshot(snap(ARB_BTC, t0 + 60, 103_000, 103_010.0, 3_000.0));
        PositionState c0 = book.states.get("arb-btc-005-w02-U-c0");
        assertThat(c0.rebalances).isEqualTo(1);
        assertThat(c0.gasCost).isCloseTo((1e7 * 6e5 + 2e12) / 1e18 * 3_000, within(1e-9));
        // ETH 价格缺了超过 1 小时：不调仓，记一次 COST_GAP
        LpBookTest.FakeLedger l2 = new LpBookTest.FakeLedger();
        LpBook b2 = new LpBook(ARB_BTC, Strategy.forPool(ARB_BTC.key(), "BTC"), Map.of(), null, l2);
        b2.onSnapshot(snap(ARB_BTC, t0, 100_000, 100_010.0, 3_000.0));
        long t = t0 + 60;
        for (; t <= t0 + 3600; t += 60) b2.onSnapshot(snap(ARB_BTC, t, 100_000, 100_010.0, null));
        b2.onSnapshot(snap(ARB_BTC, t, 103_000, 103_010.0, null));
        assertThat(b2.states.get("arb-btc-005-w02-U-c0").rebalances).isZero();
        assertThat(l2.events).contains("COST_GAP arb-btc-005");
        b2.onSnapshot(snap(ARB_BTC, t + 60, 103_000, 103_010.0, 3_100.0));
        assertThat(b2.states.get("arb-btc-005-w02-U-c0").rebalances).isEqualTo(1);
    }

    /** 记事件和提醒的假账本。 */
    static final class AlertLedger extends LpBookTest.FakeLedger {
        final List<String[]> alerts = new ArrayList<>();

        @Override
        public void alert(String eventKey, String text) {
            alerts.add(new String[] {eventKey, text});
        }
    }

    @Test
    void stablePoolStartsWithoutPerpAndRecordsDepeg() {
        AlertLedger ledger = new AlertLedger();
        LpBook book = new LpBook(STB, Strategy.forPool(STB.key(), "STABLE"), Map.of(), null, ledger);
        long t0 = 1_800_000_000L - 1_800_000_000L % 60;
        book.onSnapshot(snap(STB, t0, 1.0, null, 3_000.0));
        assertThat(book.started()).isTrue();   // 没有永续报价也能开始（不对冲）
        assertThat(book.states).hasSize(7);
        book.onSnapshot(snap(STB, t0 + 60, 1.0002, null, 3_000.0));
        assertThat(book.states.get("base-stb-001-b05-U-c0").validMinutes).isEqualTo(1);
        // 偏离 0.6%：记一条 DEPEG，不提醒；继续偏离不重复记
        book.onSnapshot(snap(STB, t0 + 120, 0.994, null, 3_000.0));
        book.onSnapshot(snap(STB, t0 + 180, 0.993, null, 3_000.0));
        assertThat(ledger.events.stream().filter(e -> e.equals("DEPEG base-stb-001")).count()).isEqualTo(1);
        assertThat(ledger.alerts).isEmpty();
        // 超过 2%：发一次 Telegram
        book.onSnapshot(snap(STB, t0 + 240, 0.97, null, 3_000.0));
        book.onSnapshot(snap(STB, t0 + 300, 0.96, null, 3_000.0));
        assertThat(ledger.alerts).hasSize(1);
        assertThat(ledger.alerts.getFirst()[1]).startsWith("【LP】").contains("base-stb-001").contains("3.00%");
        // 回到 0.5% 以内：DEPEG_END 带最大偏离
        book.onSnapshot(snap(STB, t0 + 360, 1.0, null, 3_000.0));
        int i = ledger.events.indexOf("DEPEG_END base-stb-001");
        assertThat(i).isPositive();
        assertThat((Double) ledger.details.get(i).get("maxDeviation")).isCloseTo(0.04, within(1e-6));
        assertThat(LpReports.maxDepeg(STB, List.of(snap(STB, t0, 1.0, null, null), snap(STB, t0 + 60, 0.97, null, null),
                Snapshot.gap(t0 + 120, STB.key(), null, null, "x")))).isCloseTo(0.03, within(1e-9));
    }

    // ---------------- 报告 ----------------

    static LpReports.Row row(Strategy s, double value, long days, String asset) {
        PositionState st = new PositionState();
        st.strategy = s.name();
        st.started = true;
        st.startTs = 1_800_000_000L;
        st.lastTs = st.startTs + days * 86_400;
        st.lastValue = value;
        return new LpReports.Row(s, st, 1.0, asset);
    }

    @Test
    void telegramLineGroupsByAssetAndShowsAnnualThreshold() {
        List<LpReports.Row> rows = new ArrayList<>();
        int i = 0;
        for (Strategy s : Strategy.forPool("base-005")) rows.add(row(s, 10_000 + 10 * i++, 30, "ETH"));
        for (Strategy s : Strategy.forPool("base-btc-005", "BTC")) rows.add(row(s, 10_000 - 5 * i++, 30, "BTC"));
        for (Strategy s : Strategy.forPool("base-stb-001", "STABLE")) rows.add(row(s, 10_000 + i++, 30, "STABLE"));
        String line = LpReports.telegramLine(LocalDate.of(2026, 11, 3), rows);
        assertThat(line.lines().count()).isEqualTo(1);
        assertThat(line).startsWith("【LP】2026-11-03 ETH组 前3 ").contains("；BTC组 前3 ").contains("；稳定币组 前3 ")
                .contains("（年化 ").contains("门槛 15%）").contains("｜持有50/50 ").contains("｜只持有USDC ");
        assertThat(line.indexOf("BTC组")).isLessThan(line.indexOf("稳定币组"));
        // 只有 ETH 池时没有其他组
        String ethOnly = LpReports.telegramLine(LocalDate.of(2026, 11, 3), rows.subList(0, 23));
        assertThat(ethOnly).doesNotContain("BTC组").doesNotContain("稳定币组");
    }

    @Test
    void fifteenPercentAnnualIsRequiredOnTopOfTheOriginalCriteria() {
        LpReports.Stats good = new LpReports.Stats(0.001, 0.0002, 0.002, 0.02, 3, 60);
        assertThat(LpReports.passes(0.20, good, 0, 0.99)).isTrue();
        assertThat(LpReports.passes(0.149, good, 0, 0.99)).isFalse();   // 超过 USDC 5 个点，但不到 15%
        assertThat(LpReports.passes(0.15, good, 0, 0.99)).isTrue();
        assertThat(LpReports.passes(0.30, good, 1, 0.99)).isFalse();    // 有风险事件
        assertThat(LpReports.passes(0.30, new LpReports.Stats(0.001, -0.0001, 0.002, 0.02, 3, 60), 0, 0.99)).isFalse();
        // 周报按设置汇总时，稳定币和 BTC 的策略名也能识别
        assertThat(LpReports.SHAPE.matcher("base-stb-001-b05-U-c30").find()).isTrue();
        assertThat(LpReports.SHAPE.matcher("arb-btc-005-w05-H-c0").find()).isTrue();
        assertThat(LpReports.SHAPE.matcher("arb-btc-005-btc").find()).isFalse();
        assertThat(LpReports.SHAPE.matcher("base-stb-001-usdc").find()).isFalse();
    }

    @Test
    void geckoVolumeParsing() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(LpShadowJob.parseGeckoVolume(json.readTree(
                "{\"data\":{\"attributes\":{\"reserve_in_usd\":\"1130000.5\",\"volume_usd\":{\"h24\":\"2170000.25\"}}}}")))
                .isEqualTo(2_170_000.25);
        assertThat(LpShadowJob.parseGeckoVolume(json.readTree("{\"errors\":[{\"status\":\"404\"}]}"))).isNull();
        assertThat(LpShadowJob.parseGeckoVolume(json.readTree("{\"data\":{\"attributes\":{\"volume_usd\":{\"h24\":\"abc\"}}}}"))).isNull();
    }
}
