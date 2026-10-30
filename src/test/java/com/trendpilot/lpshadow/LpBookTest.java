package com.trendpilot.lpshadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 手续费分成（全额 / 一半 / 不计）、feeGrowth 溢出回绕、gap 时不计费。 */
class LpBookTest {

    static final PoolInfo P = LpMathTest.W0;
    static final BigInteger Q128 = BigInteger.ONE.shiftLeft(128);

    static BigInteger sqrtX96(double ethPrice) {
        return new java.math.BigDecimal(Math.sqrt(LpMath.rawPrice(P, ethPrice)) * LpMath.Q96).toBigInteger();
    }

    static Snapshot snap(long ts, double ethPrice, BigInteger liq, BigInteger fg0, BigInteger fg1) {
        BigInteger s = sqrtX96(ethPrice);
        int tick = LpMath.tickAtRawPrice(Math.pow(LpMath.sqrtRaw(s), 2));
        return new Snapshot(ts, P.key(), s, tick, liq, fg0, fg1, BigInteger.valueOf(10_000_000), ethPrice, 0.0001, Snapshot.OK, null,
                L1_WEI, "test");
    }

    /** 一次调仓的 L1 数据费（测试用 2e12 wei）。 */
    static final BigInteger L1_WEI = BigInteger.valueOf(2_000_000_000_000L);

    @Test
    void feeShareMatchesHandCalculation() {
        // L_active = 1e18，自己 L = 1e18 → 分到一半；Δfg0 = 2^128 × 1e-6，Δfg1 = 2^128 × 1e-9
        BigInteger liq = BigInteger.TEN.pow(18);
        BigInteger d0 = Q128.divide(BigInteger.valueOf(1_000_000));
        BigInteger d1 = Q128.divide(BigInteger.valueOf(1_000_000_000));
        Snapshot a = snap(0, 3000, liq, BigInteger.ZERO, BigInteger.ZERO);
        Snapshot b = snap(60, 3000, liq, d0, d1);
        int[] r = LpMath.rangeTicks(P, LpMath.sqrtRaw(a.sqrtPriceX96()), 0.05);
        // fee0 = 1e18 × 1e-6 × 0.5 = 5e11 wei = 5e-7 ETH × 3000 = 0.0015 U；fee1 = 1e18 × 1e-9 × 0.5 = 5e8 = 500 U
        double fee = LpBook.feeUsdc(P, a, b, r[0], r[1], 1e18);
        assertThat(fee).isCloseTo(500.0015, within(1e-6));

        // 只有一端在区间内：一半
        Snapshot out = snap(60, 3300, liq, d0, d1);
        assertThat(LpBook.feeUsdc(P, a, out, r[0], r[1], 1e18)).isCloseTo((0.5e-6 * 3300 + 500) / 2, within(1e-6));
        // 两端都不在：不计
        Snapshot out0 = snap(0, 3300, liq, BigInteger.ZERO, BigInteger.ZERO);
        assertThat(LpBook.feeUsdc(P, out0, out, r[0], r[1], 1e18)).isZero();
    }

    @Test
    void feeGrowthWrapAroundGivesSameFee() {
        BigInteger liq = BigInteger.TEN.pow(18);
        BigInteger d1 = Q128.divide(BigInteger.valueOf(1_000_000_000));
        BigInteger start = Abi.TWO_256.subtract(d1.divide(BigInteger.TWO));   // 离 2^256 只差一半
        BigInteger end = start.add(d1).mod(Abi.TWO_256);                       // 回绕
        assertThat(end).isLessThan(start);
        Snapshot a = snap(0, 3000, liq, BigInteger.ZERO, start);
        Snapshot b = snap(60, 3000, liq, BigInteger.ZERO, end);
        int[] r = LpMath.rangeTicks(P, LpMath.sqrtRaw(a.sqrtPriceX96()), 0.05);
        assertThat(LpBook.feeUsdc(P, a, b, r[0], r[1], 1e18)).isCloseTo(500, within(1e-6));
    }

    /** 记录开仓和事件的假账本。 */
    static final class FakeLedger implements LpBook.Ledger {
        final List<String> events = new ArrayList<>();
        final List<Map<String, Object>> details = new ArrayList<>();
        long ids;

        @Override
        public long openPosition(String strategy, String pool, int tickLower, int tickUpper, double liquidity, long ts) {
            events.add("OPEN " + strategy);
            details.add(Map.of("tickLower", tickLower, "tickUpper", tickUpper));
            return ++ids;
        }

        @Override
        public void closePosition(long id, long ts) {
            events.add("CLOSE " + id);
            details.add(Map.of());
        }

        @Override
        public void event(long ts, String strategy, String type, Map<String, Object> detail) {
            events.add(type + " " + strategy);
            details.add(detail);
        }
    }

    @Test
    void gapIntervalsEarnNoFees() {
        FakeLedger ledger = new FakeLedger();
        LpBook book = new LpBook(P, Strategy.forPool(P.key()), Map.of(), null, ledger);
        BigInteger liq = BigInteger.TEN.pow(24);
        BigInteger step = Q128.divide(BigInteger.valueOf(1_000_000_000));   // 每分钟 Δfg1 = 2^128 × 1e-9
        long t0 = 1_800_000_000L - 1_800_000_000L % 60;
        book.onSnapshot(snap(t0, 3000, liq, BigInteger.ZERO, BigInteger.ZERO));               // 开始
        book.onSnapshot(snap(t0 + 60, 3000, liq, BigInteger.ZERO, step));                       // 有效
        book.onSnapshot(Snapshot.gap(t0 + 120, P.key(), null, null, "RPC 超时"));                // gap
        book.onSnapshot(snap(t0 + 180, 3000, liq, BigInteger.ZERO, step.multiply(BigInteger.valueOf(3)))); // gap 之后第一条：不计
        book.onSnapshot(snap(t0 + 240, 3000, liq, BigInteger.ZERO, step.multiply(BigInteger.valueOf(4)))); // 有效
        book.onSnapshot(snap(t0 + 360, 3000, liq, BigInteger.ZERO, step.multiply(BigInteger.valueOf(6)))); // 缺 1 分钟：不计
        PositionState st = book.states.get("w0-w05-U-c0");
        double perMinute = LpBook.feeUsdc(P, snap(t0, 3000, liq, BigInteger.ZERO, BigInteger.ZERO),
                snap(t0 + 60, 3000, liq, BigInteger.ZERO, step), st.tickLower, st.tickUpper, st.liquidity);
        assertThat(perMinute).isPositive();
        assertThat(st.fees).isCloseTo(2 * perMinute, within(1e-9));
        assertThat(st.validMinutes).isEqualTo(2);
        assertThat(ledger.events.stream().filter(e -> e.startsWith("GAP")).count()).isEqualTo(2);
        assertThat(ledger.details.get(ledger.events.indexOf("GAP w0"))).containsEntry("minutes", 1L);
        // 基准：同起点同本金
        assertThat(book.states.get("w0-hold5050").lastValue).isCloseTo(10_000, within(1e-6));
        assertThat(book.states.get("w0-usdc").lastValue).isEqualTo(10_000);
        assertThat(st.lastValue).isCloseTo(10_000 + st.fees, within(1e-6));
    }

    static Snapshot withPerp(Snapshot s, Double mark, Double rate, BigInteger gas) {
        return new Snapshot(s.ts(), s.pool(), s.sqrtPriceX96(), s.tick(), s.liquidity(), s.fg0(), s.fg1(), gas, mark, rate, s.status(), null,
                s.l1FeeWei(), s.l1Source());
    }

    @Test
    void missingL1FeeForOverAnHourMeansNoRebalance() {
        FakeLedger ledger = new FakeLedger();
        LpBook book = new LpBook(P, Strategy.forPool(P.key()), Map.of(), null, ledger);
        BigInteger liq = BigInteger.TEN.pow(24);
        long t0 = 1_800_000_000L - 1_800_000_000L % 60;
        book.onSnapshot(snap(t0, 3000, liq, BigInteger.ZERO, BigInteger.ZERO));                         // 有 L1 费
        long t = t0 + 60;
        for (; t <= t0 + 3600; t += 60) book.onSnapshot(snap(t, 3000, liq, BigInteger.ZERO, BigInteger.ZERO).withL1(null, null));
        // 第 61 分钟价格出区间：最近的 L1 费是 61 分钟前 → 不调仓，记一次 COST_GAP
        book.onSnapshot(snap(t, 3100, liq, BigInteger.ZERO, BigInteger.ZERO).withL1(null, null));
        book.onSnapshot(snap(t + 60, 3100, liq, BigInteger.ZERO, BigInteger.ZERO).withL1(null, null));
        PositionState c0 = book.states.get("w0-w02-U-c0");
        assertThat(c0.rebalances).isZero();
        assertThat(ledger.events.stream().filter(e -> e.equals("COST_GAP w0")).count()).isEqualTo(1);
        // L1 费恢复：立即调仓
        book.onSnapshot(snap(t + 120, 3100, liq, BigInteger.ZERO, BigInteger.ZERO));
        assertThat(c0.rebalances).isEqualTo(1);
        // 1 小时内的缺失：沿用最近值
        LpBook b2 = new LpBook(P, Strategy.forPool(P.key()), Map.of(), null, new FakeLedger());
        b2.onSnapshot(snap(t0, 3000, liq, BigInteger.ZERO, BigInteger.ZERO));
        b2.onSnapshot(snap(t0 + 60, 3100, liq, BigInteger.ZERO, BigInteger.ZERO).withL1(null, null));
        assertThat(b2.states.get("w0-w02-U-c0").rebalances).isEqualTo(1);
        assertThat(b2.chainCostWei(t0 + 3600)).containsExactly(1e7 * 6e5, 2e12);
        assertThat(b2.chainCostWei(t0 + 3661)).isNull();
    }

    @Test
    void noRebalanceDuringOrRightAfterGapAndCooldownIsRespected() {
        FakeLedger ledger = new FakeLedger();
        LpBook book = new LpBook(P, Strategy.forPool(P.key()), Map.of(), null, ledger);
        BigInteger liq = BigInteger.TEN.pow(24);
        long t0 = 1_800_000_000L - 1_800_000_000L % 60;
        book.onSnapshot(snap(t0, 3000, liq, BigInteger.ZERO, BigInteger.ZERO));
        book.onSnapshot(Snapshot.gap(t0 + 60, P.key(), 3000.0, 0.0001, "RPC 超时"));
        // gap 后第一条：价格已经出 ±2% 区间，但这一段不是有效区间 → 不调仓
        book.onSnapshot(snap(t0 + 120, 3100, liq, BigInteger.ZERO, BigInteger.ZERO));
        assertThat(book.states.get("w0-w02-U-c0").rebalances).isZero();
        // 下一分钟有效：冷却 0 的立即调仓，冷却 30 的开始计时
        book.onSnapshot(snap(t0 + 180, 3100, liq, BigInteger.ZERO, BigInteger.ZERO));
        PositionState c0 = book.states.get("w0-w02-U-c0");
        PositionState c30 = book.states.get("w0-w02-U-c30");
        assertThat(c0.rebalances).isEqualTo(1);
        // 链上成本 = L2 执行费（0.01 gwei × 60 万 gas）+ L1 数据费（2e12 wei），按 ETH 价格换成 USDC
        assertThat(c0.gasCost).isCloseTo((1e7 * 6e5 + 2e12) / 1e18 * 3100, within(1e-9));
        assertThat(c0.l1Cost).isCloseTo(2e12 / 1e18 * 3100, within(1e-12));
        assertThat(LpMath.inRange(book.prev.tick(), c0.tickLower, c0.tickUpper)).isTrue();
        assertThat(c30.rebalances).isZero();
        assertThat(c30.outSince).isEqualTo(t0 + 180);
        // 冷却 30 分钟满了的那一分钟 gasPrice 缺失：沿用 1 小时内最近一次的值照常调仓
        long t = t0 + 240;
        for (; t < t0 + 180 + 30 * 60; t += 60) book.onSnapshot(snap(t, 3100, liq, BigInteger.ZERO, BigInteger.ZERO));
        assertThat(c30.rebalances).isZero();
        book.onSnapshot(withPerp(snap(t, 3100, liq, BigInteger.ZERO, BigInteger.ZERO), 3100.0, 0.0001, null));
        assertThat(c30.rebalances).isEqualTo(1);
        assertThat(c30.gasCost).isCloseTo((1e7 * 6e5 + 2e12) / 1e18 * 3100, within(1e-9));
        // 宽区间没出界，不动
        assertThat(book.states.get("w0-w10-U-c0").rebalances).isZero();
        // 调仓成本 = 一半仓位价值 ×（0.05% + 0.02%）
        assertThat(ledger.details.get(ledger.events.indexOf("REBALANCE w0-w02-U-c0")).get("swap"))
                .isEqualTo(((Double) ledger.details.get(ledger.events.indexOf("REBALANCE w0-w02-U-c0")).get("value")) / 2 * 0.0007);
    }

    @Test
    void hedgeOpensShortAndFundingAndRiskEventsAreRecorded() {
        FakeLedger ledger = new FakeLedger();
        LpBook book = new LpBook(P, Strategy.forPool(P.key()), Map.of(), null, ledger);
        BigInteger liq = BigInteger.TEN.pow(24);
        long t0 = 1_800_028_800L - 120;   // 资金费结算点（8 小时的整数倍）前 2 分钟
        assertThat((t0 + 120) % (8 * 3600)).isZero();
        book.onSnapshot(withPerp(snap(t0, 3000, liq, BigInteger.ZERO, BigInteger.ZERO), 3000.0, 0.0001, BigInteger.TEN));
        PositionState h = book.states.get("w0-w50-H-c0");
        PositionState u = book.states.get("w0-w50-U-c0");
        // 保证金 = 做市资金 × ETH 占比 / 3，空单数量 = 仓位里的 ETH
        double eth = LpMath.wethUsdc(P, h.liquidity, h.tickLower, h.tickUpper, LpMath.sqrtRaw(sqrtX96(3000)))[0];
        assertThat(h.hedgeQty).isCloseTo(eth, within(1e-12));
        assertThat(h.initMargin).isCloseTo((10_000 - h.initMargin) * (eth * 3000 / h.lastLpValue) / 3, within(1e-6));
        assertThat(h.lastValue).isCloseTo(10_000 - eth * 3000 * 0.0005, within(1e-6));   // 只扣了开空单的 taker
        assertThat(u.hedgeQty).isZero();
        // 跨过 08:00：按结算前看到的 0.01% 结算，空单收钱
        book.onSnapshot(withPerp(snap(t0 + 60, 3000, liq, BigInteger.ZERO, BigInteger.ZERO), 3000.0, 0.0001, BigInteger.TEN));
        book.onSnapshot(withPerp(snap(t0 + 120, 3000, liq, BigInteger.ZERO, BigInteger.ZERO), 3000.0, -0.0005, BigInteger.TEN));
        assertThat(h.funding).isCloseTo(h.hedgeQty * 3000 * 0.0001, within(1e-9));
        assertThat(u.funding).isZero();
        assertThat(ledger.events).contains("FUNDING w0-w50-H-c0");
        // 价格大涨：离强平价不到 15% 记一次风险事件（只记一次），涨过强平价按爆仓处理
        double liqPrice = LpBook.liquidationPrice(h.margin, h.hedgeQty, h.hedgeEntry);
        double near = liqPrice / 1.10;
        book.onSnapshot(withPerp(snap(t0 + 180, near, liq, BigInteger.ZERO, BigInteger.ZERO), near, 0.0001, BigInteger.TEN));
        book.onSnapshot(withPerp(snap(t0 + 240, near, liq, BigInteger.ZERO, BigInteger.ZERO), near, 0.0001, BigInteger.TEN));
        assertThat(h.riskEvents).isEqualTo(1);
        assertThat(ledger.events.stream().filter(e -> e.equals("LIQ_RISK w0-w50-H-c0")).count()).isEqualTo(1);
        // 一分钟内跳到强平价以上：按原来的空单爆仓（保证金清零，之后不再对冲）
        PositionState h2 = book.states.get("w0-w50-H-c30");
        double above = LpBook.liquidationPrice(h2.margin, h2.hedgeQty, h2.hedgeEntry) * 1.01;
        book.onSnapshot(withPerp(snap(t0 + 300, above, liq, BigInteger.ZERO, BigInteger.ZERO), above, 0.0001, BigInteger.TEN));
        h = h2;
        assertThat(h.liquidated).isTrue();
        assertThat(h.hedgeQty).isZero();
        assertThat(ledger.events).contains("LIQUIDATION w0-w50-H-c30");
    }
}
