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
import java.util.function.Predicate;

import com.trendpilot.market.Kline;

/**
 * 研究用（不改线上规则）：BTC 短线几类方案统一回放。全部只做现货、不止损（只在含费均价 +0.3% 以上卖）、可加仓、资金上限 cap。
 * <ul>
 * <li>F2 每日开盘 − a×ATR 挂第一单（当天有效）；F3 近 N 日最高价 − k×ATR；F4 昨收在 SMA(ma) 上方时，max(EMA20, 开盘 − x×ATR)；
 *     F5 月开盘 × (1 − y%)；RND 随机日开盘买（随机基准）。空仓时每天最多开一轮。</li>
 * <li>加仓：上一笔成交价 − 间距×ATR（ATR 每天更新），跨天、跨月持续有效，直到 L 档用完；间距 C1 = 1×ATR，C0.75，INC = 1、1.5、2…×ATR；
 *     金额 EQ 等额或 INC 递增（1:2:…:L），合计 = cap。满额后 STOP 不再买，或 PROFIT：继续每跌一个末档间距，用本账本已实现利润买入（每笔 cap/L/2，以已实现利润为限）。</li>
 * <li>卖出：BASE = max(0.5×ATR%, 0.8%)；ATR m = m×ATR%；FIX t%；HH = 开仓时近 N 日最高价（至少 +0.3%）；
 *     TRAIL：浮盈达到激活线（1×ATR% 或 3%）后，从最高价回落 g×ATR 卖，始终不低于均价 +0.3%。
 *     超时 D 天（0 = 不降）后目标降为均价 +0.3%。当根 K 线有买入时不在当根卖出（保守）。</li>
 * </ul>
 */
public final class BtcPlanStudy {

    private BtcPlanStudy() {
    }

    public record Cfg(String family, double e1, double e2, int levels, String spacing, String amounts, String after,
                      String exit, double exitArg, double trailGive, int timeoutDays, double cap) {
        public String label() {
            String entry = switch (family) {
                case "F2" -> String.format(Locale.ROOT, "开盘−%.2fATR", e1);
                case "V2B" -> "现行买法(开盘−0.25/0.6ATR,下跌趋势不开)";
                case "V2K" -> String.format(Locale.ROOT, "现行买法 第1档 强%.2f/弱%.2fATR", e1, e2);
                case "V2P" -> String.format(Locale.ROOT, "现行买法 第1档 开盘−%.2f%%", e1);
                case "F3" -> String.format(Locale.ROOT, "%d日高−%.0fATR", (int) e1, e2);
                case "F4" -> String.format(Locale.ROOT, "SMA%d上 回EMA20或−%.1fATR", (int) e1, e2);
                case "F5" -> String.format(Locale.ROOT, "月开−%.0f%%", e1);
                default -> "随机";
            };
            String ex = switch (exit) {
                case "BASE" -> "止盈max(0.5ATR,0.8%)";
                case "ATR" -> String.format(Locale.ROOT, "止盈%.0fATR", exitArg);
                case "FIX" -> String.format(Locale.ROOT, "止盈%.0f%%", exitArg);
                case "HH" -> "止盈回到N日高";
                case "ADAPT50", "ADAPT100" -> String.format(Locale.ROOT, "分行情跟踪(激活%s, 昨收>SMA%s 回落%s, 否则回落1ATR)",
                        exitArg < 0 ? "1ATR" : "3%", exit.substring(5), give(trailGive));
                default -> String.format(Locale.ROOT, "跟踪(激活%s 回落%s)", exitArg < 0 ? "1ATR" : "3%", give(trailGive));
            };
            return String.format(Locale.ROOT, "%s %s %d档%s%s %s%s %s%s", family, entry, levels, spacing, amounts,
                    "PROFIT".equals(after) ? "满额后用利润加 " : "", ex, timeoutDays > 0 ? timeoutDays + "天降" : "不降",
                    cap == 10_000 ? "" : String.format(Locale.ROOT, " 资金%.0f", cap));
        }

        static String give(double g) {
            return g > 0 ? String.format(Locale.ROOT, "%.0fATR", g) : String.format(Locale.ROOT, "%.0f%%", -g);
        }

        public Cfg withCap(double c) {
            return new Cfg(family, e1, e2, levels, spacing, amounts, after, exit, exitArg, trailGive, timeoutDays, c);
        }

        public Cfg asRandom() {
            return new Cfg("RND", 0, 0, levels, spacing, amounts, after, exit, exitArg, trailGive, timeoutDays, cap);
        }
    }

    public record Run(String name, int rounds, int wins, double realized, double endUnrealized, double total, double maxDd,
                      double maxFloatLoss, long maxHoldDays, int days, int heldDays, int opened, double utilization,
                      double[] roundPnls, double[] roundPcts, Map<String, double[]> cat, Set<YearMonth> winMonths,
                      Map<Integer, double[]> byYear, EqTracker eq, double endQty, int eligibleDays) {
    }

    /** 预先算好的每日指标（只用当天之前已收盘的日线）。 */
    public static final class Days {
        final Map<LocalDate, Integer> index = new LinkedHashMap<>();
        final double[] atr, ema20, sma50, sma100, open, monthOpen;
        final double[][] hh; // [N 10/20/30][i]
        final String[] cat;
        final boolean[] down;
        final List<Kline> daily;

        public Days(List<Kline> daily, BtcDailyProperties p) {
            this.daily = daily;
            int n = daily.size();
            atr = new double[n];
            ema20 = new double[n];
            sma50 = new double[n];
            sma100 = new double[n];
            open = new double[n];
            monthOpen = new double[n];
            hh = new double[3][n];
            cat = new String[n];
            down = new boolean[n];
            Set<LocalDate> crash = new HashSet<>();
            for (int i = 3; i < n; i++) {
                double d1 = daily.get(i).close() / daily.get(i - 1).close() - 1;
                double d3 = daily.get(i).close() / daily.get(i - 3).close() - 1;
                if (d1 <= -0.07 || d3 <= -0.10) crash.add(BtcDailyRules.dayOf(daily.get(i).openTime()));
            }
            double mo = 0;
            YearMonth curM = null;
            for (int i = 0; i < n; i++) {
                LocalDate d = BtcDailyRules.dayOf(daily.get(i).openTime());
                index.put(d, i);
                open[i] = daily.get(i).open();
                if (!YearMonth.from(d).equals(curM)) {
                    curM = YearMonth.from(d);
                    mo = open[i];
                }
                monthOpen[i] = mo;
                if (i < BtcGridStudy.DAILY_WINDOW) continue;
                List<Kline> before = daily.subList(i - BtcGridStudy.DAILY_WINDOW, i);
                atr[i] = BtcDailyRules.atr(before, p.atrDays());
                ema20[i] = BtcDailyRules.ema(before, 20);
                sma50[i] = sma(before, 50);
                sma100[i] = sma(before, 100);
                int[] ns = {10, 20, 30};
                for (int k = 0; k < 3; k++) {
                    double h = 0;
                    for (int j = before.size() - ns[k]; j < before.size(); j++) h = Math.max(h, before.get(j).high());
                    hh[k][i] = h;
                }
                cat[i] = BtcGridStudy.category(d, crash, BtcDailyRules.regime(before, p));
                down[i] = BtcDailyRules.downtrend(before, p);
            }
        }

        static double sma(List<Kline> d, int n) {
            double s = 0;
            for (int i = d.size() - n; i < d.size(); i++) s += d.get(i).close();
            return s / n;
        }

        double prevClose(int i) {
            return daily.get(i - 1).close();
        }
    }

    private static final class Pos {
        double qty, cost, lastFill, peak, entryHH, minPrice = Double.MAX_VALUE, lastStep;
        int filled;
        long openedAt;
        String cat;
    }

    static double[] tranches(Cfg c) {
        double[] w = new double[c.levels()];
        double sum = 0;
        for (int i = 0; i < w.length; i++) {
            w[i] = "INC".equals(c.amounts()) ? i + 1 : "V2".equals(c.amounts()) ? (i == w.length - 1 ? 4 : 3) : 1;
            sum += w[i];
        }
        for (int i = 0; i < w.length; i++) w[i] = c.cap() * w[i] / sum;
        return w;
    }

    static double spacing(Cfg c, int filled) {
        return switch (c.spacing()) {
            case "C0.75" -> 0.75;
            case "C0.5" -> 0.5;
            case "INC" -> 1 + 0.5 * (filled - 1);
            default -> 1.0;
        };
    }

    public static Run run(Cfg c, Days dd, List<Kline> bars, LocalDate from, LocalDate to, BtcDailyProperties p,
                          Predicate<LocalDate> allowNew) {
        double fee = p.feeRate(), slip = p.slippageBps() / 10_000.0;
        double[] tr = tranches(c);
        int hk = c.family().equals("F3") ? (c.e1() == 10 ? 0 : c.e1() == 20 ? 1 : 2) : 1;
        Pos pos = null;
        LocalDate cur = null;
        int idx = -1;
        double limit = Double.NaN, realized = 0, profitUsed = 0, peakEq = 0, maxDd = 0, maxFloat = 0, lastClose = 0;
        boolean canOpen = false;
        int rounds = 0, wins = 0, days = 0, held = 0, opened = 0, eligible = 0;
        long maxHold = 0, barsN = 0;
        double util = 0;
        List<Double> pnls = new ArrayList<>(), pcts = new ArrayList<>();
        Map<String, double[]> cat = new LinkedHashMap<>();
        for (String s : BtcGridStudy.CATS) cat.put(s, new double[3]);
        Set<YearMonth> winMonths = new TreeSet<>();
        Map<Integer, double[]> byYear = new LinkedHashMap<>();
        EqTracker eq = new EqTracker(c.cap());
        for (Kline bar : bars) {
            LocalDate day = BtcDailyRules.dayOf(bar.openTime());
            if (day.isBefore(from) || day.isAfter(to)) continue;
            if (!day.equals(cur)) {
                cur = day;
                Integer ix = dd.index.get(day);
                idx = ix == null || ix < BtcGridStudy.DAILY_WINDOW ? -1 : ix;
                if (idx < 0) continue;
                days++;
                if (pos != null) held++;
                canOpen = pos == null && allowNew.test(day);
                double atr = dd.atr[idx];
                limit = switch (c.family()) {
                    case "F2" -> dd.open[idx] - c.e1() * atr;
                    case "V2B" -> dd.down[idx] ? Double.NaN
                            : dd.open[idx] - (dd.prevClose(idx) > dd.ema20[idx] ? 0.25 : 0.6) * atr;
                    case "V2P" -> dd.down[idx] ? Double.NaN : dd.open[idx] * (1 - c.e1() / 100);
                    case "V2K" -> dd.down[idx] ? Double.NaN
                            : dd.open[idx] - (dd.prevClose(idx) > dd.ema20[idx] ? c.e1() : c.e2()) * atr;
                    case "F3" -> dd.hh[hk][idx] - c.e2() * atr;
                    case "F4" -> dd.prevClose(idx) > (c.e1() == 50 ? dd.sma50[idx] : dd.sma100[idx])
                            ? Math.max(dd.ema20[idx], dd.open[idx] - c.e2() * atr) : Double.NaN;
                    case "F5" -> dd.monthOpen[idx] * (1 - c.e1() / 100);
                    default -> dd.open[idx];
                };
                if (canOpen && !Double.isNaN(limit)) eligible++;
            }
            if (idx < 0) continue;
            double atr = dd.atr[idx];
            boolean bought = false;
            if (pos == null && canOpen && !Double.isNaN(limit) && bar.low() <= limit) {
                pos = new Pos();
                buy(pos, Math.min(limit, bar.open()) * (1 + slip), tr[0], fee);
                pos.openedAt = bar.openTime();
                pos.cat = dd.cat[idx];
                pos.entryHH = dd.hh[hk][idx];
                pos.lastStep = spacing(c, 1);
                pos.peak = pos.lastFill;
                canOpen = false;
                opened++;
                bought = true;
            }
            while (pos != null) {
                double step = pos.filled < tr.length ? spacing(c, pos.filled) : pos.lastStep;
                double next = pos.lastFill - step * atr;
                if (bar.low() > next) break;
                double amt;
                if (pos.filled < tr.length) {
                    amt = tr[pos.filled];
                } else {
                    if (!"PROFIT".equals(c.after())) break;
                    amt = Math.min(c.cap() / tr.length / 2, realized - profitUsed);
                    if (amt < 50) break;
                    profitUsed += amt;
                }
                buy(pos, Math.min(next, bar.open()) * (1 + slip), amt, fee);
                pos.lastStep = step;
                bought = true;
            }
            if (pos != null) {
                pos.minPrice = Math.min(pos.minPrice, bar.low());
                if (!bought) {
                    double avg = pos.cost / pos.qty;
                    // 硬规则（用户 2026-10-20）：绝不卖亏，成交价下限 = 含费均价 × (1 + 手续费 + 0.3%)，按滑点倒推挂价
                    double floor = minSellPrice(avg, fee, slip);
                    double atrPct = atr / dd.open[idx];
                    boolean timeout = c.timeoutDays() > 0 && bar.openTime() - pos.openedAt >= c.timeoutDays() * 86_400_000L;
                    double sellAt = Double.NaN;
                    if ("TRAIL".equals(c.exit()) || c.exit().startsWith("ADAPT")) {
                        double act = avg * (1 + (c.exitArg() < 0 ? atrPct : 0.03));
                        double g = c.trailGive();
                        if (c.exit().startsWith("ADAPT")) {
                            double ma = "ADAPT50".equals(c.exit()) ? dd.sma50[idx] : dd.sma100[idx];
                            if (dd.prevClose(idx) <= ma) g = 1; // 不在均线上方：窄跟踪 1×ATR
                        }
                        double give = g > 0 ? g * atr : pos.peak * -g / 100;
                        if (pos.peak >= act) {
                            double stop = Math.max(floor, pos.peak - give);
                            if (bar.low() <= stop) sellAt = Math.min(stop, bar.open());
                        }
                        if (Double.isNaN(sellAt) && timeout && bar.high() >= floor) sellAt = Math.max(floor, bar.open());
                        pos.peak = Math.max(pos.peak, bar.high()); // 本根的新高只影响下一根的回落判断（保守）
                    } else {
                        double t = switch (c.exit()) {
                            case "BASE" -> Math.max(0.5 * atrPct, 0.008);
                            case "ATR" -> c.exitArg() * atrPct;
                            case "FIX" -> c.exitArg() / 100;
                            case "HH" -> Math.max(pos.entryHH / avg - 1, 0.003);
                            default -> 0.008;
                        };
                        double target = timeout ? floor : Math.max(floor, avg * (1 + t));
                        if (bar.high() >= target) sellAt = Math.max(target, bar.open());
                    }
                    if (!Double.isNaN(sellAt) && sellAt >= floor) {
                        double px = sellAt * (1 - slip);
                        double pnl = pos.qty * px * (1 - fee) - pos.cost;
                        if (px < avg * (1 + fee + 0.003) * (1 - 1e-9) || pnl <= 0) {
                            throw new IllegalStateException("方案无效：出现亏损卖出 " + c.label() + " 均价 " + avg + " 卖价 " + px);
                        }
                        realized += pnl;
                        rounds++;
                        if (pnl > 0) {
                            wins++;
                            winMonths.add(YearMonth.from(day));
                        }
                        pnls.add(pnl);
                        pcts.add(100 * pnl / pos.cost);
                        double[] k = cat.get(pos.cat);
                        k[0]++;
                        k[1] += pnl;
                        k[2] = Math.min(k[2], (pos.minPrice / avg - 1) * 100);
                        double[] y = byYear.computeIfAbsent(day.getYear(), z -> new double[2]);
                        y[0]++;
                        y[1] += pnl;
                        maxHold = Math.max(maxHold, (bar.closeTime() + 1 - pos.openedAt) / 86_400_000L);
                        pos = null;
                    }
                }
            }
            double uLow = 0, uClose = 0;
            if (pos != null) {
                uLow = pos.qty * bar.low() * (1 - fee) - pos.cost;
                uClose = pos.qty * bar.close() * (1 - fee) - pos.cost;
                maxFloat = Math.min(maxFloat, uLow);
                maxHold = Math.max(maxHold, (bar.closeTime() + 1 - pos.openedAt) / 86_400_000L);
                util += Math.min(1, pos.cost / c.cap());
            }
            barsN++;
            eq.on(day, bar.open(), c.cap() + realized + uClose, bar.close());
            maxDd = Math.max(maxDd, peakEq - (realized + uLow));
            peakEq = Math.max(peakEq, realized + uClose);
            lastClose = bar.close();
        }
        double endU = pos == null ? 0 : pos.qty * lastClose * (1 - fee) - pos.cost;
        if (pos != null) {
            double[] k = cat.get(pos.cat);
            k[2] = Math.min(k[2], (pos.minPrice / (pos.cost / pos.qty) - 1) * 100);
        }
        return new Run(c.label(), rounds, wins, realized, endU, realized + endU, maxDd, maxFloat, maxHold, days, held, opened,
                barsN == 0 ? 0 : util / barsN, pnls.stream().mapToDouble(Double::doubleValue).toArray(),
                pcts.stream().mapToDouble(Double::doubleValue).toArray(), cat, winMonths, byYear, eq, pos == null ? 0 : pos.qty, eligible);
    }

    /** 最低卖出挂价：成交价（扣滑点后）不低于含费均价 × (1 + 手续费 + 0.3%)。 */
    public static double minSellPrice(double avg, double fee, double slip) {
        return avg * (1 + fee + 0.003) / (1 - slip);
    }

    private static void buy(Pos pos, double px, double usdt, double fee) {
        pos.qty += usdt * (1 - fee) / px;
        pos.cost += usdt;
        pos.filled++;
        pos.lastFill = px;
    }

    /** 区间内完整月份里有盈利平仓的占比（%）。 */
    public static double coverage(Set<YearMonth> win, LocalDate from, LocalDate to) {
        int n = 0, h = 0;
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            n++;
            if (win.contains(m)) h++;
        }
        return n == 0 ? 0 : 100.0 * h / n;
    }
}
