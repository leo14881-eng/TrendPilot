package com.trendpilot.notify;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import com.trendpilot.capital.ReviewResult;
import com.trendpilot.market.Venue;
import com.trendpilot.monitor.ExitDecision;
import com.trendpilot.risk.RiskState;

/** Telegram 消息文本。所有交易消息都标明"影子"。 */
public final class Messages {

    private Messages() {
    }

    public static String entry(Venue venue, ReviewResult r) {
        String verb = r.action().name().equals("ADD") ? "V2 影子加仓 ADD" : "V2 影子买入 BUY";
        return String.format(Locale.ROOT, """
                🟢 %s %s%s
                原因：通过 Capital Review，获得 V2 资金池额度
                成交均价 %s，金额 %s U（含费 %s U）
                评分 %.2f，RR %.2f，价差 %.1f bps，滑点 %.1f bps
                研究缺口：%s""",
                verb, r.symbol(), venueTag(venue), price(r.fill().avgPrice()), money(r.fill().notional()), money(r.fill().fee()),
                r.score(), r.rr(), r.spreadBps(), r.slippageBps(), String.join(", ", r.gaps()));
    }

    public static String exit(Venue venue, ExitDecision d) {
        String icon = d.netPnl().signum() >= 0 ? "🔵" : "🔴";
        return String.format(Locale.ROOT, """
                %s V2 影子卖出 SELL %s%s
                原因：%s
                成交均价 %s，净盈亏 %s U，原始收益 %.2f%%""",
                icon, d.symbol(), venueTag(venue), reasonText(d.reason()), price(d.fill().avgPrice()), money(d.netPnl()), d.rawReturnPct());
    }

    /** 卖出原因：止损类给出中文说明，其余保持原代码（与历史通知一致）。 */
    static String reasonText(String reason) {
        return switch (reason) {
            case "STOP_LOSS" -> "STOP_LOSS 止损（跌破买入时日线波动 ×1.5 或 −30%）";
            case "STALE_EXIT" -> "STALE_EXIT 持有 3 天仍亏损，反弹高点离场";
            case "EARLY_EXIT" -> "EARLY_EXIT 持有期间一直没赚过且亏损超过线，提前离场（已转正的参数）";
            default -> reason;
        };
    }

    public static String note(String symbol, String note, ExitDecision d) {
        String what = switch (note) {
            case "PROTECT_ARM" -> "启动利润保护";
            case "PROTECTION_GAPPED" -> "价格跳空穿透保护窗口，当前已无净利，按规则不亏损卖出";
            case "LOSS_RECOVERY" -> "信号连续走弱但仍亏损，进入 LOSS_RECOVERY 观察";
            case "DEGRADED_QUOTE" -> "盘口拿不到，按最优买价再低 1% 保守模拟卖出";
            default -> note;
        };
        return String.format(Locale.ROOT, "🟡 V2 %s %s\n当前可退出净利 %s U，原始收益 %.2f%%",
                symbol, what, money(d.netPnl()), d.rawReturnPct());
    }

    public static String regime(String from, RiskState r) {
        return String.format(Locale.ROOT, "⚠️ 市场状态 %s → %s\nBTC 24h %.2f%%，4h %.2f%%，上涨占比 %.0f%%%s",
                from, r.regime(), r.btc24hPct(), r.btc4hPct(), r.breadth() * 100,
                r.reasons().isEmpty() ? "" : "\n" + String.join(", ", r.reasons()));
    }

    public static String failure(String job, String error) {
        return "❗ 任务失败 " + job + "\n" + error;
    }

    /** Bybit 独立池的标的在消息里标注交易所。 */
    public static String venueTag(Venue venue) {
        return venue == Venue.BINANCE ? "" : "（" + venue + "）";
    }

    public static String lines(List<String> lines) {
        return String.join("\n", lines);
    }

    /** 按分值从大到小列出前 n 行，其余只给数量。 */
    public static String top(List<Map.Entry<Double, String>> ranked, int n, String by) {
        List<String> sorted = ranked.stream().sorted(Map.Entry.<Double, String>comparingByKey().reversed())
                .map(Map.Entry::getValue).toList();
        if (sorted.size() <= n) return lines(sorted);
        return "（只列" + by + "的 " + n + " 个，另有 " + (sorted.size() - n) + " 个见网页）\n" + lines(sorted.subList(0, n));
    }

    public static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static String price(BigDecimal v) {
        return v.round(new java.math.MathContext(8)).stripTrailingZeros().toPlainString();
    }
}
