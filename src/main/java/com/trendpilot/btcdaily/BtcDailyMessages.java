package com.trendpilot.btcdaily;

import java.util.Locale;
import java.util.Map;

/** BTC 每日短线的 Telegram 文本。 */
final class BtcDailyMessages {

    private BtcDailyMessages() {
    }

    static String plan(DailyPlan p, Book b, Map<String, Object> yesterday, BtcDailyProperties props) {
        StringBuilder sb = new StringBuilder();
        sb.append("【BTC 每日短线·影子】").append(p.day()).append(" 计划（参数 ").append(p.version()).append("）\n");
        sb.append("开盘 ").append(money(p.open())).append("｜ATR14 ").append(money(p.atr()))
                .append("（").append(pct(p.atrPct())).append("）｜昨收")
                .append(p.strong() ? "高于" : "低于").append(" EMA20，").append(p.strong() ? "偏强" : "偏弱").append('\n');
        sb.append("\n买入策略：\n");
        if (p.levels().isEmpty()) {
            sb.append("  资金已用满，今天不再买入，等待止盈\n");
        }
        for (DailyPlan.Level l : p.levels()) {
            sb.append("  第 ").append(l.index() + 1).append(" 档").append(l.index() == 0 ? "" : "（加仓）")
                    .append(" 跌到 ").append(money(l.price())).append(" 买 ").append(money(l.usdt())).append(" USDT\n");
        }
        sb.append("\n卖出策略：\n");
        if (b.holding()) {
            double tpPct = BtcDailyRules.takeProfitPct(p, b, props);
            sb.append("  持仓 ").append(qty(b.qty())).append(" BTC，成本 ").append(money(b.cost()))
                    .append("，均价 ").append(money(b.avgCost())).append("（").append(b.openedDay()).append(" 开始持有）\n");
            sb.append("  涨到 ").append(money(BtcDailyRules.takeProfitPrice(p, b, props))).append("（均价 +")
                    .append(pct(tpPct)).append("）全部卖出\n");
            sb.append("  若今天又加仓，止盈价按新均价重算\n");
        } else {
            sb.append("  买入后涨到 均价 +").append(pct(p.tpPct())).append(" 全部卖出\n");
        }
        sb.append("  不止损；当天没卖掉的仓位，次日起目标降为均价 +").append(pct(props.carryTpPct())).append("，回本即走\n");
        sb.append("\n昨日：买入 ").append(yesterday.get("buys")).append(" 次，卖出 ").append(yesterday.get("sells"))
                .append(" 次，已实现 ").append(signed(num(yesterday.get("pnl")))).append(" USDT\n");
        sb.append("累计：完成 ").append(b.rounds()).append(" 轮，已实现 ").append(signed(b.realized()))
                .append(" USDT，最大浮亏 ").append(money(b.maxFloatLoss())).append(" USDT\n");
        sb.append("资金上限 ").append(money(props.capUsdt())).append(" USDT（").append(props.capUntil()).append(" 复评）");
        return sb.toString();
    }

    static String fill(Fill f, Book b, DailyPlan p, BtcDailyProperties props) {
        StringBuilder sb = new StringBuilder("【BTC 每日短线·影子】");
        if (f.side() == Fill.Side.BUY) {
            sb.append(f.reason()).append('\n');
            sb.append("价格 ").append(money(f.price())).append("｜数量 ").append(qty(f.qty())).append(" BTC｜花费 ")
                    .append(money(f.usdt())).append(" USDT（含手续费 ").append(money(f.fee())).append("）\n");
            sb.append("持仓：成本 ").append(money(b.cost())).append("，均价 ").append(money(b.avgCost()))
                    .append("，止盈价 ").append(money(BtcDailyRules.takeProfitPrice(p, b, props)));
        } else {
            double cost = f.usdt() - f.pnl();
            sb.append("卖出（").append(f.reason()).append("）\n");
            sb.append("价格 ").append(money(f.price())).append("｜数量 ").append(qty(f.qty())).append(" BTC｜到手 ")
                    .append(money(f.usdt())).append(" USDT（手续费 ").append(money(f.fee())).append("）\n");
            sb.append("本轮盈亏 ").append(signed(f.pnl())).append(" USDT（").append(pct(f.pnl() / cost * 100))
                    .append("），买入 ").append(f.level()).append(" 次\n");
            sb.append("累计：完成 ").append(b.rounds()).append(" 轮，已实现 ").append(signed(b.realized())).append(" USDT");
        }
        return sb.toString();
    }

    static String backtest(BtcDailyBacktest.Result r, BtcDailyProperties props) {
        return "【BTC 每日短线】历史回测（参数 " + r.version() + "，规则与实盘影子同一套代码）\n"
                + "区间 " + r.from() + " ~ " + r.to() + "（" + r.days() + " 天），有买入的天数 " + r.tradeDays()
                + "，完成 " + r.rounds() + " 轮\n"
                + "已实现 " + signed(r.realized()) + " USDT；期末未卖仓位成本 " + money(r.endCost()) + "，浮动 "
                + signed(r.endUnrealized()) + "\n"
                + "最大浮亏 " + money(r.maxFloatLoss()) + " USDT（上限的 " + pct(r.maxFloatLossPct()) + "）\n"
                + "单轮最长持仓 " + r.maxHoldDays() + " 天，平均 " + r.avgHoldDays() + " 天\n"
                + "同期一直持有 BTC：" + pct(r.buyHoldPct()) + "\n"
                + "说明：不止损，被套时只等回本；资金上限 " + money(props.capUsdt()) + " USDT。";
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    static String money(double v) {
        return String.format(Locale.ROOT, "%,.2f", v);
    }

    static String signed(double v) {
        return (v >= 0 ? "+" : "") + money(v);
    }

    static String pct(double v) {
        return String.format(Locale.ROOT, "%.2f%%", v);
    }

    static String qty(double v) {
        return String.format(Locale.ROOT, "%.5f", v);
    }
}
