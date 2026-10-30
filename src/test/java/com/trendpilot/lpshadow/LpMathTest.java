package com.trendpilot.lpshadow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

/** 价格 / tick 互转（6 位和 18 位精度、两种 token 顺序）、区间边界连续、全区间无常损失理论值。 */
class LpMathTest {

    /** WETH 是 token0（Base、Arbitrum 的 WETH/USDC 都是这样）。 */
    static final PoolInfo W0 = new PoolInfo("w0", "base", 500, "0xp", "0xw", "0xu", 18, 6, true, 10);
    /** USDC 是 token0（假设的反向池子，验证换算）。 */
    static final PoolInfo U0 = new PoolInfo("u0", "x", 3000, "0xp", "0xu", "0xw", 6, 18, false, 60);

    static double rel(double a, double b) {
        return Math.abs(a / b - 1);
    }

    @Test
    void tickAndPriceRoundTripWithDecimals() {
        // ETH = 3000 USDC，WETH 是 token0：原始价格 = 3000 × 10^6 / 10^18 = 3e-9，tick ≈ -196257
        double raw = LpMath.rawPrice(W0, 3000);
        assertThat(raw).isCloseTo(3e-9, within(1e-15));
        int tick = LpMath.tickAtRawPrice(raw);
        assertThat(tick).isEqualTo(-196257);
        assertThat(rel(LpMath.ethPriceAtTick(W0, tick), 3000)).isLessThan(1.0001 - 1);
        // USDC 是 token0：原始价格 = (1/3000) × 10^18 / 10^6，tick 为正
        double rawU = LpMath.rawPrice(U0, 3000);
        assertThat(rel(rawU, 1e12 / 3000)).isLessThan(1e-12);
        int tickU = LpMath.tickAtRawPrice(rawU);
        assertThat(tickU).isBetween(196255, 196257);
        assertThat(rel(LpMath.ethPriceAtTick(U0, tickU), 3000)).isLessThan(1.0001 - 1);
        // sqrtPriceX96 → 价格
        BigInteger sqrtX96 = new BigInteger("4339505179874779662909440");
        assertThat(rel(LpMath.ethPriceAtSqrt(W0, LpMath.sqrtRaw(sqrtX96)), 3000)).isLessThan(1e-9);
        // tickAtRawPrice 与 1.0001^tick 一致
        for (int t : new int[] {-887000, -196257, -1, 0, 1, 12345, 196256, 887000}) {
            assertThat(LpMath.tickAtRawPrice(Math.pow(1.0001, t) * (1 + 1e-12))).isEqualTo(t);
        }
    }

    @Test
    void rangeTicksAreCenteredOnEthPriceForBothTokenOrders() {
        for (PoolInfo p : new PoolInfo[] {W0, U0}) {
            double sqrt = Math.sqrt(LpMath.rawPrice(p, 3000));
            int[] r = LpMath.rangeTicks(p, sqrt, 0.05);
            assertThat(r[0] % p.tickSpacing()).isZero();
            assertThat(r[1] % p.tickSpacing()).isZero();
            double a = LpMath.ethPriceAtTick(p, r[0]);
            double b = LpMath.ethPriceAtTick(p, r[1]);
            double lo = Math.min(a, b), hi = Math.max(a, b);
            double step = Math.pow(1.0001, p.tickSpacing()) - 1;
            assertThat(lo).isLessThanOrEqualTo(2850 * (1 + 1e-9)).isGreaterThan(2850 * (1 - step - 1e-9));
            assertThat(hi).isGreaterThanOrEqualTo(3150 * (1 - 1e-9)).isLessThan(3150 * (1 + step + 1e-9));
            int tick = LpMath.tickAtRawPrice(sqrt * sqrt);
            assertThat(LpMath.inRange(tick, r[0], r[1])).isTrue();
        }
    }

    @Test
    void positionValueIsContinuousAtRangeBoundaries() {
        for (PoolInfo p : new PoolInfo[] {W0, U0}) {
            double sqrt = Math.sqrt(LpMath.rawPrice(p, 3000));
            int[] r = LpMath.rangeTicks(p, sqrt, 0.05);
            double liq = LpMath.liquidityFor(p, 10_000, r[0], r[1], sqrt);
            assertThat(LpMath.value(p, liq, r[0], r[1], sqrt)).isCloseTo(10_000, within(1e-6));
            for (int edge : r) {
                double s = LpMath.sqrtAtTick(edge);
                double below = LpMath.value(p, liq, r[0], r[1], s * (1 - 1e-9));
                double at = LpMath.value(p, liq, r[0], r[1], s);
                double above = LpMath.value(p, liq, r[0], r[1], s * (1 + 1e-9));
                assertThat(rel(below, at)).isLessThan(1e-4);   // < 0.01%
                assertThat(rel(above, at)).isLessThan(1e-4);
            }
            // 下方全是一种代币、上方全是另一种
            double[] low = LpMath.wethUsdc(p, liq, r[0], r[1], LpMath.sqrtAtTick(r[0]) * 0.99);
            double[] high = LpMath.wethUsdc(p, liq, r[0], r[1], LpMath.sqrtAtTick(r[1]) * 1.01);
            if (p.wethIsToken0()) {
                assertThat(low[1]).isZero();     // 价格低于区间：全是 ETH
                assertThat(high[0]).isZero();    // 高于区间：全是 USDC
            } else {
                assertThat(low[0]).isZero();     // 原始价格低 = ETH 价格高：全是 USDC
                assertThat(high[1]).isZero();
            }
        }
    }

    @Test
    void fullRangeImpermanentLossMatchesTheory() {
        int lo = -887270, hi = 887270;
        for (PoolInfo p : new PoolInfo[] {W0, U0}) {
            double s0 = Math.sqrt(LpMath.rawPrice(p, 3000));
            double liq = LpMath.liquidityFor(p, 10_000, lo, hi, s0);
            double[] hold = LpMath.wethUsdc(p, liq, lo, hi, s0);
            assertThat(hold[0] * 3000).isCloseTo(hold[1], within(1e-3));   // 全区间开仓是 50/50
            for (double r : new double[] {0.25, 0.5, 0.9, 1.0, 1.1, 2.0, 4.0}) {
                double p1 = 3000 * r;
                double s1 = Math.sqrt(LpMath.rawPrice(p, p1));
                double lp = LpMath.value(p, liq, lo, hi, s1);
                double hodl = hold[0] * p1 + hold[1];
                assertThat(lp / hodl).isCloseTo(2 * Math.sqrt(r) / (1 + r), within(1e-6));
            }
        }
    }
}
