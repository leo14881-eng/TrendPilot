package com.trendpilot.btcdaily;

import java.time.LocalDate;

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

    public double maxFloatLoss() {
        return maxFloatLoss;
    }

    public double roundMaxFloatLoss() {
        return roundMaxFloatLoss;
    }
}
