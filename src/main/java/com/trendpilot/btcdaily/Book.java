package com.trendpilot.btcdaily;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BTC 每日短线的独立影子账本（单一持仓）。cost 为含买入手续费的总成本。
 */
public final class Book {

    double qty;
    double cost;
    int buys;
    double lastBuyPrice;
    LocalDate openedDay;
    double realized;
    int rounds;
    int wins;
    LocalDate lastExitDay;
    /** 本轮持仓期间的最大浮亏（USDT，负数），卖出后清零。 */
    double roundMaxFloatLoss;
    /** 账本整体出现过的最大浮亏。 */
    double maxFloatLoss;
    /** 本轮第一次买入所在 K 线的开盘时间。 */
    long openedAt;
    /** 本轮持仓期间相对当时均价的最大不利 / 有利幅度（%）。 */
    double maePct;
    double mfePct;
    /** 开仓当天的市场 / 宏观过滤说明。 */
    String openFilters = "";
    /** 候选止损（% → 首次触发）：实盘不止损，只记录"如果止损会怎样"。 */
    final Map<String, StopHit> stopHits = new LinkedHashMap<>();
    /** 移动止盈（TRAIL）启动后的最高价；0 表示未启动。 */
    double trailPeak;
    /** 最近一次卖出完成的一轮（由调用方取走落库）。 */
    Round lastRound;

    /** 候选止损首次触发：K 线开盘时间、假设止损价、假设盈亏。 */
    public record StopHit(long at, double price, double pnl) {
    }

    /** 完成的一轮。 */
    public record Round(String version, LocalDate openedDay, long openedAt, long closedAt, long holdMinutes, int buys,
                       double cost, double proceeds, double pnl, double pnlPct, double maePct, double mfePct,
                       String exitReason, String openFilters, Map<String, StopHit> stopHits) {
    }

    public boolean holding() {
        return qty > 0;
    }

    public double avgCost() {
        return qty > 0 ? cost / qty : 0;
    }

    /** 按价格扣卖出手续费后的未实现盈亏。 */
    public double unrealized(double price, double feeRate) {
        return qty > 0 ? qty * price * (1 - feeRate) - cost : 0;
    }

    public double qty() {
        return qty;
    }

    public double cost() {
        return cost;
    }

    public int buys() {
        return buys;
    }

    public double lastBuyPrice() {
        return lastBuyPrice;
    }

    public LocalDate openedDay() {
        return openedDay;
    }

    public double realized() {
        return realized;
    }

    public int rounds() {
        return rounds;
    }

    public int wins() {
        return wins;
    }

    public LocalDate lastExitDay() {
        return lastExitDay;
    }

    public long openedAt() {
        return openedAt;
    }

    public double maePct() {
        return maePct;
    }

    public double mfePct() {
        return mfePct;
    }

    public Map<String, StopHit> stopHits() {
        return Map.copyOf(stopHits);
    }

    public double maxFloatLoss() {
        return maxFloatLoss;
    }

    public double roundMaxFloatLoss() {
        return roundMaxFloatLoss;
    }
}
