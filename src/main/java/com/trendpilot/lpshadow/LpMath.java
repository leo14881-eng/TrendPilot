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
     * 标准 V3 公式：流动性 L、区间 [sa, sb]（√P 原始单位）、当前 √P = s 时持有的 token0 / token1（原始单位）。
     * 价格在区间下方全是 token0，上方全是 token1。
     */
    public static double[] amounts(double liquidity, double sa, double sb, double s) {
        if (s <= sa) return new double[] {liquidity * (sb - sa) / (sa * sb), 0};
        if (s >= sb) return new double[] {0, liquidity * (sb - sa)};
        return new double[] {liquidity * (sb - s) / (s * sb), liquidity * (s - sa)};
    }

    /** 仓位里的 [WETH 数量, USDC 数量]（人类单位）。 */
    public static double[] wethUsdc(PoolInfo p, double liquidity, int tickLower, int tickUpper, double sqrtRaw) {
        double[] a = amounts(liquidity, sqrtAtTick(tickLower), sqrtAtTick(tickUpper), sqrtRaw);
        double a0 = a[0] / Math.pow(10, p.dec0());
        double a1 = a[1] / Math.pow(10, p.dec1());
        return p.wethIsToken0() ? new double[] {a0, a1} : new double[] {a1, a0};
    }

    /** 仓位价值（USDC）。 */
    public static double value(PoolInfo p, double liquidity, int tickLower, int tickUpper, double sqrtRaw) {
        double[] wu = wethUsdc(p, liquidity, tickLower, tickUpper, sqrtRaw);
        return wu[0] * ethPriceAtSqrt(p, sqrtRaw) + wu[1];
    }

    /** 用 valueUsdc 的资金在 [tickLower, tickUpper) 开仓能得到的流动性（价值和 L 成正比）。 */
    public static double liquidityFor(PoolInfo p, double valueUsdc, int tickLower, int tickUpper, double sqrtRaw) {
        double unit = value(p, 1.0, tickLower, tickUpper, sqrtRaw);
        return unit > 0 ? valueUsdc / unit : 0;
    }

    /**
     * 以当前 ETH 价格为中心、上下各 width（如 0.05 = ±5%）的区间，换成 tick 并对齐 tickSpacing（下沿向下、上沿向上取整）。
     * token0 / token1 顺序不同时原始价格方向相反，这里统一处理。
     */
    public static int[] rangeTicks(PoolInfo p, double sqrtRaw, double width) {
        double eth = ethPriceAtSqrt(p, sqrtRaw);
        double ra = rawPrice(p, eth * (1 - width));
        double rb = rawPrice(p, eth * (1 + width));
        int spacing = p.tickSpacing();
        int lo = Math.floorDiv(tickAtRawPrice(Math.min(ra, rb)), spacing) * spacing;
        int hi = -Math.floorDiv(-tickAtRawPrice(Math.max(ra, rb)), spacing) * spacing;
        int minT = -Math.floorDiv(-MIN_TICK, spacing) * spacing;   // 向内对齐
        int maxT = Math.floorDiv(MAX_TICK, spacing) * spacing;
        lo = Math.max(lo, minT);
        hi = Math.min(hi, maxT);
        if (hi <= lo) hi = lo + spacing;
        return new int[] {lo, hi};
    }

    public static boolean inRange(int tick, int tickLower, int tickUpper) {
        return tick >= tickLower && tick < tickUpper;
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
