package com.trendpilot.lpshadow;

import java.math.BigInteger;

/**
 * Uniswap V3 价格和流动性换算（double 精度，用于影子估值，不用于链上交易）。
 * <ul>
 * <li>"原始价格" P = token1 原始单位 / token0 原始单位 = (sqrtPriceX96 / 2^96)^2 = 1.0001^tick；</li>
 * <li>"ETH 价格" = 1 WETH 值多少 USDC，已处理 6 位 / 18 位精度和 token0 / token1 顺序。</li>
 * </ul>
 */
public final class LpMath {

    static final double Q96 = Math.pow(2, 96);
    static final double Q128 = Math.pow(2, 128);
    static final double LN_TICK = Math.log(1.0001);
    public static final int MIN_TICK = -887272;
    public static final int MAX_TICK = 887272;

    private LpMath() {
    }

    /** √P（原始单位）。 */
    public static double sqrtRaw(BigInteger sqrtPriceX96) {
        return sqrtPriceX96.doubleValue() / Q96;
    }

    public static double sqrtAtTick(int tick) {
        return Math.exp(tick * LN_TICK / 2);
    }

    /** 原始价格对应的 tick（向下取整），即满足 1.0001^tick ≤ P 的最大整数。 */
    public static int tickAtRawPrice(double rawPrice) {
        int t = (int) Math.floor(Math.log(rawPrice) / LN_TICK);
        // 浮点误差修正
        if (Math.pow(1.0001, t + 1) <= rawPrice) t++;
        if (Math.pow(1.0001, t) > rawPrice) t--;
        return t;
    }

    /** 原始价格 → ETH 价格（USDC / WETH）。 */
    public static double ethPrice(PoolInfo p, double rawPrice) {
        double human = rawPrice * Math.pow(10, p.dec0() - p.dec1()); // token1 / token0（人类单位）
        return p.wethIsToken0() ? human : 1.0 / human;
    }

    /** ETH 价格 → 原始价格。 */
    public static double rawPrice(PoolInfo p, double ethPrice) {
        double human = p.wethIsToken0() ? ethPrice : 1.0 / ethPrice;
        return human / Math.pow(10, p.dec0() - p.dec1());
    }

    public static double ethPriceAtSqrt(PoolInfo p, double sqrtRaw) {
        return ethPrice(p, sqrtRaw * sqrtRaw);
    }

    public static double ethPriceAtTick(PoolInfo p, int tick) {
        return ethPrice(p, Math.pow(1.0001, tick));
    }

    /**
     * 池子 TVL（USDC）和实测集中度 m = L_active × 2√P / TVL（都按 token1 原始单位）。
     * m = 1 相当于所有流动性都是全区间；越大说明其他 LP 越集中在当前价格附近。
     */
    public static double[] tvlAndM(PoolInfo p, BigInteger bal0, BigInteger bal1, BigInteger sqrtPriceX96, BigInteger liquidity) {
        double s = sqrtRaw(sqrtPriceX96);
        double raw = s * s;
        double tvlRaw1 = bal1.doubleValue() + bal0.doubleValue() * raw;
        double m = tvlRaw1 > 0 ? liquidity.doubleValue() * 2 * s / tvlRaw1 : Double.NaN;
        double eth = ethPrice(p, raw);
        double wethBal = (p.wethIsToken0() ? bal0 : bal1).doubleValue() / Math.pow(10, p.wethDecimals());
        double usdcBal = (p.wethIsToken0() ? bal1 : bal0).doubleValue() / Math.pow(10, p.usdcDecimals());
        return new double[] {wethBal * eth + usdcBal, m};
    }
}
