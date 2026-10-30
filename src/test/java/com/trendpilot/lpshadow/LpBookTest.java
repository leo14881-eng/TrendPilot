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
        return new Snapshot(ts, P.key(), s, tick, liq, fg0, fg1, BigInteger.valueOf(10_000_000), ethPrice, 0.0001, Snapshot.OK, null);
    }

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
}
