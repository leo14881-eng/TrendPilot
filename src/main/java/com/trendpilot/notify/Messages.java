package com.trendpilot.notify;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

import com.trendpilot.capital.ReviewResult;
import com.trendpilot.monitor.ExitDecision;
import com.trendpilot.risk.RiskState;

/** Telegram 消息文本。所有交易消息都标明"影子"。 */
public final class Messages {

    private Messages() {
    }

    public static String entry(ReviewResult r) {
        String verb = r.action().name().equals("ADD") ? "影子加仓 ADD" : "影子买入 BUY";
        return String.format(Locale.ROOT, """
                🟢 %s %s
                成交均价 %s，金额 %s U（含费 %s U）
                评分 %.2f，RR %.2f，价差 %.1f bps，滑点 %.1f bps
                研究缺口：%s""",
                verb, r.symbol(), price(r.fill().avgPrice()), money(r.fill().notional()), money(r.fill().fee()),
                r.score(), r.rr(), r.spreadBps(), r.slippageBps(), String.join(", ", r.gaps()));
    }

    public static String exit(ExitDecision d) {
        String icon = d.netPnl().signum() >= 0 ? "🔵" : "🔴";
        return String.format(Locale.ROOT, """
                %s 影子卖出 SELL %s
                原因：%s
                成交均价 %s，净盈亏 %s U，原始收益 %.2f%%""",
                icon, d.symbol(), d.reason(), price(d.fill().avgPrice()), money(d.netPnl()), d.rawReturnPct());
    }

    public static String note(String symbol, String note, ExitDecision d) {
        String what = switch (note) {
            case "PROTECT_ARM" -> "启动利润保护";
            case "PROTECTION_GAPPED" -> "价格跳空穿透保护窗口，当前已无净利，按规则不亏损卖出";
            case "LOSS_RECOVERY" -> "信号连续走弱但仍亏损，进入 LOSS_RECOVERY 观察";
            default -> note;
        };
        return String.format(Locale.ROOT, "🟡 %s %s\n当前可退出净利 %s U，原始收益 %.2f%%",
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

    public static String lines(List<String> lines) {
        return String.join("\n", lines);
    }

    public static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static String price(BigDecimal v) {
        return v.round(new java.math.MathContext(8)).stripTrailingZeros().toPlainString();
    }
}
