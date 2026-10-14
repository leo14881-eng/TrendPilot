package com.trendpilot.btcdaily;

import java.time.LocalDate;

/**
 * 一次影子成交。usdt 买入为含手续费的花费，卖出为扣手续费后的到手金额；pnl 只在卖出时有值。
 * posQty / posCost 为这笔成交之后的持仓数量和成本（卖出后为 0）。
 */
public record Fill(LocalDate day, long barOpenTime, Side side, int level, double price, double qty, double usdt,
                   double fee, double pnl, String reason, double posQty, double posCost) {

    public double posAvg() {
        return posQty > 0 ? posCost / posQty : 0;
    }

    public enum Side { BUY, SELL }
}
