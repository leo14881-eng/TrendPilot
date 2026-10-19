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
 * @param maxDrawdown    权益曲线（已实现 + 浮动盈亏）的最大回撤
 * @param avgMaxNetPct   已平仓每笔持有期间最大可退出净利（相对成本）的平均值，即平均最大浮盈
 * @param avgMinNetPct   同上的最小值的平均，即平均最大浮亏（负数）
 * @param avgHoursToProfit 曾经转为盈利的平仓，从买入到第一次可退出净利 > 0 的平均小时数
 * @param medianReturnPct 每笔平仓净收益率的中位数（V1 没有数量上限，对比以每笔平均/中位为主）
 * @param underwaterCount 当前浮亏中的持仓个数
 * @param underwaterPnl   这些持仓的浮亏合计（≤0）
 */
public record BookStats(String book, int entries, int closed, int wins, double realizedPnl, double unrealizedPnl,
                        int openPositions, double openCost, double avgReturnPct, double avgHoldHours,
                        double maxDrawdown, double avgMaxNetPct, double avgMinNetPct, double avgHoursToProfit,
                        double medianReturnPct, int underwaterCount, double underwaterPnl) {

    public double winRatePct() {
        return closed == 0 ? Double.NaN : wins * 100.0 / closed;
    }

    public String line() {
        return String.format(Locale.ROOT,
                "%s：买入 %d 笔，平仓 %d 笔，胜率 %s（另有浮亏中 %d 个，共 %.2f U），每笔平均 %s、中位 %s，已实现 %.2f U，未实现 %.2f U，平均持有 %s，最大回撤（含浮亏）%.2f U，持仓 %d 个",
                book, entries, closed, Double.isNaN(winRatePct()) ? "-" : String.format(Locale.ROOT, "%.0f%%", winRatePct()),
                underwaterCount, underwaterPnl, pct(avgReturnPct), pct(medianReturnPct),
                realizedPnl, unrealizedPnl,
                Double.isNaN(avgHoldHours) ? "-" : String.format(Locale.ROOT, "%.1f 小时", avgHoldHours),
                maxDrawdown, openPositions)
                + String.format(Locale.ROOT, "，平均最大浮盈 %s，平均最大浮亏 %s，平均转盈利用时 %s",
                pct(avgMaxNetPct), pct(avgMinNetPct),
                Double.isNaN(avgHoursToProfit) ? "-" : String.format(Locale.ROOT, "%.1f 小时", avgHoursToProfit));
    }

    /** 一行简版：笔数、胜率、已实现、未实现、最大回撤、持仓（每日汇总正文用）。 */
    public String shortLine() {
        return String.format(Locale.ROOT, "%s：买入 %d，平仓 %d，胜率 %s，已实现 %+.2f U，未实现 %+.2f U，最大回撤 %.2f U，持仓 %d 个（浮亏 %d 个）",
                book, entries, closed, Double.isNaN(winRatePct()) ? "-" : String.format(Locale.ROOT, "%.0f%%", winRatePct()),
                realizedPnl, unrealizedPnl, maxDrawdown, openPositions, underwaterCount);
    }

    private static String pct(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.2f%%", v);
    }
}
