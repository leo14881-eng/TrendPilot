package com.trendpilot.portfolio;

import java.util.Locale;

/**
 * 一个账本的收益统计（金额单位 U）。
 *
 * @param entries        买入 + 加仓笔数
 * @param closed         已平仓笔数
 * @param wins           盈利平仓笔数
 * @param unrealizedPnl  当前持仓按最近一次 Monitor 全数量模拟卖出的净盈亏合计
 * @param avgReturnPct   每笔平仓净收益率（相对含费成本）的平均值
 * @param avgHoldHours   平均持有小时数
 * @param maxDrawdown    已实现盈亏累计曲线的最大回撤
 */
public record BookStats(String book, int entries, int closed, int wins, double realizedPnl, double unrealizedPnl,
                        int openPositions, double openCost, double avgReturnPct, double avgHoldHours,
                        double maxDrawdown) {

    public double winRatePct() {
        return closed == 0 ? Double.NaN : wins * 100.0 / closed;
    }

    public String line() {
        return String.format(Locale.ROOT,
                "%s：买入 %d 笔，平仓 %d 笔，胜率 %s，已实现 %.2f U，未实现 %.2f U，每笔平均 %s，平均持有 %s，最大回撤 %.2f U，持仓 %d 个",
                book, entries, closed, Double.isNaN(winRatePct()) ? "-" : String.format(Locale.ROOT, "%.0f%%", winRatePct()),
                realizedPnl, unrealizedPnl,
                Double.isNaN(avgReturnPct) ? "-" : String.format(Locale.ROOT, "%.2f%%", avgReturnPct),
                Double.isNaN(avgHoldHours) ? "-" : String.format(Locale.ROOT, "%.1f 小时", avgHoldHours),
                maxDrawdown, openPositions);
    }
}
