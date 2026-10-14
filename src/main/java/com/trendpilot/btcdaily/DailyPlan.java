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
 */
public record DailyPlan(LocalDate day, String version, double open, double atr, double ema, double prevClose,
                        boolean strong, boolean carried, double tpPct, List<Level> levels, boolean skipNew,
                        double multiplier, String reasons) {

    /** @param index 本轮第几次买入（0 起） */
    public record Level(int index, double price, double usdt) {
    }

    public double atrPct() {
        return open > 0 ? atr / open * 100 : 0;
    }
}
