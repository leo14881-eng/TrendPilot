package com.trendpilot.btcdaily;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import com.trendpilot.market.Kline;

/**
 * 研究用（不改线上规则）：BTC 每日短线挂单深度网格。直接调用线上同一套 {@link BtcDailyRules#plan} / {@link BtcDailyRules#onBar}，
 * 计划日用前 119 根日线（与线上取 120 根日线一致），15m K 线撮合，不止损。
 * <p>
 * 过滤"开"：下跌趋势 + 已知 FOMC 日（其余宏观数据历史上没有，按回测口径视为不触发）；"关"：不过滤。
 * 行情分类按开仓日（之前已收盘日线）：暴跌（前 1 日 ≤ −7% 或前 3 日 ≤ −10%）、暴跌后反弹（暴跌后 10 天内）、
 * 其余按线上 regime：UP 上涨 / SIDE 震荡 / DOWN 下跌。
 */
public final class BtcGridStudy {

    private BtcGridStudy() {
    }

    static final int DAILY_WINDOW = 119;
    static final String[] CATS = {"上涨", "震荡", "下跌", "暴跌", "暴跌后反弹"};

    /** 一次回放的结果。 */
    public record Run(String name, int rounds, int wins, double realized, double endUnrealized, double total,
                      double maxDd, double maxFloatLoss, long maxHoldDays, int tradeDays, int days, int skipped, int heldDays,
                      double[] roundPnls, Map<String, double[]> cat, Map<Integer, double[]> byYear,
                      java.util.Set<java.time.YearMonth> winMonths, double[] roundPcts, EqTracker eq) {

        double meanRound() {
            return roundPnls.length == 0 ? 0 : Arrays.stream(roundPnls).average().orElse(0);
        }
    }

    /**
     * @param daily 全部日线（升序，UTC 日）
     * @param bars  15m K 线（升序）
     * @param from  回测起始日（含）
     * @param ctx   每日过滤上下文，null = 不过滤
     */
    public static Run run(String name, List<Kline> daily, List<Kline> bars, LocalDate from, BtcDailyProperties p,
                          Function<LocalDate, MarketContext> ctx) {
        return run(name, daily, bars, from, p, ctx, d -> true);
    }

    /** @param allowNew 某天是否允许开新一轮（随机基准用；已有仓位的加仓和止盈不受影响） */
    public static Run run(String name, List<Kline> daily, List<Kline> bars, LocalDate from, BtcDailyProperties p,
                          Function<LocalDate, MarketContext> ctx, java.util.function.Predicate<LocalDate> allowNew) {
        Map<LocalDate, Integer> dayIndex = new LinkedHashMap<>();
        for (int i = 0; i < daily.size(); i++) dayIndex.put(BtcDailyRules.dayOf(daily.get(i).openTime()), i);
        Set<LocalDate> crash = new HashSet<>();
        for (int i = 3; i < daily.size(); i++) {
            double d1 = daily.get(i).close() / daily.get(i - 1).close() - 1;
            double d3 = daily.get(i).close() / daily.get(i - 3).close() - 1;
            if (d1 <= -0.07 || d3 <= -0.10) crash.add(BtcDailyRules.dayOf(daily.get(i).openTime()));
        }
        Book book = new Book();
        EqTracker eq = new EqTracker(p.capUsdt());
        DailyPlan plan = null;
        String planCat = "";
        List<Double> pnls = new ArrayList<>();
        List<Double> pcts = new ArrayList<>();
        Map<String, double[]> cat = new LinkedHashMap<>(); // {轮数, 盈亏, 最差 MAE%}
        for (String c : CATS) cat.put(c, new double[3]);
        Map<Integer, double[]> byYear = new LinkedHashMap<>(); // {轮数, 已实现}
        java.util.Set<java.time.YearMonth> winMonths = new java.util.TreeSet<>();
        String openCat = "";
        int days = 0, tradeDays = 0, skipped = 0, heldDays = 0;
        LocalDate lastBuyDay = null, lastDay = null;
        long maxHold = 0;
        double peak = 0, maxDd = 0, lastClose = 0;
        for (Kline bar : bars) {
            LocalDate day = BtcDailyRules.dayOf(bar.openTime());
            if (day.isBefore(from)) continue;
            if (plan == null || !plan.day().equals(day)) {
                Integer idx = dayIndex.get(day);
                if (idx == null || idx < DAILY_WINDOW) {
                    plan = null;
                    continue;
                }
                List<Kline> before = daily.subList(idx - DAILY_WINDOW, idx);
                MarketContext c = ctx == null ? null : ctx.apply(day);
                plan = BtcDailyRules.plan(day, before, daily.get(idx).open(), book, p, c);
                if (!book.holding() && !allowNew.test(day)) {
                    plan = new DailyPlan(plan.day(), plan.version(), plan.open(), plan.atr(), plan.ema(), plan.prevClose(),
                            plan.strong(), plan.carried(), plan.tpPct(), List.of(), true, 0, "随机未选中", plan.strategy(),
                            plan.regime(), plan.hourAtr(), plan.addFloor());
                }
                if (plan.skipNew()) skipped++;
                if (book.holding()) heldDays++;
                planCat = category(day, crash, plan.regime());
                days++;
            }
            for (Fill f : BtcDailyRules.onBar(plan, book, bar, p)) {
                if (f.side() == Fill.Side.BUY) {
                    if (!day.equals(lastBuyDay)) tradeDays++;
                    lastBuyDay = day;
                    if (f.level() == 0) openCat = planCat;
                } else if (book.lastRound != null) {
                    Book.Round r = book.lastRound;
                    pnls.add(r.pnl());
                    pcts.add(r.pnlPct());
                    if (r.pnl() > 0) winMonths.add(java.time.YearMonth.from(day));
                    double[] c = cat.get(openCat);
                    c[0]++;
                    c[1] += r.pnl();
                    c[2] = Math.min(c[2], r.maePct());
                    double[] y = byYear.computeIfAbsent(day.getYear(), k -> new double[2]);
                    y[0]++;
                    y[1] += r.pnl();
                    maxHold = Math.max(maxHold, r.holdMinutes() / 1440);
                }
            }
            if (book.holding()) {
                maxHold = Math.max(maxHold, (bar.closeTime() + 1 - book.openedAt()) / 86_400_000L);
            }
            double eqLow = book.realized() + book.unrealized(bar.low(), p.feeRate());
            maxDd = Math.max(maxDd, peak - eqLow);
            peak = Math.max(peak, book.realized() + book.unrealized(bar.close(), p.feeRate()));
            lastClose = bar.close();
            lastDay = day;
            eq.on(day, bar.open(), p.capUsdt() + book.realized() + book.unrealized(bar.close(), p.feeRate()), bar.close());
        }
        double endU = book.unrealized(lastClose, p.feeRate());
        if (book.holding()) {
            double[] c = cat.get(openCat);
            c[2] = Math.min(c[2], book.maePct());
        }
        return new Run(name, book.rounds(), book.wins(), book.realized(), endU, book.realized() + endU, maxDd,
                book.maxFloatLoss(), maxHold, tradeDays, days, skipped, heldDays,
                pnls.stream().mapToDouble(Double::doubleValue).toArray(), cat, byYear, winMonths, pcts.stream().mapToDouble(Double::doubleValue).toArray(), eq);
    }

    static String category(LocalDate day, Set<LocalDate> crash, String regime) {
        LocalDate prev = day.minusDays(1);
        if (crash.contains(prev)) return "暴跌";
        for (int i = 2; i <= 11; i++) if (crash.contains(day.minusDays(i))) return "暴跌后反弹";
        return switch (regime) {
            case "UP" -> "上涨";
            case "DOWN" -> "下跌";
            default -> "震荡";
        };
    }

    public static BtcDailyProperties params(BtcDailyProperties base, double kStrong, double kWeak, double step,
                                            double tpAtr, boolean filters) {
        BtcDailyProperties.Filters f = base.filters();
        BtcDailyProperties.Filters nf = new BtcDailyProperties.Filters(filters, f.emaLong(), f.slopeDays(),
                f.fundingMaxPct(), f.fearGreedMax(), f.etfOutflowDayM(), f.etfOutflow3dM(), f.us10yRisePp(),
                f.dollarRisePct(), f.minMultiplier(), f.fomcDates(), f.cpiDates(), "", null, null, null, null);
        return new BtcDailyProperties(true, String.format(Locale.ROOT, "grid-ks%.2f-kw%.2f-st%.2f-tp%.2f-%s",
                kStrong, kWeak, step, tpAtr, filters ? "F" : "N"), base.symbol(), base.capUsdt(), base.capUntil(),
                base.tranches(), kStrong, kWeak, step, tpAtr, base.tpMinPct(), base.carryTpPct(), base.feeRate(),
                base.slippageBps(), base.atrDays(), base.emaDays(), base.backtestDays(), nf, base.stopCandidatesPct(),
                base.exit().withMode("FIXED"), "V2", base.v3(), 0);
    }

    /** 过滤上下文：已知 FOMC 日为事件日；其余宏观数据历史上缺失，按回测口径不触发。 */
    public static Function<LocalDate, MarketContext> filterContext(BtcDailyProperties p) {
        Set<String> fomc = new HashSet<>(p.filters().fomcDates());
        return d -> fomc.contains(d.toString()) ? withEvent(d, "议息") : MarketContext.empty(d);
    }

    static MarketContext withEvent(LocalDate d, String event) {
        return new MarketContext(d, event, null, null, null, null, null, null, null, null, List.of());
    }

    /** 随机基准：每天以概率 q 允许开新一轮（与过滤开关无关；过滤开时照样叠加同样的过滤）。 */
    public static java.util.function.Predicate<LocalDate> randomDays(double q, long seed) {
        Map<LocalDate, Boolean> pick = new LinkedHashMap<>();
        Random rnd = new Random(seed);
        return d -> pick.computeIfAbsent(d, k -> rnd.nextDouble() < q);
    }

    /** 第 1 档价格（开盘 − k×ATR）被当天最低价触及的天数比例，按强弱分开（与计划同口径）。 */
    public static double[] touchRate(List<Kline> daily, LocalDate from, double k, BtcDailyProperties p) {
        int touch = 0, n = 0, touchS = 0, nS = 0, touchW = 0, nW = 0;
        for (int i = DAILY_WINDOW; i < daily.size(); i++) {
            Kline d = daily.get(i);
            if (BtcDailyRules.dayOf(d.openTime()).isBefore(from)) continue;
            List<Kline> before = daily.subList(i - DAILY_WINDOW, i);
            double atr = BtcDailyRules.atr(before, p.atrDays());
            boolean strong = before.getLast().close() > BtcDailyRules.ema(before, p.emaDays());
            boolean hit = d.low() <= d.open() - k * atr;
            n++;
            if (hit) touch++;
            if (strong) {
                nS++;
                if (hit) touchS++;
            } else {
                nW++;
                if (hit) touchW++;
            }
        }
        return new double[]{100.0 * touch / n, nS == 0 ? 0 : 100.0 * touchS / nS, nW == 0 ? 0 : 100.0 * touchW / nW};
    }

    static long epochMs(LocalDate d) {
        return d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }
}
