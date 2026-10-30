package com.trendpilot.lpshadow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 策略定义：池子 × 区间宽度（±2% … ±50%）× 是否对冲 × 调仓冷却（0 / 30 分钟），每个池子 20 个，三个池子共 60 个；
 * 另有 3 个基准（持有 50/50、只持有 USDC、只持有 ETH），同起点、同本金。
 */
public record Strategy(String name, String pool, Kind kind, double width, boolean hedged, int cooldownMinutes) {

    public enum Kind { LP, HOLD_5050, HOLD_USDC, HOLD_ETH }

    public static final double CAPITAL = 10_000;
    public static final double[] WIDTHS = {0.02, 0.05, 0.10, 0.20, 0.50};
    public static final int[] COOLDOWNS = {0, 30};

    public boolean benchmark() {
        return kind != Kind.LP;
    }

    /** 某个池子的全部策略（20 个做市 + 3 个基准）。 */
    public static List<Strategy> forPool(String pool) {
        List<Strategy> out = new ArrayList<>();
        for (double w : WIDTHS) {
            for (boolean h : new boolean[] {false, true}) {
                for (int c : COOLDOWNS) {
                    out.add(new Strategy(lpName(pool, w, h, c), pool, Kind.LP, w, h, c));
                }
            }
        }
        out.add(new Strategy(pool + "-hold5050", pool, Kind.HOLD_5050, 0, false, 0));
        out.add(new Strategy(pool + "-usdc", pool, Kind.HOLD_USDC, 0, false, 0));
        out.add(new Strategy(pool + "-eth", pool, Kind.HOLD_ETH, 0, false, 0));
        return out;
    }

    /** 例：base-005-w05-H-c30 = Base 0.05% 池、±5%、对冲、出区间 30 分钟后调仓。 */
    static String lpName(String pool, double w, boolean hedged, int cooldown) {
        return String.format(Locale.ROOT, "%s-w%02d-%s-c%d", pool, Math.round(w * 100), hedged ? "H" : "U", cooldown);
    }
}
