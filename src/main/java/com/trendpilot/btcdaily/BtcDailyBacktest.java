package com.trendpilot.btcdaily;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Function;

import com.trendpilot.market.Kline;

/**
 * 用历史 K 线按同一套 {@link BtcDailyRules} 回放，回答"不止损最坏会被套多深、多久"。
 */
public final class BtcDailyBacktest {

    private BtcDailyBacktest() {
    }

    /**
     * @param rounds         完成的轮数（止盈卖出次数）
     * @param realized       已实现盈亏（USDT）
     * @param maxFloatLoss   最大浮亏（USDT，负数）
     * @param maxFloatLossPct 最大浮亏占上限的百分比
     * @param maxHoldDays    单轮最长持仓天数（含期末未卖的）
     * @param avgHoldDays    已完成轮的平均持仓天数
     * @param endUnrealized  期末未卖仓位的浮动盈亏
     * @param endCost        期末未卖仓位的成本
     * @param tradeDays      有买入的天数
     * @param buyHoldPct     同期一直持有 BTC 的涨跌幅
     * @param skippedDays    过滤导致不开新一轮的天数
     * @param reducedDays    过滤导致减仓的天数
     */
    public record Result(String version, LocalDate from, LocalDate to, int days, int rounds, double realized,
                         double maxFloatLoss, double maxFloatLossPct, long maxHoldDays, double avgHoldDays,
                         double endUnrealized, double endCost, int tradeDays, double buyHoldPct, int skippedDays,
                         int reducedDays) {
    }

    /**
     * @param daily 日线（升序），需覆盖回测区间及之前至少 max(atrDays + 1, emaDays) 根
     * @param bars  回测区间内的已收盘 K 线（升序，1h 或 15m）
     * @param ctx   每天的市场 / 宏观数据；null 表示不做过滤（基准）
     */
    public static Result run(List<Kline> daily, List<Kline> bars, BtcDailyProperties p,
                             Function<LocalDate, MarketContext> ctx) {
        Book book = new Book();
        int need = BtcDailyRules.dailyNeeded(p);
        int skipped = 0, reduced = 0;
        DailyPlan plan = null;
        LocalDate from = null, last = null, lastBuyDay = null;
        int days = 0, tradeDays = 0;
        long maxHold = 0, holdSum = 0;
        double firstOpen = 0, lastClose = 0;
        for (Kline bar : bars) {
            LocalDate day = BtcDailyRules.dayOf(bar.openTime());
            if (plan == null || !plan.day().equals(day)) {
                long start = BtcDailyRules.dayStart(day);
                List<Kline> before = daily.stream().filter(k -> k.openTime() < start).toList();
                Kline today = daily.stream().filter(k -> k.openTime() == start).findFirst().orElse(null);
                if (before.size() < need || today == null) {
                    plan = null;
                    continue;
                }
                plan = BtcDailyRules.plan(day, before, today.open(), book, p, ctx == null ? null : ctx.apply(day));
                if (plan.skipNew()) skipped++;
                else if (plan.multiplier() < 1) reduced++;
                if (from == null) {
                    from = day;
                    firstOpen = today.open();
                }
                days++;
            }
            LocalDate opened = book.openedDay();
            for (Fill f : BtcDailyRules.onBar(plan, book, bar, p)) {
                if (f.side() == Fill.Side.BUY && !day.equals(lastBuyDay)) {
                    tradeDays++;
                    lastBuyDay = day;
                } else if (f.side() == Fill.Side.SELL) {
                    long hold = ChronoUnit.DAYS.between(opened, day);
                    holdSum += hold;
                    maxHold = Math.max(maxHold, hold);
                }
            }
            if (book.holding()) {
                maxHold = Math.max(maxHold, ChronoUnit.DAYS.between(book.openedDay(), day));
            }
            last = day;
            lastClose = bar.close();
        }
        double avgHold = book.rounds() == 0 ? 0 : (double) holdSum / book.rounds();
        return new Result(p.version(), from, last, days, book.rounds(), round2(book.realized()),
                round2(book.maxFloatLoss()), round2(book.maxFloatLoss() / p.capUsdt() * 100), maxHold,
                round2(avgHold), round2(book.unrealized(lastClose, p.feeRate())), round2(book.cost()), tradeDays,
                firstOpen > 0 ? round2((lastClose / firstOpen - 1) * 100) : 0, skipped, reduced);
    }

    private static double round2(double v) {
        return BtcDailyRules.round2(v);
    }
}
