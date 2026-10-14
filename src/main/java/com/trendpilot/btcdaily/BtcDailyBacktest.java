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
                         int reducedDays, List<StopAlt> stopAlts, String exitMode, double totalPnl,
                         double annualPct, double maxDrawdown, double maxDrawdownPct, double winRate, double avgWin,
                         double avgLoss, double winLossRatio, int losses) {
    }

    /** 候选止损对比：触发的轮按假设止损结算，其余按实际结果（期末持仓按浮动盈亏）。 */
    public record StopAlt(double stopPct, int hits, double pnl) {
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
        List<Book.Round> rounds = new java.util.ArrayList<>();
        DailyPlan plan = null;
        LocalDate from = null, last = null, lastBuyDay = null;
        int days = 0, tradeDays = 0;
        long maxHold = 0, holdSum = 0;
        double firstOpen = 0, lastClose = 0;
        double equityPeak = 0, maxDd = 0;
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
                    rounds.add(book.lastRound);
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
            // 权益 = 已实现 + 浮动（低点算回撤，收盘算新高）
            maxDd = Math.max(maxDd, equityPeak - (book.realized() + book.unrealized(bar.low(), p.feeRate())));
            equityPeak = Math.max(equityPeak, book.realized() + book.unrealized(bar.close(), p.feeRate()));
        }
        double avgHold = book.rounds() == 0 ? 0 : (double) holdSum / book.rounds();
        double endUnrealized = book.unrealized(lastClose, p.feeRate());
        List<StopAlt> alts = new java.util.ArrayList<>();
        for (double c : p.stopCandidatesPct()) {
            String key = String.valueOf(c);
            int hits = 0;
            double total = 0;
            for (Book.Round r : rounds) {
                Book.StopHit h = r.stopHits().get(key);
                if (h != null) hits++;
                total += h != null ? h.pnl() : r.pnl();
            }
            Book.StopHit h = book.holding() ? book.stopHits.get(key) : null;
            if (h != null) hits++;
            total += h != null ? h.pnl() : endUnrealized;
            alts.add(new StopAlt(c, hits, round2(total)));
        }
        double total = book.realized() + endUnrealized;
        double years = Math.max(days, 1) / 365.0;
        double[] wins = rounds.stream().mapToDouble(Book.Round::pnl).filter(x -> x > 0).toArray();
        double[] losses = rounds.stream().mapToDouble(Book.Round::pnl).filter(x -> x <= 0).toArray();
        double avgWin = java.util.Arrays.stream(wins).average().orElse(0);
        double avgLoss = java.util.Arrays.stream(losses).average().orElse(0);
        return new Result(p.version(), from, last, days, book.rounds(), round2(book.realized()),
                round2(book.maxFloatLoss()), round2(book.maxFloatLoss() / p.capUsdt() * 100), maxHold,
                round2(avgHold), round2(endUnrealized), round2(book.cost()), tradeDays,
                firstOpen > 0 ? round2((lastClose / firstOpen - 1) * 100) : 0, skipped, reduced, List.copyOf(alts),
                p.exit().mode(), round2(total), round2(total / p.capUsdt() * 100 / years), round2(maxDd),
                round2(maxDd / p.capUsdt() * 100), rounds.isEmpty() ? 0 : round2(100.0 * wins.length / rounds.size()),
                round2(avgWin), round2(avgLoss), avgLoss < 0 ? round2(avgWin / -avgLoss) : 0, losses.length);
    }

    private static double round2(double v) {
        return BtcDailyRules.round2(v);
    }
}
