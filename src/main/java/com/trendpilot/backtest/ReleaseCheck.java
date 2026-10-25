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
 * </ol>
 * 结论：全部满足 = PASS；1 满足、或验证期不差且全期回撤更小 = PROMISING（有希望，作为影子变体跑 30 天）；其余 = WORSE（只存档）。
 * PASS 也不直接改 V2：一律先作为影子变体实盘跑满 30 天，再按自动转正条件决定（"回测好 + 实盘影子 30 天好"两道关）。
 */
public final class ReleaseCheck {

    public static final double DD_TOLERANCE = 1.10;
    public static final double REGIME_ABS = 1000;
    public static final double REGIME_REL = 0.25;

    public enum Verdict { PASS, PROMISING, WORSE }

    /** 一个时段的汇总：权益变化、最大回撤、每笔均值和方差、笔数。 */
    public record Period(double equity, double maxDd, int trades, double meanPnl, double varPnl) {

        static Period of(Sim s, Regimes regimes, int from, int to) {
            Metrics m = Metrics.of(s, regimes, from, to);
            double[] p = s.trades().stream().filter(t -> t.entryQ() >= from && t.entryQ() < to).mapToDouble(Trade::pnl).toArray();
            double mean = 0, var = 0;
            for (double x : p) mean += x;
            mean = p.length == 0 ? 0 : mean / p.length;
            for (double x : p) var += (x - mean) * (x - mean);
            var = p.length < 2 ? 0 : var / (p.length - 1);
            return new Period(m.equityChange(), m.maxDrawdown(), p.length, mean, var);
        }
    }

    /** 两组每笔均值之差和 95% 半宽。 */
    public static double[] diff(Period a, Period b) {
        double se = Math.sqrt((a.trades() == 0 ? 0 : a.varPnl() / a.trades()) + (b.trades() == 0 ? 0 : b.varPnl() / b.trades()));
        return new double[]{a.meanPnl() - b.meanPnl(), 1.96 * se};
    }

    public record Result(Verdict verdict, Map<String, Boolean> checks, List<String> notes, double[] perTradeDiff,
                         double[] randomDiff) {
    }

    /**
     * @param regimeCand 候选六种行情的权益（下标 1～6）
     */
    public static Result evaluate(Period candFull, Period candVal, Period baseFull, Period baseVal, double[] regimeCand,
                                  double[] regimeBase, Period randomFull) {
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
        boolean all = c.values().stream().allMatch(Boolean::booleanValue);
        // 有希望：两期合计都不差；或者验证期不差且全期回撤更小（收益/风险换了个比例，值得用实盘影子看看）
        boolean promising = better || (candVal.equity() >= baseVal.equity() && candFull.maxDd() < baseFull.maxDd());
        Verdict v = all ? Verdict.PASS : promising ? Verdict.PROMISING : Verdict.WORSE;
        return new Result(v, c, notes, d, rd);
    }
}
