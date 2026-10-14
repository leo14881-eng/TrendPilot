package com.trendpilot.btcdaily;

import java.time.LocalDate;

/**
 * 一次影子成交。usdt 买入为含手续费的花费，卖出为扣手续费后的到手金额；pnl 只在卖出时有值。
 */
public record Fill(LocalDate day, long barOpenTime, Side side, int level, double price, double qty, double usdt,
                   double fee, double pnl, String reason) {

    public enum Side { BUY, SELL }
}
