package com.trendpilot.btcdaily;

import java.util.Locale;
import java.util.Map;

/** BTC 每日短线的 Telegram 文本。 */
final class BtcDailyMessages {

    private BtcDailyMessages() {
    }

    static String plan(DailyPlan p, Book b, Map<String, Object> yesterday, BtcDailyProperties props) {
        StringBuilder sb = new StringBuilder();
        sb.append("【BTC 每日短线·影子】").append(p.day()).append(" 计划（参数 ").append(p.version())
                .append(p.v3() ? "，策略 v3，行情" + DailyPlan.regimeName(p.regime()) : "").append("）\n");
        sb.append("开盘 ").append(money(p.open())).append("｜ATR14 ").append(money(p.atr()))
                .append("（").append(pct(p.atrPct())).append("）｜昨收")
                .append(p.strong() ? "高于" : "低于").append(" EMA20，").append(p.strong() ? "偏强" : "偏弱").append('\n');
        if (!p.reasons().isBlank()) {
            sb.append("\n市场 / 宏观过滤：\n");
            for (String r : p.reasons().split("；")) sb.append("  · ").append(r).append('\n');
        }
        sb.append("\n买入策略：\n");
        if (p.levels().isEmpty()) {
            sb.append(b.holding() && p.v3() ? "  已有持仓，不加仓、不开新一轮，等卖出\n"
                    : p.skipNew() && !b.holding() ? "  今天不开新一轮（见上面的原因）\n"
                    : b.trailPeak() > 0 && !props.exit().trailB() ? "  持仓正在跟踪止盈，今天不加仓\n"
                    : "  资金已用满，今天不再买入，等待止盈\n");
        } else if (p.multiplier() < 1 && !p.carried()) {
            sb.append("  仓位按过滤结果乘 ").append(p.multiplier()).append('\n');
        }
        for (DailyPlan.Level l : p.levels()) {
            sb.append("  第 ").append(l.index() + 1).append(" 档").append(l.index() == 0 ? "" : "（加仓）")
                    .append(" 跌到 ").append(money(l.price())).append(" 买 ").append(money(l.usdt())).append(" USDT\n");
        }
        if (p.v3()) {
            sellV3(sb, p, b, props);
            return tail(sb, b, yesterday, props);
        }
        if (props.exit().trailB()) {
            sellB(sb, p, b, props);
            return tail(sb, b, yesterday, props);
        }
        String mode = props.exit().mode();
        boolean partialMode = "PARTIAL".equals(mode);
        boolean trail = "TRAIL".equals(mode) || partialMode;
        sb.append(partialMode ? "\n卖出策略（先卖一半，剩下跟踪）：\n" : trail ? "\n卖出策略（回本后跟踪止盈）：\n" : "\n卖出策略：\n");
        if (b.holding()) {
            double tpPct = BtcDailyRules.takeProfitPct(p, b, props);
            sb.append("  持仓 ").append(qty(b.qty())).append(" BTC，成本 ").append(money(b.cost()))
                    .append("，均价 ").append(money(b.avgCost())).append("（").append(b.openedDay()).append(" 开始持有，已 ")
                    .append(java.time.temporal.ChronoUnit.DAYS.between(b.openedDay(), p.day())).append(" 天）\n");
            sb.append("  涨到 ").append(money(BtcDailyRules.takeProfitPrice(p, b, props))).append("（均价 +")
                    .append(pct(tpPct)).append("）").append(trail ? "后开始跟踪\n" : "全部卖出\n");
            sb.append("  若今天又加仓，止盈价按新均价重算\n");
        } else {
            sb.append("  买入后涨到 均价 +").append(pct(p.tpPct())).append(trail ? " 后开始跟踪\n" : " 全部卖出\n");
        }
        if (b.trailPeak() > 0) {
            sb.append("  正在跟踪：已到止盈价，最高 ").append(money(b.trailPeak())).append("，跟踪期间不加仓\n");
        }
        if (trail) {
            double atrMult = partialMode ? props.exit().partialTrailAtr() : props.exit().trailAtr();
            if (partialMode) sb.append("  到止盈价先卖 ").append(fmt(props.exit().partialPct())).append("% 锁定利润\n");
            sb.append("  ").append(partialMode ? "剩下的" : "").append("从最高价回落 ").append(fmt(atrMult)).append("×ATR（约 ")
                    .append(money(atrMult * p.atr())).append("）全部卖出，最低按止盈价卖，不会卖亏\n");
            sb.append("  不止损；当天没卖掉的仓位，次日起止盈价降为均价 +").append(pct(props.carryTpPct()))
                    .append("，回本后照样跟踪\n");
        } else {
            sb.append("  不止损；当天没卖掉的仓位，次日起目标降为均价 +").append(pct(props.carryTpPct())).append("，回本即走\n");
        }
        return tail(sb, b, yesterday, props);
    }

    /** 一行卖法说明（/status、日报用）。 */
    public static String sellRuleShort(BtcDailyProperties props) {
        BtcDailyProperties.Exit x = props.exit();
        if (x.trailB()) {
            return "方案 B：涨 " + fmt(x.activatePct()) + "% 后跟踪，回落 " + fmt(x.giveAtr()) + "×ATR 卖；"
                    + (x.timeoutDays() > 0 ? "满 " + x.timeoutDays() + " 天成本 +" + fmt(x.timeoutTargetPct()) + "% 以上卖；"
                    : "不设超时；") + "不卖亏";
        }
        return "卖法 " + x.mode() + "；不卖亏";
    }

    /** 方案 B 的卖出说明（激活价、跟踪线、30 天保底）。 */
    static void sellB(StringBuilder sb, DailyPlan p, Book b, BtcDailyProperties props) {
        BtcDailyProperties.Exit x = props.exit();
        sb.append("\n卖出策略（方案 B：涨 ").append(fmt(x.activatePct())).append("% 后跟踪，不卖亏）：\n");
        if (b.holding()) {
            long held = java.time.temporal.ChronoUnit.DAYS.between(b.openedDay(), p.day());
            sb.append("  持仓 ").append(qty(b.qty())).append(" BTC，成本 ").append(money(b.cost()))
                    .append("，均价 ").append(money(b.avgCost())).append("（").append(b.openedDay()).append(" 开始持有，已 ")
                    .append(held).append(" 天）\n");
            sb.append("  涨到 ").append(money(BtcDailyRules.activateB(b, props))).append("（均价 +").append(fmt(x.activatePct()))
                    .append("%）后开始跟踪，从最高价回落 ").append(fmt(x.giveAtr())).append("×ATR（约 ")
                    .append(money(x.giveAtr() * p.atr())).append("）全部卖出\n");
            double line = BtcDailyRules.trailLineB(b, p, props);
            if (!Double.isNaN(line)) {
                sb.append("  正在跟踪：最高 ").append(money(b.trailPeak())).append("，跌到 ").append(money(line)).append(" 卖出\n");
            }
            if (x.timeoutDays() > 0) {
                sb.append("  持有满 ").append(x.timeoutDays()).append(" 天还没卖：").append(money(BtcDailyRules.timeoutTargetB(b, props)))
                        .append("（含费成本 +").append(fmt(x.timeoutTargetPct())).append("%）以上就卖\n");
            }
            sb.append("  若加仓，均价、激活价和保底价按新均价重算\n");
        } else {
            sb.append("  买入后涨到均价 +").append(fmt(x.activatePct())).append("% 开始跟踪，从最高价回落 ").append(fmt(x.giveAtr()))
                    .append("×ATR（约 ").append(money(x.giveAtr() * p.atr())).append("）全部卖出\n");
            if (x.timeoutDays() > 0) {
                sb.append("  持有满 ").append(x.timeoutDays()).append(" 天还没卖：含费成本 +").append(fmt(x.timeoutTargetPct())).append("% 以上就卖\n");
            }
        }
        sb.append("  不止损、绝不卖亏；跌了按档位继续加仓\n");
    }

    private static void sellV3(StringBuilder sb, DailyPlan p, Book b, BtcDailyProperties props) {
        BtcDailyProperties.V3 v = props.v3();
        if (!p.levels().isEmpty() && p.levels().size() > 1) {
            sb.append("  加仓只在趋势还在时：档位须高于 ").append("UP".equals(p.regime()) ? "日线 EMA20 " : "日线 EMA50 ")
                    .append(money(p.addFloor())).append('\n');
        }
        sb.append("\n卖出策略（分批 + 跟踪，不止损）：\n");
        if (b.holding()) {
            sb.append("  持仓 ").append(qty(b.qty())).append(" BTC，成本 ").append(money(b.cost()))
                    .append("，均价 ").append(money(b.avgCost())).append("（").append(b.openedDay()).append(" 开始持有）\n");
            if (b.trailPeak() > 0) sb.append("  正在跟踪，最高 ").append(money(b.trailPeak())).append('\n');
        }
        sb.append("  涨到均价 +").append(fmt(v.partialAtPct())).append("% 先卖 1/3 锁定利润\n");
        sb.append("  剩下的从最高价回落 ").append(fmt(v.trailHourAtr())).append("×1h ATR（约 ")
                .append(money(v.trailHourAtr() * p.hourAtr())).append("）再卖，只在均价 +").append(fmt(v.floorPct()))
                .append("% 以上卖，不卖亏\n");
        sb.append("  不强制当天平仓；被套时不加仓、不开新一轮，回本后同样跟踪\n");
    }

    private static String tail(StringBuilder sb, Book b, Map<String, Object> yesterday, BtcDailyProperties props) {
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
            // 用这笔成交之后的持仓，而不是整批成交处理完之后的账本
            boolean carried = b.openedDay() != null && b.openedDay().isBefore(p.day());
            double tpPct = p.v3() ? props.v3().partialAtPct() : props.exit().trailB() ? props.exit().activatePct()
                    : carried ? props.carryTpPct() : p.tpPct();
            sb.append("持仓：成本 ").append(money(f.posCost())).append("，均价 ").append(money(f.posAvg()))
                    .append(p.v3() ? "，先卖 1/3 价 " : "FIXED".equals(props.exit().mode()) || "STOP".equals(props.exit().mode()) ? "，止盈价 " : "，开始跟踪价 ")
                    .append(money(f.posAvg() * (1 + tpPct / 100)));
        } else {
            double cost = f.usdt() - f.pnl();
            boolean partial = f.posQty() > 0;
            sb.append(partial ? "部分卖出（" : "卖出（").append(f.reason()).append("）\n");
            sb.append("价格 ").append(money(f.price())).append("｜数量 ").append(qty(f.qty())).append(" BTC｜到手 ")
                    .append(money(f.usdt())).append(" USDT（手续费 ").append(money(f.fee())).append("）\n");
            if (partial) {
                sb.append("这部分盈亏 ").append(signed(f.pnl())).append(" USDT（").append(pct(f.pnl() / cost * 100))
                        .append("）\n剩余 ").append(qty(f.posQty())).append(" BTC 继续跟踪最高价，回落 ")
                        .append(p.v3() ? fmt(props.v3().trailHourAtr()) + "×1h ATR" : fmt(props.exit().partialTrailAtr()) + "×ATR")
                        .append(" 再卖，不卖亏\n");
            } else if (round != null && round.cost() > cost + 0.01) {
                sb.append("这次卖出 ").append(signed(f.pnl())).append(" USDT；本轮合计盈亏 ").append(signed(round.pnl()))
                        .append(" USDT（").append(pct(round.pnlPct())).append("），买入 ").append(f.level()).append(" 次\n");
            } else {
                sb.append("本轮盈亏 ").append(signed(f.pnl())).append(" USDT（").append(pct(f.pnl() / cost * 100))
                        .append("），买入 ").append(f.level()).append(" 次\n");
            }
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

    static String exits(Map<String, BtcDailyBacktest.Result> exits, BtcDailyProperties props, String live) {
        BtcDailyProperties.Exit x = props.exit();
        Map<String, String> names = Map.of(
                "FIXED", "现行：跨天 +" + fmt(props.carryTpPct()) + "% 就卖、不止损",
                "TRAIL", "回本后跟踪：到了止盈价不立即卖，跟踪最高价，回落 " + fmt(x.trailAtr()) + "×ATR 卖（最低按原止盈价）、不止损",
                "PARTIAL", "先卖一半、剩下跟踪：到止盈价卖 " + fmt(x.partialPct()) + "%，剩下回落 " + fmt(x.partialTrailAtr())
                        + "×ATR 卖（最低按原止盈价，可跨天）、不止损",
                "STOP", "现行 + 跌 " + fmt(x.hardStopPct()) + "% 止损",
                "TRAIL_B", sellRuleShort(props));
        StringBuilder sb = new StringBuilder("【BTC 每日短线】卖出方式回测对比（近一年，同一套买入和过滤规则）\n");
        BtcDailyBacktest.Result any = exits.values().iterator().next();
        sb.append("区间 ").append(any.from()).append(" ~ ").append(any.to()).append("，同期持有 BTC ").append(pct(any.buyHoldPct())).append('\n');
        for (var e : exits.entrySet()) {
            BtcDailyBacktest.Result r = e.getValue();
            sb.append("\n■ ").append(names.getOrDefault(e.getKey(), e.getKey())).append('\n')
                    .append("年化 ").append(pct(r.annualPct())).append("（总盈亏 ").append(signed(r.totalPnl())).append(" USDT，含期末浮动）\n")
                    .append("最大回撤 ").append(money(r.maxDrawdown())).append(" USDT（").append(pct(r.maxDrawdownPct())).append("）")
                    .append("｜最长被套 ").append(r.maxHoldDays()).append(" 天\n")
                    .append("完成 ").append(r.rounds()).append(" 轮，胜率 ").append(pct(r.winRate()))
                    .append("｜平均赚 ").append(signed(r.avgWin())).append("，平均亏 ").append(signed(r.avgLoss()))
                    .append("，盈亏比 ").append(r.winLossRatio() == 0 ? "—（没有亏损的轮）" : String.valueOf(r.winLossRatio())).append('\n')
                    .append("大涨轮次（持仓期间最高涨 ≥").append(fmt(BtcDailyBacktest.BIG_MOVE_PCT)).append("%）").append(r.bigRounds())
                    .append(" 轮，平均吃到涨幅的 ").append(pct(r.captureRatio())).append('\n');
        }
        String wanted = x.mode();
        if ("FIXED".equals(wanted)) {
            sb.append("\n实盘按现行规则。");
        } else if (!"FIXED".equals(live)) {
            sb.append("\n✅ 选定「").append(names.getOrDefault(live, live)).append("」：不比现行差（总盈亏不低、最大回撤不超 1.1 倍、")
                    .append("最长被套不多 2 天）里总盈亏最高，实盘从现在起改用它（参数 ").append(props.version())
                    .append("）。回滚：配置 exit.mode 改回 FIXED。");
        } else {
            sb.append("\n⚠️ 跟踪类卖法回测都不如现行（总盈亏、最大回撤或最长被套更差），实盘暂不切换，仍按现行规则，等你决定。");
        }
        return sb.toString();
    }

    static String strategies(Map<String, BtcDailyBacktest.Result> runs, BtcDailyProperties props, String live) {
        Map<String, String> names = Map.of(
                "V2", "现行 V2：开盘下方三档，到止盈价全卖（跨天 +" + fmt(props.carryTpPct()) + "%）",
                "V3+TREND", "V3 + 趋势转跌就卖（行情变成下跌当天开盘卖）",
                "V3+TIME", "V3 + 持有满 " + props.v3().guardDays() + " 天就卖",
                "V3+STOP", "V3 + 跌 " + fmt(props.v3().guardStopPct()) + "% 止损",
                "V2-B", "实盘买法 + " + sellRuleShort(props),
                "V3", "新 V3：按行情挂单（上升满额 / 震荡深位减半 / 下跌不做），涨 " + fmt(props.v3().partialAtPct())
                        + "% 卖 1/3，剩下跟踪 " + fmt(props.v3().trailHourAtr()) + "×1h ATR");
        StringBuilder sb = new StringBuilder("【BTC 每日短线】策略回测对比（参数 " + props.version() + "，近一年，同一套过滤）\n");
        BtcDailyBacktest.Result any = runs.values().iterator().next();
        sb.append("区间 ").append(any.from()).append(" ~ ").append(any.to()).append("，同期持有 BTC ").append(pct(any.buyHoldPct())).append('\n');
        BtcDailyBacktest.Result v3 = runs.get("V3");
        if (v3 != null && v3.regimeDays() != null) {
            sb.append("行情天数：");
            v3.regimeDays().forEach((k, n) -> sb.append(DailyPlan.regimeName(k)).append(' ').append(n).append(" 天 "));
            sb.append('\n');
        }
        for (var e : runs.entrySet()) {
            BtcDailyBacktest.Result r = e.getValue();
            sb.append("\n■ ").append(names.getOrDefault(e.getKey(), e.getKey())).append('\n')
                    .append("年化 ").append(pct(r.annualPct())).append("（总盈亏 ").append(signed(r.totalPnl())).append(" USDT，含期末浮动）\n")
                    .append("最大回撤 ").append(money(r.maxDrawdown())).append("（").append(pct(r.maxDrawdownPct())).append("）")
                    .append("｜最长被套 ").append(r.maxHoldDays()).append(" 天\n")
                    .append("完成 ").append(r.rounds()).append(" 轮，胜率 ").append(pct(r.winRate()))
                    .append("｜平均赚 ").append(signed(r.avgWin())).append("，平均亏 ").append(signed(r.avgLoss())).append('\n')
                    .append("大涨轮（持仓期间最高涨 ≥").append(fmt(BtcDailyBacktest.BIG_MOVE_PCT)).append("%）").append(r.bigRounds())
                    .append(" 轮，吃到涨幅的 ").append(pct(r.captureRatio())).append('\n');
            if (r.byRegime() != null && !r.byRegime().isEmpty()) {
                sb.append("按开仓行情：");
                r.byRegime().forEach((k, st) -> {
                    boolean open = k.endsWith("_OPEN");
                    String rg = DailyPlan.regimeName(open ? k.substring(0, k.length() - 5) : k);
                    sb.append("\n  ").append(rg).append(open ? "（期末未卖）浮动 " + signed(st.pnl()) + "，已拿 "
                            + fmt(st.maxHoldDays()) + " 天" : " " + st.rounds() + " 轮，盈亏 " + signed(st.pnl()) + "，胜率 "
                            + pct(st.winRate()) + "，最长 " + fmt(st.maxHoldDays()) + " 天，最大浮亏 " + pct(st.worstMaePct()));
                });
                sb.append('\n');
            }
        }
        sb.append("\n说明：带 + 的三种是被套保护（都会亏着卖），只做回测对比，实盘仍不止损，等你决定。\n");
        if ("V3".equals(live)) {
            sb.append("\n✅ V3 不比现行差（完成至少 10 轮、总盈亏不低、最大回撤不超 1.1 倍、最长被套不多 2 天），影子实盘从下一个计划起改用 V3。回滚：配置 strategy 改为 V2。");
        } else if ("AUTO".equals(props.strategy())) {
            sb.append("\n⚠️ V3 没通过上线条件（完成不到 10 轮，或总盈亏、最大回撤、最长被套更差），影子实盘仍按 V2，等你决定。");
        } else {
            sb.append("\n影子实盘按配置的策略 ").append(live).append('。');
        }
        if (props.exit().trailB()) {
            sb.append("\n卖法按你 2026-10-20 选定的方案 B（上面 V2-B 一行）；近一年对照只作参考，依据是 2020 年起的完整研究。回滚：exit.mode 改回 FIXED 并改版本号。");
        }
        return sb.toString();
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
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
