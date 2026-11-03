package com.trendpilot.lpshadow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 策略定义：
 * <ul>
 * <li>ETH / BTC 池：区间宽度（±2% … ±50%）× 是否对冲 × 调仓冷却（0 / 30 分钟），每个池子 20 个；
 *     另有 3 个基准（持有 50/50、只持有 USDC、只持有 ETH 或 BTC），同起点、同本金；</li>
 * <li>稳定币池（2026-11-03）：区间宽度 ±0.05% / ±0.1% / ±0.5% × 不对冲 × 调仓冷却 0 / 30 分钟，6 个，基准只有"只持有 USDC"。</li>
 * </ul>
 * Kind.HOLD_ETH 表示"只持有池子里的 base 代币"（ETH 池是 ETH，BTC 池是 BTC），名字后缀用资产名（-eth / -btc）。
 */
public record Strategy(String name, String pool, Kind kind, double width, boolean hedged, int cooldownMinutes) {

    public enum Kind { LP, HOLD_5050, HOLD_USDC, HOLD_ETH }

    public static final double CAPITAL = 10_000;
    public static final double[] WIDTHS = {0.02, 0.05, 0.10, 0.20, 0.50};
    public static final double[] STABLE_WIDTHS = {0.0005, 0.001, 0.005};
    public static final int[] COOLDOWNS = {0, 30};

    public boolean benchmark() {
        return kind != Kind.LP;
    }

    /** ETH 池的全部策略（20 个做市 + 3 个基准）。 */
    public static List<Strategy> forPool(String pool) {
        return forPool(pool, LpShadowProperties.Pool.ETH);
    }

    /** 按资产给出某个池子的全部策略：ETH / BTC 20 个做市 + 3 个基准；稳定币 6 个做市 + 只持有 USDC。 */
    public static List<Strategy> forPool(String pool, String asset) {
        List<Strategy> out = new ArrayList<>();
        if (LpShadowProperties.Pool.STABLE.equals(asset)) {
            for (double w : STABLE_WIDTHS) {
                for (int c : COOLDOWNS) out.add(new Strategy(lpName(pool, w, false, c), pool, Kind.LP, w, false, c));
            }
            out.add(new Strategy(pool + "-usdc", pool, Kind.HOLD_USDC, 0, false, 0));
            return out;
        }
        for (double w : WIDTHS) {
            for (boolean h : new boolean[] {false, true}) {
                for (int c : COOLDOWNS) {
                    out.add(new Strategy(lpName(pool, w, h, c), pool, Kind.LP, w, h, c));
                }
            }
        }
        out.add(new Strategy(pool + "-hold5050", pool, Kind.HOLD_5050, 0, false, 0));
        out.add(new Strategy(pool + "-usdc", pool, Kind.HOLD_USDC, 0, false, 0));
        out.add(new Strategy(pool + "-" + asset.toLowerCase(Locale.ROOT), pool, Kind.HOLD_ETH, 0, false, 0));
        return out;
    }

    /**
     * 例：base-005-w05-H-c30 = Base 0.05% 池、±5%、对冲、出区间 30 分钟后调仓。
     * 宽度小于 1% 时用基点：base-stb-001-b05-U-c0 = ±0.05%（5 个基点）。
     */
    static String lpName(String pool, double w, boolean hedged, int cooldown) {
        if (w < 0.01) {
            return String.format(Locale.ROOT, "%s-b%02d-%s-c%d", pool, Math.round(w * 10_000), hedged ? "H" : "U", cooldown);
        }
        return String.format(Locale.ROOT, "%s-w%02d-%s-c%d", pool, Math.round(w * 100), hedged ? "H" : "U", cooldown);
    }
}
