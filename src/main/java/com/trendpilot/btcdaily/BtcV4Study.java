package com.trendpilot.btcdaily;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

import com.trendpilot.market.Kline;

/**
 * 研究用：BTC 每日短线 V4 候选——按 ATR 阶梯挂单、跨天加仓拉低均价、不止损（只在均价以上卖）。
 * <ul>
 * <li>空仓时每天开盘（越南 07:00 = UTC 00:00）挂第一单：开盘 − a×ATR14，只当天有效；a = 0 即开盘价买。</li>
 * <li>成交后，下一档 = 上一笔成交价 − s×ATR（ATR 每天更新），跨天一直有效，直到档数用完。</li>
 * <li>卖出：价格 ≥ 含费均价 × (1 + t)；t = max(0.5×ATR%, 0.8%) 或固定 0.6%；第一笔成交满 D 天后 t 降为 0.3%（仍不卖亏）。</li>
 * <li>同一根 15m K 线先买后卖（保守：当根买入不在当根卖出）；买入成交价 = min(挂单价, 开盘价)，卖出 = max(目标价, 开盘价)，再计滑点和手续费。</li>
 * <li>过滤只影响开新一轮：下跌趋势 + 已知议息日（与线上同一个 {@link MarketContext#gate}）。每天最多开一轮。</li>
 * </ul>
 */
public final class BtcV4Study {

    private BtcV4Study() {
    }

    /** 候选参数。tpMode: "ATR" = max(0.5×ATR%, 0.8%)，"FIX" = 0.6%。 */
    public record Params(double firstAtr, double stepAtr, double[] tranches, String tpMode, int lowerAfterDays,
                         boolean filters) {
        public String label() {
            StringBuilder t = new StringBuilder();
            for (double d : tranches) t.append(t.isEmpty() ? "" : "/").append((int) d);
            return String.format(Locale.ROOT, "首单%.2f 档距%.2f %d档(%s) 止盈%s %d天降 %s", firstAtr, stepAtr,
                    tranches.length, t, "ATR".equals(tpMode) ? "max(0.5ATR,0.8%)" : "0.6%", lowerAfterDays,
                    filters ? "过滤开" : "过滤关");
        }
    }

    /** 一次回放的结果。winMonths：有盈利平仓的月份。 */
    public record Run(String name, int rounds, int wins, double realized, double endUnrealized, double total,
                      double maxDd, double maxFloatLoss, long maxHoldDays, int days, int skipped, int heldDays,
                      double[] roundPnls, Map<String, double[]> cat, Set<YearMonth> winMonths, Map<Integer, double[]> byYear) {
    }

    private static final class Pos {
        double qty, cost; // cost 含买入手续费
        int filled;
        double lastFill;
        long openedAt;
        String cat;
        double minPrice = Double.MAX_VALUE;
    }

    public static Run run(String name, List<Kline> daily, List<Kline> bars, LocalDate from, Params v, BtcDailyProperties p,
                          Function<LocalDate, MarketContext> ctx, Predicate<LocalDate> allowNew) {
        Map<LocalDate, Integer> dayIndex = new LinkedHashMap<>();
        for (int i = 0; i < daily.size(); i++) dayIndex.put(BtcDailyRules.dayOf(daily.get(i).openTime()), i);
        Set<LocalDate> crash = new HashSet<>();
        for (int i = 3; i < daily.size(); i++) {
            double d1 = daily.get(i).close() / daily.get(i - 1).close() - 1;
            double d3 = daily.get(i).close() / daily.get(i - 3).close() - 1;
            if (d1 <= -0.07 || d3 <= -0.10) crash.add(BtcDailyRules.dayOf(daily.get(i).openTime()));
        }
        double fee = p.feeRate(), slip = p.slippageBps() / 10_000.0;
        Pos pos = null;
        LocalDate cur = null;
        double atr = 0, open = 0, firstPrice = 0;
        boolean newAllowed = false;
        String dayCat = "";
        int rounds = 0, wins = 0, days = 0, skipped = 0, heldDays = 0;
        double realized = 0, peak = 0, maxDd = 0, maxFloat = 0, lastClose = 0;
        long maxHold = 0;
        List<Double> pnls = new ArrayList<>();
        Map<String, double[]> cat = new LinkedHashMap<>();
        for (String c : BtcGridStudy.CATS) cat.put(c, new double[3]);
        Set<YearMonth> winMonths = new TreeSet<>();
        Map<Integer, double[]> byYear = new LinkedHashMap<>();
        for (Kline bar : bars) {
            LocalDate day = BtcDailyRules.dayOf(bar.openTime());
            if (day.isBefore(from)) continue;
            if (!day.equals(cur)) {
                cur = day;
                Integer idx = dayIndex.get(day);
                if (idx == null || idx < BtcGridStudy.DAILY_WINDOW) {
                    newAllowed = false;
                    atr = 0;
                    continue;
                }
                List<Kline> before = daily.subList(idx - BtcGridStudy.DAILY_WINDOW, idx);
                atr = BtcDailyRules.atr(before, p.atrDays());
                open = daily.get(idx).open();
                String regime = BtcDailyRules.regime(before, p);
                dayCat = BtcGridStudy.category(day, crash, regime);
                days++;
                if (pos != null) {
                    heldDays++;
                    newAllowed = false;
                } else {
                    boolean skip = false;
                    if (v.filters() && ctx != null) {
                        MarketContext c = ctx.apply(day);
                        skip = c.gate(BtcDailyRules.downtrend(before, p), p.filters()).skipNew();
                    }
                    if (skip) skipped++;
                    newAllowed = !skip && allowNew.test(day);
                    firstPrice = open - v.firstAtr() * atr;
                }
            }
            if (atr <= 0) continue;
            boolean boughtThisBar = false;
            // 买：第一单（当天有效）或加仓档（跨天有效）
            if (pos == null && newAllowed && bar.low() <= firstPrice) {
                double px = Math.min(firstPrice, bar.open()) * (1 + slip);
                pos = new Pos();
                buy(pos, px, v.tranches()[0], fee);
                pos.openedAt = bar.openTime();
                pos.cat = dayCat;
                newAllowed = false;
                boughtThisBar = true;
            }
            while (pos != null && pos.filled < v.tranches().length) {
                double next = pos.lastFill - v.stepAtr() * atr;
                if (bar.low() > next) break;
                double px = Math.min(next, bar.open()) * (1 + slip);
                buy(pos, px, v.tranches()[pos.filled], fee);
                boughtThisBar = true;
            }
            if (pos != null) pos.minPrice = Math.min(pos.minPrice, bar.low());
            // 卖：只在均价以上
            if (pos != null && !boughtThisBar) {
                double avg = pos.cost / pos.qty;
                double t = "ATR".equals(v.tpMode()) ? Math.max(0.5 * atr / open, 0.008) : 0.006;
                if (bar.openTime() - pos.openedAt >= v.lowerAfterDays() * 86_400_000L) t = 0.003;
                double target = avg * (1 + t);
                if (bar.high() >= target) {
                    double px = Math.max(target, bar.open()) * (1 - slip);
                    double proceeds = pos.qty * px * (1 - fee);
                    double pnl = proceeds - pos.cost;
                    realized += pnl;
                    rounds++;
                    if (pnl > 0) {
                        wins++;
                        winMonths.add(YearMonth.from(day));
                    }
                    pnls.add(pnl);
                    double[] c = cat.get(pos.cat);
                    c[0]++;
                    c[1] += pnl;
                    c[2] = Math.min(c[2], (pos.minPrice / avg - 1) * 100);
                    double[] y = byYear.computeIfAbsent(day.getYear(), k -> new double[2]);
                    y[0]++;
                    y[1] += pnl;
                    maxHold = Math.max(maxHold, (bar.closeTime() + 1 - pos.openedAt) / 86_400_000L);
                    pos = null;
                }
            }
            double uLow = 0, uClose = 0;
            if (pos != null) {
                uLow = pos.qty * bar.low() * (1 - fee) - pos.cost;
                uClose = pos.qty * bar.close() * (1 - fee) - pos.cost;
                maxFloat = Math.min(maxFloat, uLow);
                maxHold = Math.max(maxHold, (bar.closeTime() + 1 - pos.openedAt) / 86_400_000L);
            }
            maxDd = Math.max(maxDd, peak - (realized + uLow));
            peak = Math.max(peak, realized + uClose);
            lastClose = bar.close();
        }
        double endU = pos == null ? 0 : pos.qty * lastClose * (1 - fee) - pos.cost;
        if (pos != null) {
            double[] c = cat.get(pos.cat);
            c[2] = Math.min(c[2], (pos.minPrice / (pos.cost / pos.qty) - 1) * 100);
        }
        return new Run(name, rounds, wins, realized, endU, realized + endU, maxDd, maxFloat, maxHold, days, skipped,
                heldDays, pnls.stream().mapToDouble(Double::doubleValue).toArray(), cat, winMonths, byYear);
    }

    private static void buy(Pos pos, double px, double usdt, double fee) {
        pos.qty += usdt * (1 - fee) / px;
        pos.cost += usdt;
        pos.filled++;
        pos.lastFill = px;
    }

    /** 区间内（含首尾月）有盈利平仓的月份占比。 */
    public static double monthCoverage(Set<YearMonth> winMonths, LocalDate from, LocalDate to) {
        int n = 0, hit = 0;
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            n++;
            if (winMonths.contains(m)) hit++;
        }
        return n == 0 ? 0 : 100.0 * hit / n;
    }
}
