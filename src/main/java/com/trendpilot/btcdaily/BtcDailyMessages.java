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
        if (!p.reasons().isBlank()) {
            sb.append("\n市场 / 宏观过滤：\n");
            for (String r : p.reasons().split("；")) sb.append("  · ").append(r).append('\n');
        }
        sb.append("\n买入策略：\n");
        if (p.levels().isEmpty()) {
            sb.append(p.skipNew() && !b.holding() ? "  今天不开新一轮（见上面的过滤原因）\n"
                    : "  资金已用满，今天不再买入，等待止盈\n");
        } else if (p.multiplier() < 1 && !p.carried()) {
            sb.append("  仓位按过滤结果乘 ").append(p.multiplier()).append('\n');
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

    static String fill(Fill f, Book b, DailyPlan p, Book.Round round, BtcDailyProperties props) {
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
            if (round != null) {
                sb.append("持仓 ").append(hold(round.holdMinutes())).append("｜期间最大浮亏 ").append(pct(round.maePct()))
                        .append("，最大浮盈 ").append(pct(round.mfePct())).append('\n');
                if (!round.stopHits().isEmpty()) {
                    sb.append("如果设了止损（只记录，不执行）：");
                    round.stopHits().forEach((k, h) -> sb.append(k.replace(".0", "")).append("% 会亏 ")
                            .append(money(h.pnl())).append("；"));
                    sb.append('\n');
                }
            }
            sb.append("累计：完成 ").append(b.rounds()).append(" 轮，已实现 ").append(signed(b.realized())).append(" USDT");
        }
        return sb.toString();
    }

    static String backtest(BtcDailyBacktest.Result base, BtcDailyBacktest.Result filtered, BtcDailyProperties props) {
        return "【BTC 每日短线】历史回测（参数 " + base.version() + "，规则与实盘影子同一套代码）\n"
                + "\n■ 不加市场 / 宏观过滤\n" + result(base)
                + "\n\n■ 加过滤（下跌趋势、议息/CPI 日、ETF 流出、资金费率、恐惧贪婪" + (props.filters().fredConfigured() ? "、利率、美元" : "") + "）\n"
                + result(filtered) + "\n不开新一轮 " + filtered.skippedDays() + " 天，减仓 " + filtered.reducedDays() + " 天"
                + "\n\n■ 如果加止损（基于加过滤版本，只做对比）\n" + stops(filtered)
                + "\n\n说明：不止损，被套时只等回本；资金上限 " + money(props.capUsdt()) + " USDT。ETF 数据从 2024-01 开始；缺失的数据当天不过滤。";
    }

    private static String stops(BtcDailyBacktest.Result r) {
        StringBuilder sb = new StringBuilder("不止损（实际规则）：总盈亏 " + signed(r.realized() + r.endUnrealized()) + " USDT\n");
        for (BtcDailyBacktest.StopAlt a : r.stopAlts()) {
            sb.append("止损 ").append(String.format(Locale.ROOT, "%.0f%%", a.stopPct())).append("：触发 ").append(a.hits())
                    .append(" 次，总盈亏 ").append(signed(a.pnl())).append(" USDT\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String result(BtcDailyBacktest.Result r) {
        return "区间 " + r.from() + " ~ " + r.to() + "（" + r.days() + " 天），有买入的天数 " + r.tradeDays()
                + "，完成 " + r.rounds() + " 轮\n"
                + "已实现 " + signed(r.realized()) + " USDT；期末未卖仓位成本 " + money(r.endCost()) + "，浮动 "
                + signed(r.endUnrealized()) + "\n"
                + "最大浮亏 " + money(r.maxFloatLoss()) + " USDT（上限的 " + pct(r.maxFloatLossPct()) + "）\n"
                + "单轮最长持仓 " + r.maxHoldDays() + " 天，平均 " + r.avgHoldDays() + " 天\n"
                + "同期一直持有 BTC：" + pct(r.buyHoldPct());
    }

    static String hold(long minutes) {
        return minutes >= 1440 ? String.format(Locale.ROOT, "%.1f 天", minutes / 1440.0)
                : String.format(Locale.ROOT, "%.1f 小时", minutes / 60.0);
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
