package com.trendpilot.btcdaily;

import java.time.LocalDate;
import java.util.List;

/**
 * 某一天（UTC 日，对应越南时间当天 07:00 起）的买卖计划。
 *
 * @param strong  昨收是否高于 EMA
 * @param carried 生成计划时是否还持有之前未卖的仓位
 * @param levels  还可以买的档位，按价格从高到低
 * @param tpPct   当天新开仓位的止盈幅度（%）；跨天仓位用 carryTpPct
 * @param skipNew    市场 / 宏观过滤：当天不开新一轮
 * @param multiplier 市场 / 宏观过滤：新一轮档位金额乘数
 * @param reasons    过滤原因和缺失的数据源
 * @param strategy   V2 / V3
 * @param regime     行情：UP 上升 / SIDE 震荡 / DOWN 下跌
 * @param hourAtr    1h ATR（V3 跟踪止盈用；V2 为 0）
 * @param addFloor   V3：加仓档位须不低于此价（上升 = 日线 EMA20，震荡 = EMA50）
 */
public record DailyPlan(LocalDate day, String version, double open, double atr, double ema, double prevClose,
                        boolean strong, boolean carried, double tpPct, List<Level> levels, boolean skipNew,
                        double multiplier, String reasons, String strategy, String regime, double hourAtr,
                        double addFloor) {

    /** 同一份计划去掉全部挂单（运行租约确认不了时，空仓不开新一轮用）；只在内存里用，不改已保存的计划。 */
    public DailyPlan withoutLevels() {
        return new DailyPlan(day, version, open, atr, ema, prevClose, strong, carried, tpPct, List.of(), skipNew, multiplier,
                reasons, strategy, regime, hourAtr, addFloor);
    }

    public DailyPlan {
        if (strategy == null || strategy.isBlank()) strategy = "V2";
        if (regime == null) regime = "";
    }

    public boolean v3() {
        return "V3".equals(strategy);
    }

    public static String regimeName(String r) {
        return switch (r) {
            case "UP" -> "上升";
            case "SIDE" -> "震荡";
            case "DOWN" -> "下跌";
            default -> "未知";
        };
    }

    /** @param index 本轮第几次买入（0 起） */
    public record Level(int index, double price, double usdt) {
    }

    public double atrPct() {
        return open > 0 ? atr / open * 100 : 0;
    }
}
