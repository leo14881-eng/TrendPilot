package com.trendpilot.backtest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.trendpilot.v2n.V2nParams;

/**
 * 回测报告（Markdown）：总体、前 7 个月（选参数）/ 后段（只验证一次）、六种行情、消融、止损调查，以及按 7.4 的切换判断。
 */
public final class Report {

    public record Run(Sim sim, V2nParams params, String group) {
    }

    private final Regimes regimes;
    private final int fromQ, splitQ, toQ;
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private final StringBuilder md = new StringBuilder();
    private final String period;

    public Report(Regimes regimes, int fromQ, int splitQ, int toQ, String period) {
        this.regimes = regimes;
        this.fromQ = fromQ;
        this.splitQ = splitQ;
        this.toQ = toQ;
        this.period = period;
    }

    public void add(Run r) {
        runs.put(r.sim().name(), r);
    }

    private Metrics m(String name, int a, int b) {
        Run r = runs.get(name);
        return r == null ? null : Metrics.of(r.sim(), regimes, a, b);
    }

    private void line(String s) {
        md.append(s).append('\n');
    }

    private static String f(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String f0(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%,.0f", v);
    }

    private static String pct(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.0f%%", v * 100);
    }

    private void table(String title, List<String> names, int a, int b) {
        line("");
        line("### " + title);
        line("");
        line("| 方案 | 笔数 | 胜率 | 权益变化 | 最大回撤 | 每笔均值 | 每笔 R | 盈亏比 | 最差5%均值 | 最长亏损持有(天) | 去掉最好3笔 | 价格止损 | 正常行情止损占比 | 误杀占比 |");
        line("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
        for (String n : names) {
            Metrics x = m(n, a, b);
            if (x == null) continue;
            line("| " + n + " | " + x.trades() + " | " + pct(x.winRate()) + " | " + f0(x.equityChange()) + " | " + f0(x.maxDrawdown())
                    + " | " + f(x.meanPct()) + "% | " + f(x.meanR()) + " | " + f(x.payoff()) + " | " + f(x.cvar5Pct()) + "% | "
                    + f(x.longestLossDays()) + " | " + f0(x.withoutTop3()) + " | " + x.l1Stops() + " | " + pct(x.l1Share())
                    + " | " + pct(x.falseStopShare()) + " |");
        }
    }

    private void regimeTable(String title, List<String> names, int a, int b) {
        line("");
        line("### " + title);
        line("");
        StringBuilder h = new StringBuilder("| 方案 |");
        StringBuilder sep = new StringBuilder("|---|");
        for (int r = 1; r <= 6; r++) {
            h.append(' ').append(Regimes.NAMES[r]).append(" 笔数/每笔/权益 |");
            sep.append("---:|");
        }
        line(h.toString());
        line(sep.toString());
        for (String n : names) {
            Metrics x = m(n, a, b);
            if (x == null) continue;
            StringBuilder row = new StringBuilder("| " + n + " |");
            for (int r = 1; r <= 6; r++) {
                row.append(' ').append(x.regimeTrades()[r]).append(" / ").append(f(x.regimeMeanPct()[r])).append("% / ")
                        .append(f0(x.regimeEquity()[r])).append(" |");
            }
            line(row.toString());
        }
        line("");
        StringBuilder hrs = new StringBuilder("各行情小时数：");
        for (int r = 1; r <= 6; r++) hrs.append(Regimes.NAMES[r]).append(' ').append(regimes.hours(r, a, b)).append("　");
        line(hrs.toString());
    }

    /** 某种行情里 N 是否不比 B0 明显差（7.3 / 7.4）。 */
    static boolean regimeOk(Metrics n, Metrics b0, int r) {
        int nn = n.regimeTrades()[r], nb = b0.regimeTrades()[r];
        if (nn == 0) return n.regimeEquity()[r] >= b0.regimeEquity()[r] - 1e-9;
        if (nb == 0) return n.regimeEquity()[r] >= 0 || n.regimeEquity()[r] >= b0.regimeEquity()[r];
        double tol = nn < 30 || nb < 30 ? -0.5 : -0.2;
        return n.regimeMeanPct()[r] - b0.regimeMeanPct()[r] >= tol || n.regimeEquity()[r] >= b0.regimeEquity()[r];
    }

    static boolean stopsOk(Metrics x) {
        return x.l1Share() <= 0.25 && x.falseStopShare() <= 0.30;
    }

    public String build(List<String> grid, List<String> ablations, List<String> stopStudy) {
        line("# V2 规则一年回测报告");
        line("");
        line("数据：" + period + "，币安现货 15m K 线（data.binance.vision），每天按前一天成交额取前 150 个币。");
        line("信号、买入审核、资金分配、系统风险、现行退出规则都直接调用线上同一套 Java 类；成交价按成交额分档估算冲击 + 0.1% 手续费（没有历史盘口，深度门槛用 24h 成交额 ≥ 3M U 近似）。");
        line("同一根 K 线按 开→低→高→收 走（先止损后止盈，保守）。权益 = 已实现 + 按可退出价格的浮动，每小时记一次。");

        // 1. 在校准期选参数（满足止损频率上限的里面权益变化最大的）
        String best = null;
        double bestEq = Double.NEGATIVE_INFINITY;
        for (String g : grid) {
            Metrics x = m(g, fromQ, splitQ);
            if (x == null || !stopsOk(x)) continue;
            if (x.equityChange() > bestEq) {
                bestEq = x.equityChange();
                best = g;
            }
        }
        if (best == null) best = "N";

        Metrics vb = m("B0", splitQ, toQ), vn = m(best, splitQ, toQ);
        Metrics fb = m("B0", fromQ, toQ), fnm = m(best, fromQ, toQ);
        List<String> fails = new ArrayList<>();
        if (vn.equityChange() < vb.equityChange()) fails.add("验证期权益变化低于现行");
        if (!(vn.maxDrawdown() <= vb.maxDrawdown() * 0.8 || vn.ciLowPct() > vb.meanPct())) fails.add("验证期回撤没有改善 20%，每笔均值置信下限也没有超过现行");
        for (int r = 1; r <= 6; r++) if (!regimeOk(vn, vb, r)) fails.add("验证期「" + Regimes.NAMES[r] + "」比现行明显差");
        if (!stopsOk(vn)) fails.add("验证期止损频率超标（正常行情止损占比 " + pct(vn.l1Share()) + "，误杀 " + pct(vn.falseStopShare()) + "）");
        int better = 0, total = 0;
        V2nParams bp = runs.get(best).params();
        for (String g : grid) {
            if (g.equals(best) || runs.get(g).params() == null || !neighbor(bp, runs.get(g).params())) continue;
            total++;
            if (m(g, splitQ, toQ).equityChange() >= vb.equityChange()) better++;
        }
        if (total > 0 && better * 2 < total) fails.add("相邻参数在验证期多数不如现行（" + better + "/" + total + "）");
        if (vn.withoutTop3() < vb.withoutTop3()) fails.add("去掉最好 3 笔后验证期不如现行");

        line("");
        line("## 结论");
        line("");
        line("- 校准期（前段）选出的参数：**" + best + "**（" + describe(bp) + "）");
        line("- 验证期（后段，只看一次）：新规则权益变化 " + f0(vn.equityChange()) + " U、最大回撤 " + f0(vn.maxDrawdown())
                + " U；现行 " + f0(vb.equityChange()) + " U、最大回撤 " + f0(vb.maxDrawdown()) + " U。");
        line("- 全年：新规则 " + f0(fnm.equityChange()) + " U（回撤 " + f0(fnm.maxDrawdown()) + "），现行 " + f0(fb.equityChange())
                + " U（回撤 " + f0(fb.maxDrawdown()) + "）。");
        line("- 相邻参数验证期不差于现行：" + better + "/" + total + "。");
        line(fails.isEmpty() ? "- **按 7.4 判断：回测通过**，可以进入 2 周影子并行。"
                : "- **按 7.4 判断：回测不通过**，原因：" + String.join("；", fails) + "。");
        line("");
        line("SELECTED=" + best);
        line("PASSED=" + fails.isEmpty());

        List<String> main = new ArrayList<>(List.of("B0", "N"));
        if (!main.contains(best)) main.add(best);
        line("");
        line("## 总体对比");
        table("全年", main, fromQ, toQ);
        table("校准期（前段）", main, fromQ, splitQ);
        table("验证期（后段）", main, splitQ, toQ);
        line("");
        line("## 六种行情（验证期）");
        regimeTable("每种行情：笔数 / 每笔平均收益 / 该行情期间的权益变化(U)", main, splitQ, toQ);
        regimeTable("全年", main, fromQ, toQ);
        line("");
        line("## 消融（全年；去掉一个部件看它有没有用）");
        List<String> ab = new ArrayList<>(List.of("B0", "N"));
        ab.addAll(ablations);
        table("消融", ab, fromQ, toQ);
        line("");
        line("## 参数网格（校准期，事先登记的值）");
        table("网格", grid, fromQ, splitQ);
        line("");
        line("## 止损调查（3.1）");
        stopStudy(stopStudy);
        return md.toString();
    }

    private void stopStudy(List<String> names) {
        Run free = runs.get("N-无价格止损");
        if (free != null) {
            List<Trade> winners = free.sim().trades().stream().filter(t -> t.pnl() >= 2 * t.r() && t.atr1hPct() > 0).toList();
            line("");
            line("1. 不设价格止损时，最终赚到 +2R 以上的单子 " + winners.size() + " 笔，它们到最高点之前最深跌了多少（以买入时 1h ATR 的倍数计）：");
            line("");
            line("| 止损倍数 k | 这批赢家不被打掉的比例（1h ATR） |");
            line("|---:|---:|");
            for (double k : new double[]{2.5, 3, 4, 5, 6}) {
                long ok = winners.stream().filter(t -> -t.maeBeforePeak() < k * t.atr1hPct()).count();
                line("| " + k + " | " + pct(winners.isEmpty() ? Double.NaN : ok / (double) winners.size()) + " |");
            }
            line("");
            line("| 日线 ATR 倍数 | 不被打掉的比例 |");
            line("|---:|---:|");
            for (double k : new double[]{0.5, 0.75, 1, 1.5}) {
                long ok = winners.stream().filter(t -> -t.maeBeforePeak() < k * t.atr1dPct()).count();
                line("| " + k + " | " + pct(winners.isEmpty() ? Double.NaN : ok / (double) winners.size()) + " |");
            }
        }
        line("");
        line("2～4. 各止损方案（全年）：频率上限是正常行情止损占比 ≤ 25%、误杀（卖出后 3 天内回到成本）≤ 30%；最终看是否比不设止损赚得多或回撤明显小。");
        List<String> all = new ArrayList<>(List.of("N", "N-无价格止损"));
        all.addAll(names);
        table("止损方案", all, fromQ, toQ);
    }

    static boolean neighbor(V2nParams a, V2nParams b) {
        int diff = 0;
        if (a.stopAtr() != b.stopAtr()) diff++;
        if (a.timeStopHours() != b.timeStopHours()) diff++;
        if (a.altWeakPct() != b.altWeakPct()) diff++;
        if (a.partialR() != b.partialR()) diff++;
        if (a.trailAtr() != b.trailAtr()) diff++;
        if (a.useDailyAtr() != b.useDailyAtr() || !a.stopMode().equals(b.stopMode())) return false;
        return diff == 1;
    }

    static String describe(V2nParams p) {
        return String.format(Locale.ROOT, "止损 %.1f×ATR、时间止损 %dh、山寨门 %.0f%%、%.0fR 部分止盈、跟踪 %.1f×ATR、止损触发 %s",
                p.stopAtr(), p.timeStopHours(), p.altWeakPct(), p.partialR(), p.trailAtr(), p.stopMode());
    }

    /** 所有方案的一行汇总（CSV）。 */
    public String summaryCsv() {
        StringBuilder b = new StringBuilder("name,segment,trades,winRate,equityChange,maxDrawdown,meanPct,meanR,payoff,cvar5,l1Share,falseStop\n");
        for (String n : runs.keySet()) {
            for (String seg : new String[]{"full", "calib", "valid"}) {
                int a = seg.equals("valid") ? splitQ : fromQ, z = seg.equals("calib") ? splitQ : toQ;
                Metrics x = m(n, a, z);
                b.append(String.format(Locale.ROOT, "%s,%s,%d,%.4f,%.2f,%.2f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n", n, seg, x.trades(),
                        x.winRate(), x.equityChange(), x.maxDrawdown(), x.meanPct(), x.meanR(), x.payoff(), x.cvar5Pct(),
                        x.l1Share(), x.falseStopShare()));
            }
        }
        return b.toString();
    }
}
