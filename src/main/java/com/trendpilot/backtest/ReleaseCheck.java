package com.trendpilot.backtest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 发布前检查（自动化版，规则见 docs/规则说明.md "自动优化"一节）。候选和基准用同一份数据、同一时段：
 * <ol>
 * <li>验证期（最后 16 个月，不参与调参）和全期的权益变化都不低于基准；</li>
 * <li>每笔期望比基准高，且差值的 95% 区间（±1.96 × 合并标准误）不含 0（全期）——"证明更好"；</li>
 * <li>最大回撤（全期、验证期）都不超过基准 × 1.1；</li>
 * <li>六种行情没有一种明显变差：该行情期间权益比基准少 1,000 U 以上且少 25% 以上算明显变差；</li>
 * <li>比同规则的随机对照好：每笔差值的 95% 区间下沿 &gt; 0（全期）。</li>
 * <li><b>绝对上限</b>（用户 2026-10-26"安全第一"，目标最大回撤 20%～30% 以内）：全期净值最大回撤（资金 = 硬顶 20,000 U，
 *     含浮亏）&gt; 25% 的不进入候选，结论直接是 WORSE。</li>
 * <li><b>执行敏感度</b>（2026-11-08 回测与实盘对账后加入，见 docs/策略对比记录/2026-11-08-回测与实盘对账.md）：同一候选再用
 *     "15m 上影线只算 75%"（{@code sim.wick=0.75}，基准也同样重跑）跑一次。全期合计 ≤ 0、或每笔期望的 95% 区间含 0、或对基准的
 *     每笔差值 95% 区间含 0 的，这一项不满足——结论最高 PROMISING，不能 PASS。理由：V5/V6 的优势几乎全部来自利润保护卖在上影线附近，
 *     执行稍慢就没了。</li>
 * </ol>
 * 只做现货，不用杠杆和合约。候选排序先看风险调整收益（卡玛 = 全期收益 ÷ 最大回撤），再看绝对收益。
 * 结论：全部满足 = PASS；1 满足、或验证期不差且全期回撤更小 = PROMISING（有希望，作为影子变体跑 30 天）；其余 = WORSE（只存档）。
 * PASS 也不直接改 V2：一律先作为影子变体实盘跑满 30 天，再按自动转正条件决定（"回测好 + 实盘影子 30 天好"两道关）。
 */
public final class ReleaseCheck {

    public static final double DD_TOLERANCE = 1.10;
    public static final double REGIME_ABS = 1000;
    public static final double REGIME_REL = 0.25;
    /** 净值最大回撤绝对上限（比例）。 */
    public static final double MAX_NAV_DD = 0.25;
    /** 执行敏感度：利润保护里 15m 上影线计入的比例（1 = 原回测口径）。 */
    public static final double WICK_SENSITIVITY = 0.75;
    public static final String WICK_CHECK = "执行敏感度：上影线 75% 仍为正且显著";

    /** 执行敏感度的两组全期结果：候选和基准都按 {@link #WICK_SENSITIVITY} 重跑。 */
    public record Sensitivity(Period candFull, Period baseFull) {
    }

    public enum Verdict { PASS, PROMISING, WORSE }

    /** 一个时段的汇总：权益变化、最大回撤、每笔均值和方差、笔数。 */
    public record Period(double equity, double maxDd, int trades, double meanPnl, double varPnl, double navDd) {

        public Period(double equity, double maxDd, int trades, double meanPnl, double varPnl) {
            this(equity, maxDd, trades, meanPnl, varPnl, Double.NaN);
        }

        /** 卡玛（期间收益 ÷ 最大回撤，U 口径）。 */
        public double calmar() {
            return maxDd > 0 ? equity / maxDd : equity > 0 ? Double.POSITIVE_INFINITY : 0;
        }

        static Period of(Sim s, Regimes regimes, int from, int to) {
            return of(s, regimes, from, to, Double.NaN);
        }

        /** capital：净值口径的资金（硬顶），算净值最大回撤 = (峰值净值 − 净值) ÷ 峰值净值。 */
        static Period of(Sim s, Regimes regimes, int from, int to, double capital) {
            Metrics m = Metrics.of(s, regimes, from, to);
            double[] p = s.trades().stream().filter(t -> t.entryQ() >= from && t.entryQ() < to).mapToDouble(Trade::pnl).toArray();
            double mean = 0, var = 0;
            for (double x : p) mean += x;
            mean = p.length == 0 ? 0 : mean / p.length;
            for (double x : p) var += (x - mean) * (x - mean);
            var = p.length < 2 ? 0 : var / (p.length - 1);
            double navDd = Double.NaN;
            if (capital > 0) {
                double[] eq = s.equity();
                int a = from / 4, b = Math.min(to / 4, eq.length - 1);
                double peak = capital, dd = 0;
                for (int i = a; i <= b; i++) {
                    double nav = capital + eq[i] - eq[a];
                    peak = Math.max(peak, nav);
                    dd = Math.max(dd, (peak - nav) / peak);
                }
                navDd = dd;
            }
            return new Period(m.equityChange(), m.maxDrawdown(), p.length, mean, var, navDd);
        }
    }

    /** 两组每笔均值之差和 95% 半宽。 */
    public static double[] diff(Period a, Period b) {
        double se = Math.sqrt((a.trades() == 0 ? 0 : a.varPnl() / a.trades()) + (b.trades() == 0 ? 0 : b.varPnl() / b.trades()));
        return new double[]{a.meanPnl() - b.meanPnl(), 1.96 * se};
    }

    /** 每笔均值和 95% 半宽。 */
    public static double[] mean(Period a) {
        return new double[]{a.meanPnl(), a.trades() == 0 ? Double.NaN : 1.96 * Math.sqrt(a.varPnl() / a.trades())};
    }

    /**
     * @param wickOwn  执行敏感度下候选每笔期望 ±95%（没跑时 NaN）
     * @param wickDiff 执行敏感度下对基准的每笔差值 ±95%（没跑时 NaN）
     */
    public record Result(Verdict verdict, Map<String, Boolean> checks, List<String> notes, double[] perTradeDiff,
                         double[] randomDiff, double[] wickOwn, double[] wickDiff) {
    }

    /** 执行敏感度这一项是否满足：全期合计 &gt; 0、每笔期望和对基准的每笔差值的 95% 区间都在 0 以上。 */
    public static boolean sensitivityHolds(Sensitivity s) {
        if (s == null || s.candFull() == null || s.baseFull() == null) return false;
        double[] own = mean(s.candFull());
        double[] d = diff(s.candFull(), s.baseFull());
        return s.candFull().equity() > 0 && s.candFull().trades() >= 2 && own[0] - own[1] > 0 && d[0] - d[1] > 0;
    }

    /**
     * @param regimeCand 候选六种行情的权益（下标 1～6）
     */
    public static Result evaluate(Period candFull, Period candVal, Period baseFull, Period baseVal, double[] regimeCand,
                                  double[] regimeBase, Period randomFull) {
        return evaluate(candFull, candVal, baseFull, baseVal, regimeCand, regimeBase, randomFull, null, false);
    }

    /**
     * 每周自动回测和变体候选用的完整检查（含执行敏感度）。sens 为空时这一项按"不满足"处理（不能 PASS）。
     */
    public static Result evaluate(Period candFull, Period candVal, Period baseFull, Period baseVal, double[] regimeCand,
                                  double[] regimeBase, Period randomFull, Sensitivity sens) {
        return evaluate(candFull, candVal, baseFull, baseVal, regimeCand, regimeBase, randomFull, sens, true);
    }

    /** requireSensitivity = false 只给研究入口（不是 B0Sim 的模拟、没有上影线开关），它们的结论不会自动上线。 */
    private static Result evaluate(Period candFull, Period candVal, Period baseFull, Period baseVal, double[] regimeCand,
                                   double[] regimeBase, Period randomFull, Sensitivity sens, boolean requireSensitivity) {
        Map<String, Boolean> c = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        boolean better = candVal.equity() >= baseVal.equity() && candFull.equity() >= baseFull.equity();
        c.put("验证期和全期合计都不低于基准", better);
        double[] d = diff(candFull, baseFull);
        c.put("每笔期望显著更高（全期）", d[0] - d[1] > 0);
        boolean dd = candFull.maxDd() <= baseFull.maxDd() * DD_TOLERANCE && candVal.maxDd() <= baseVal.maxDd() * DD_TOLERANCE;
        c.put("最大回撤不超过基准 ×1.1", dd);
        boolean regimes = true;
        for (int r = 1; r <= 6; r++) {
            double loss = regimeBase[r] - regimeCand[r];
            if (loss > REGIME_ABS && loss > Math.abs(regimeBase[r]) * REGIME_REL) {
                regimes = false;
                notes.add(String.format(Locale.ROOT, "%s 比基准少 %,.0f U", Regimes.NAMES[r], loss));
            }
        }
        c.put("六种行情没有明显变差", regimes);
        double[] rd = randomFull == null ? new double[]{Double.NaN, Double.NaN} : diff(candFull, randomFull);
        c.put("显著好于同规则随机对照", randomFull != null && rd[0] - rd[1] > 0);
        boolean ddCap = Double.isNaN(candFull.navDd()) || candFull.navDd() <= MAX_NAV_DD;
        c.put("净值最大回撤 ≤ 25%（绝对上限）", ddCap);
        double[] wOwn = {Double.NaN, Double.NaN}, wDiff = {Double.NaN, Double.NaN};
        if (requireSensitivity) {
            boolean holds = sensitivityHolds(sens);
            c.put(WICK_CHECK, holds);
            if (sens != null && sens.candFull() != null && sens.baseFull() != null) {
                wOwn = mean(sens.candFull());
                wDiff = diff(sens.candFull(), sens.baseFull());
                if (!holds) notes.add(String.format(Locale.ROOT, "上影线 75%%：全期 %+,.0f U，每笔 %+.2f ±%.2f U，对基准每笔 %+.2f ±%.2f U",
                        sens.candFull().equity(), wOwn[0], wOwn[1], wDiff[0], wDiff[1]));
            } else {
                notes.add("没有跑上影线 75% 敏感度");
            }
        }
        boolean all = c.values().stream().allMatch(Boolean::booleanValue);
        // 有希望：两期合计都不差；或者验证期不差且全期回撤更小（收益/风险换了个比例，值得用实盘影子看看）
        boolean promising = better || (candVal.equity() >= baseVal.equity() && candFull.maxDd() < baseFull.maxDd());
        Verdict v = !ddCap ? Verdict.WORSE : all ? Verdict.PASS : promising ? Verdict.PROMISING : Verdict.WORSE;
        return new Result(v, c, notes, d, rd, wOwn, wDiff);
    }
}
