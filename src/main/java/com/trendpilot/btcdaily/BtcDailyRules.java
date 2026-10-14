package com.trendpilot.btcdaily;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.trendpilot.market.Kline;

/**
 * BTC 每日短线规则（纯计算，实盘影子和历史回测共用同一套代码）。
 * <ul>
 * <li>计划：空仓时按今日开盘价向下挂三档；持仓跨天时只把没用完的档位从上次买入价继续往下挂。</li>
 * <li>成交：用已收盘 K 线的最低/最高价判断；同一根 K 线里先算已有持仓的止盈，止盈了就不再买（保守）。</li>
 * <li>不止损：亏损时不卖，跨天后把止盈幅度降到 carryTpPct，尽快回本离场。每天最多完成一轮。</li>
 * </ul>
 */
public final class BtcDailyRules {

    private BtcDailyRules() {
    }

    public static LocalDate dayOf(long epochMs) {
        return java.time.Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate();
    }

    public static long dayStart(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /** 计划需要的最少日线根数。 */
    public static int dailyNeeded(BtcDailyProperties p) {
        return Math.max(p.atrDays() + 1, Math.max(p.emaDays(), p.filters().emaLong()) + p.filters().slopeDays());
    }

    /** 不带市场 / 宏观过滤的计划（回测基准）。 */
    public static DailyPlan plan(LocalDate day, List<Kline> dailyBefore, double open, Book book, BtcDailyProperties p) {
        return plan(day, dailyBefore, open, book, p, null);
    }

    /**
     * @param dailyBefore 计划日之前已收盘的日线（升序），至少 {@link #dailyNeeded} 根
     * @param open        计划日日线开盘价
     * @param ctx         市场与宏观数据；null 表示不做过滤（回测基准）
     */
    public static DailyPlan plan(LocalDate day, List<Kline> dailyBefore, double open, Book book, BtcDailyProperties p,
                                 MarketContext ctx) {
        int need = dailyNeeded(p);
        if (dailyBefore.size() < need) {
            throw new IllegalArgumentException("日线不足：需要 " + need + " 根，只有 " + dailyBefore.size());
        }
        MarketContext.Gate gate = MarketContext.Gate.NONE;
        if (ctx != null && p.filters().enabled()) {
            gate = ctx.gate(downtrend(dailyBefore, p), p.filters());
        }
        double atr = atr(dailyBefore, p.atrDays());
        double ema = ema(dailyBefore, p.emaDays());
        double prevClose = dailyBefore.getLast().close();
        boolean strong = prevClose > ema;
        double tpPct = Math.max(p.tpAtrMult() * atr / open * 100, p.tpMinPct());

        List<DailyPlan.Level> levels = new ArrayList<>();
        List<Double> tranches = p.tranches();
        if (!book.holding()) {
            double first = open - (strong ? p.kStrong() : p.kWeak()) * atr;
            double spent = 0;
            for (int i = 0; i < tranches.size() && !gate.skipNew(); i++) {
                double usdt = round2(tranches.get(i) * gate.multiplier());
                if (spent + usdt > p.capUsdt() + 1e-6) break;
                spent += usdt;
                levels.add(new DailyPlan.Level(i, round2(first - i * p.stepAtr() * atr), usdt));
            }
        } else {
            double spent = book.cost;
            for (int i = book.buys, j = 1; i < tranches.size(); i++, j++) {
                if (spent + tranches.get(i) > p.capUsdt() + 1e-6) break;
                spent += tranches.get(i);
                levels.add(new DailyPlan.Level(i, round2(book.lastBuyPrice - j * p.stepAtr() * atr), tranches.get(i)));
            }
        }
        List<String> notes = new ArrayList<>(gate.reasons());
        if (ctx != null && !ctx.gaps().isEmpty()) notes.add("数据缺失：" + String.join("、", ctx.gaps()));
        return new DailyPlan(day, p.version(), open, atr, ema, prevClose, strong, book.holding(), tpPct,
                List.copyOf(levels), gate.skipNew(), gate.multiplier(), String.join("；", notes));
    }

    /** 下跌趋势：昨收 < EMA20 且 EMA20 < 长期 EMA 且 EMA20 比 slopeDays 天前低。 */
    static boolean downtrend(List<Kline> daily, BtcDailyProperties p) {
        int slope = p.filters().slopeDays();
        double ema = ema(daily, p.emaDays());
        double emaBefore = ema(daily.subList(0, daily.size() - slope), p.emaDays());
        double emaLong = ema(daily, p.filters().emaLong());
        return daily.getLast().close() < ema && ema < emaLong && ema < emaBefore;
    }

    /** 当前持仓的止盈价（含手续费的均价 × (1 + 幅度)）。 */
    public static double takeProfitPrice(DailyPlan plan, Book book, BtcDailyProperties p) {
        return book.avgCost() * (1 + takeProfitPct(plan, book, p) / 100);
    }

    public static double takeProfitPct(DailyPlan plan, Book book, BtcDailyProperties p) {
        boolean carried = book.openedDay != null && book.openedDay.isBefore(plan.day());
        return carried ? p.carryTpPct() : plan.tpPct();
    }

    /** 处理一根已收盘 K 线，返回成交（可能为空），并更新账本。 */
    public static List<Fill> onBar(DailyPlan plan, Book book, Kline bar, BtcDailyProperties p) {
        List<Fill> fills = new ArrayList<>();
        double slip = p.slippageBps() / 10_000;
        LocalDate day = plan.day();

        if (book.holding()) {
            double target = takeProfitPrice(plan, book, p);
            if (bar.high() >= target) {
                double price = Math.max(target, bar.open()) * (1 - slip);
                double gross = book.qty * price;
                double fee = gross * p.feeRate();
                double net = gross - fee;
                double pnl = net - book.cost;
                String reason = book.openedDay.isBefore(day) ? "跨天回本止盈" : "当日止盈";
                fills.add(new Fill(day, bar.openTime(), Fill.Side.SELL, book.buys, price, book.qty, net, fee, pnl, reason));
                book.realized += pnl;
                book.rounds++;
                if (pnl > 0) book.wins++;
                book.qty = 0;
                book.cost = 0;
                book.buys = 0;
                book.lastBuyPrice = 0;
                book.openedDay = null;
                book.roundMaxFloatLoss = 0;
                book.lastExitDay = day;
                return fills;
            }
        }
        boolean newRoundBlocked = !book.holding() && day.equals(book.lastExitDay);
        if (!newRoundBlocked) {
            for (DailyPlan.Level l : plan.levels()) {
                if (l.index() != book.buys || bar.low() > l.price()) continue;
                if (book.cost + l.usdt() > p.capUsdt() + 1e-6) break;
                double price = Math.min(l.price(), bar.open()) * (1 + slip);
                double fee = l.usdt() * p.feeRate() / (1 + p.feeRate());
                double qty = (l.usdt() - fee) / price;
                if (!book.holding()) book.openedDay = day;
                book.qty += qty;
                book.cost += l.usdt();
                book.buys++;
                book.lastBuyPrice = price;
                fills.add(new Fill(day, bar.openTime(), Fill.Side.BUY, l.index(), price, qty, l.usdt(), fee, 0,
                        l.index() == 0 ? "第 1 档买入" : "第 " + (l.index() + 1) + " 档加仓"));
            }
        }
        if (book.holding()) {
            double floating = book.unrealized(bar.low(), p.feeRate());
            book.roundMaxFloatLoss = Math.min(book.roundMaxFloatLoss, floating);
            book.maxFloatLoss = Math.min(book.maxFloatLoss, floating);
        }
        return fills;
    }

    /** 简单平均真实波幅（最近 n 根）。 */
    static double atr(List<Kline> daily, int n) {
        double sum = 0;
        for (int i = daily.size() - n; i < daily.size(); i++) {
            Kline k = daily.get(i);
            double prev = daily.get(i - 1).close();
            sum += Math.max(k.high() - k.low(), Math.max(Math.abs(k.high() - prev), Math.abs(k.low() - prev)));
        }
        return sum / n;
    }

    /** 收盘价 EMA（用全部给定日线递推）。 */
    static double ema(List<Kline> daily, int n) {
        double a = 2.0 / (n + 1);
        double e = daily.getFirst().close();
        for (int i = 1; i < daily.size(); i++) {
            e = a * daily.get(i).close() + (1 - a) * e;
        }
        return e;
    }

    static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
