package com.trendpilot.backtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.trendpilot.btcdaily.BtcDailyProperties;
import com.trendpilot.btcdaily.BtcGridStudy;
import com.trendpilot.btcdaily.BtcV4Study;
import com.trendpilot.market.Kline;

/**
 * BTC 每日短线 V4 候选（ATR 阶梯挂单、跨天加仓、不止损）网格 + 现行规则对比 + 随机基准（研究，不改线上规则）。
 * 参数：数据目录 输出目录 [起始月 结束月 最后一天 回测开始日 随机次数]
 */
public final class BtcV4StudyMain {

    private BtcV4StudyMain() {
    }

    static final String[] CATS = {"上涨", "震荡", "下跌", "暴跌", "暴跌后反弹"};

    record Row(BtcV4Study.Params p, BtcV4Study.Run full, BtcV4Study.Run recent) {
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "backtest-data");
        Path outDir = Path.of(args.length > 1 ? args[1] : "backtest-out");
        YearMonth from = YearMonth.parse(args.length > 2 ? args[2] : "2019-08");
        YearMonth to = YearMonth.parse(args.length > 3 ? args[3] : "2026-09");
        LocalDate lastDay = args.length > 4 ? LocalDate.parse(args[4]) : LocalDate.of(2026, 10, 18);
        LocalDate tradeFrom = LocalDate.parse(args.length > 5 ? args[5] : "2020-01-01");
        int seeds = args.length > 6 ? Integer.parseInt(args[6]) : 200;
        Files.createDirectories(outDir);
        List<Kline> daily = BtcGridStudyMain.load(new Archive(dataDir.resolve("btc-1d"), "1d"), "1d", from, to, lastDay, 86_400_000L);
        List<Kline> bars = BtcGridStudyMain.load(new Archive(dataDir.resolve("btc-15m"), "15m"), "15m", from, to, lastDay, 15 * 60_000L);
        System.out.println("日线 " + daily.size() + " 根，15m " + bars.size() + " 根");
        LocalDate oneYear = lastDay.minusDays(365);
        // 覆盖率按完整月：全期从起始月到最后一个完整月；近 1 年从下一个月开始
        LocalDate covEnd = lastDay.withDayOfMonth(1).minusDays(1);
        LocalDate recentCovFrom = oneYear.plusMonths(1).withDayOfMonth(1);
        double years = (lastDay.toEpochDay() - tradeFrom.toEpochDay()) / 365.25;

        BtcDailyProperties base = BtcDailyProperties.defaults();
        BtcDailyProperties curP = BtcGridStudy.params(base, 0.25, 0.6, 0.5, 0.5, true);
        BtcGridStudy.Run cur = BtcGridStudy.run("现行", daily, bars, tradeFrom, curP, BtcGridStudy.filterContext(curP));
        BtcGridStudy.Run curR = BtcGridStudy.run("现行", daily, bars, oneYear, curP, BtcGridStudy.filterContext(curP));
        double curCov = BtcV4Study.monthCoverage(cur.winMonths(), tradeFrom, covEnd);
        double curCovR = BtcV4Study.monthCoverage(curR.winMonths(), recentCovFrom, covEnd);

        List<BtcV4Study.Params> grid = new ArrayList<>();
        double[][] ladders = {{2000, 3000, 5000}, {2000, 2000, 3000, 3000}, {1500, 1500, 2000, 2000, 3000}};
        for (boolean f : new boolean[]{true, false})
            for (double a : new double[]{0, 0.1, 0.25})
                for (double s : new double[]{0.5, 0.75, 1.0})
                    for (double[] l : ladders)
                        for (String tp : new String[]{"ATR", "FIX"})
                            for (int d : new int[]{7, 14})
                                grid.add(new BtcV4Study.Params(a, s, l, tp, d, f));
        List<Row> rows = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
            List<Future<BtcV4Study.Run>> fa = new ArrayList<>(), fb = new ArrayList<>();
            for (BtcV4Study.Params v : grid) {
                fa.add(pool.submit(() -> BtcV4Study.run(v.label(), daily, bars, tradeFrom, v, base, BtcGridStudy.filterContext(base), d -> true)));
                fb.add(pool.submit(() -> BtcV4Study.run(v.label(), daily, bars, oneYear, v, base, BtcGridStudy.filterContext(base), d -> true)));
            }
            for (int i = 0; i < grid.size(); i++) rows.add(new Row(grid.get(i), fa.get(i).get(), fb.get(i).get()));
        }

        StringBuilder md = new StringBuilder();
        md.append("# BTC 每日短线 V4 候选：ATR 阶梯挂单 + 跨天加仓（研究）\n\n");
        md.append(String.format(Locale.ROOT, "数据：data.binance.vision BTCUSDT 日线 + 15m，%s ～ %s（%.1f 年）；近 1 年从 %s 起。不止损，只在含费均价以上卖；手续费 %.2f%%，滑点 %d bp。"
                + "月度覆盖率 = 有盈利平仓的月份 / 完整月份数（全期 %s ～ %s，近 1 年 %s ～ %s）。\n\n",
                tradeFrom, lastDay, years, oneYear, base.feeRate() * 100, base.slippageBps(), YearMonth.from(tradeFrom),
                YearMonth.from(covEnd), YearMonth.from(recentCovFrom), YearMonth.from(covEnd)));
        md.append("## 1. 现行规则（V2：ks0.25 kw0.60 档距0.5×ATR 止盈 max(0.5ATR,0.8%)，只当天挂单，过滤开）\n\n");
        md.append(String.format(Locale.ROOT, "全期：%d 轮，合计 %.0f U（已实现 %.0f，期末浮动 %.0f），最大回撤 %.0f U，最长持仓 %d 天，年均 %.1f 轮，**月度覆盖率 %.1f%%**；近 1 年：%d 轮，合计 %.0f U，回撤 %.0f U，覆盖率 %.1f%%。\n\n",
                cur.rounds(), cur.total(), cur.realized(), cur.endUnrealized(), cur.maxDd(), cur.maxHoldDays(), cur.rounds() / years, curCov,
                curR.rounds(), curR.total(), curR.maxDd(), curCovR));

        rows.sort(Comparator.comparingDouble((Row r) -> -BtcV4Study.monthCoverage(r.full().winMonths(), tradeFrom, covEnd))
                .thenComparingDouble(r -> -r.full().total()));
        md.append("## 2. 网格（按全期月度覆盖率排序，前 40）\n\n");
        md.append("| 方案 | 覆盖率 全期 | 覆盖率 近1年 | 年均轮数 | 合计 U | 期末浮动 | 最大回撤 | 最长持仓天 | 近1年合计 | 近1年回撤 | 上涨 | 震荡 | 下跌 | 暴跌 | 暴跌后反弹 |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---|\n");
        for (int i = 0; i < Math.min(40, rows.size()); i++) appendRow(md, rows.get(i), tradeFrom, covEnd, recentCovFrom, years);

        // 发布前检查
        List<Row> passed = new ArrayList<>(), coverOnly = new ArrayList<>();
        for (Row r : rows) {
            double cov = BtcV4Study.monthCoverage(r.full().winMonths(), tradeFrom, covEnd);
            double covR = BtcV4Study.monthCoverage(r.recent().winMonths(), recentCovFrom, covEnd);
            boolean better = r.full().total() > cur.total() && r.recent().total() > curR.total() && cov > curCov && covR >= curCovR;
            boolean dd = r.full().maxDd() <= cur.maxDd() * 1.10 && r.recent().maxDd() <= Math.max(curR.maxDd() * 1.10, 1);
            boolean cats = catsOk(r.full().cat(), cur.cat()) && catsOk(r.recent().cat(), curR.cat());
            if (better && dd && cats) passed.add(r);
            else if (cov >= curCov + 15 && cats) coverOnly.add(r);
        }
        md.append("\n## 3. 发布前检查\n\n条件（全期和近 1 年都要满足）：合计盈亏高于现行；月度覆盖率不低于现行（全期严格更高）；最大回撤（含浮亏）≤ 现行 × 1.10；五类行情每类盈亏不低于现行减 200 U。之后再看随机基准。\n\n");
        md.append("通过：" + passed.size() + " 组。\n\n");
        for (Row r : passed.subList(0, Math.min(10, passed.size()))) appendLine(md, r, tradeFrom, covEnd, recentCovFrom);
        md.append("\n覆盖率比现行高 15 个百分点以上、各类行情不差，但没通过收益或回撤条件（需要用户决定）：" + coverOnly.size() + " 组，前 10：\n\n");
        for (Row r : coverOnly.subList(0, Math.min(10, coverOnly.size()))) appendLine(md, r, tradeFrom, covEnd, recentCovFrom);

        // 随机基准：前 3 个通过的 + 覆盖率最高的 2 个
        List<Row> cmp = new ArrayList<>(passed.subList(0, Math.min(3, passed.size())));
        for (Row r : rows) {
            if (cmp.size() >= Math.min(3, passed.size()) + 2) break;
            if (!cmp.contains(r)) cmp.add(r);
        }
        md.append("\n## 4. 随机基准（同期；随机日开盘即买第一单，之后同样的 ATR 阶梯加仓和卖法，不止损）\n\n");
        md.append("开新一轮的概率 q = 方案开轮数 / 空仓天数；随机 " + seeds + " 次，区间为 2.5%～97.5% 分位。每轮均值：方案用 t 区间，随机用各次均值的分位。\n\n");
        md.append("| 方案 | 合计 U | 随机合计均值（95%） | 合计分位 | 覆盖率 | 随机覆盖率均值（95%） | 每轮均值（95%） | 随机每轮均值（95%） |\n|---|---:|---|---:|---:|---|---|---|\n");
        for (Row r : cmp) {
            BtcV4Study.Run x = r.full();
            int flat = Math.max(1, x.days() - x.heldDays() - x.skipped());
            double q = Math.min(1, (x.rounds() + 1.0) / flat);
            BtcV4Study.Params rp = new BtcV4Study.Params(0, r.p().stepAtr(), r.p().tranches(), r.p().tpMode(),
                    r.p().lowerAfterDays(), r.p().filters());
            double[] totals = new double[seeds], covs = new double[seeds], means = new double[seeds];
            try (ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
                List<Future<BtcV4Study.Run>> fs = new ArrayList<>();
                for (int s = 0; s < seeds; s++) {
                    long seed = 2000L + s;
                    fs.add(pool.submit(() -> BtcV4Study.run("rnd", daily, bars, tradeFrom, rp, base,
                            BtcGridStudy.filterContext(base), BtcGridStudy.randomDays(q, seed))));
                }
                for (int s = 0; s < seeds; s++) {
                    BtcV4Study.Run y = fs.get(s).get();
                    totals[s] = y.total();
                    covs[s] = BtcV4Study.monthCoverage(y.winMonths(), tradeFrom, covEnd);
                    means[s] = y.roundPnls().length == 0 ? 0 : Arrays.stream(y.roundPnls()).average().orElse(0);
                }
            }
            Arrays.sort(totals);
            Arrays.sort(covs);
            Arrays.sort(means);
            int below = 0;
            for (double t : totals) if (t < x.total()) below++;
            double[] ci = BtcGridStudyMain.tci(x.roundPnls());
            md.append(String.format(Locale.ROOT, "| %s | %.0f | %.0f（%.0f ～ %.0f） | %.0f%% | %.1f%% | %.1f%%（%.1f ～ %.1f） | %.1f（%.1f ～ %.1f） | %.1f（%.1f ～ %.1f） |\n",
                    r.p().label(), x.total(), avg(totals), BtcGridStudyMain.q(totals, 0.025), BtcGridStudyMain.q(totals, 0.975),
                    100.0 * below / seeds, BtcV4Study.monthCoverage(x.winMonths(), tradeFrom, covEnd), avg(covs),
                    BtcGridStudyMain.q(covs, 0.025), BtcGridStudyMain.q(covs, 0.975), ci[0], ci[1], ci[2], avg(means),
                    BtcGridStudyMain.q(means, 0.025), BtcGridStudyMain.q(means, 0.975)));
        }
        // 最佳候选按年
        if (!cmp.isEmpty()) {
            Row b = cmp.getFirst();
            md.append("\n## 5. " + b.p().label() + " 按年（完成的轮）\n\n| 年 | 轮数 | 已实现 U | 现行轮数 | 现行已实现 |\n|---|---:|---:|---:|---:|\n");
            for (var e : b.full().byYear().entrySet()) {
                double[] c = cur.byYear().getOrDefault(e.getKey(), new double[2]);
                md.append(String.format(Locale.ROOT, "| %d | %.0f | %.0f | %.0f | %.0f |\n", e.getKey(), e.getValue()[0], e.getValue()[1], c[0], c[1]));
            }
        }
        Files.writeString(outDir.resolve("report.md"), md.toString());
        System.out.println(md);
        System.exit(0);
    }

    static double avg(double[] x) {
        return Arrays.stream(x).average().orElse(0);
    }

    static boolean catsOk(java.util.Map<String, double[]> a, java.util.Map<String, double[]> cur) {
        for (String c : CATS) if (a.get(c)[1] < cur.get(c)[1] - 200) return false;
        return true;
    }

    static void appendRow(StringBuilder md, Row r, LocalDate from, LocalDate covEnd, LocalDate recentFrom, double years) {
        BtcV4Study.Run a = r.full(), b = r.recent();
        md.append(String.format(Locale.ROOT, "| %s | %.1f%% | %.1f%% | %.1f | %.0f | %.0f | %.0f | %d | %.0f | %.0f |", r.p().label(),
                BtcV4Study.monthCoverage(a.winMonths(), from, covEnd), BtcV4Study.monthCoverage(b.winMonths(), recentFrom, covEnd),
                a.rounds() / years, a.total(), a.endUnrealized(), a.maxDd(), a.maxHoldDays(), b.total(), b.maxDd()));
        for (String c : CATS) {
            double[] v = a.cat().get(c);
            md.append(String.format(Locale.ROOT, " %.0f轮 %.0fU |", v[0], v[1]));
        }
        md.append("\n");
    }

    static void appendLine(StringBuilder md, Row r, LocalDate from, LocalDate covEnd, LocalDate recentFrom) {
        md.append(String.format(Locale.ROOT, "- %s：覆盖率 %.1f%% / 近1年 %.1f%%，合计 %.0f U / 近1年 %.0f U，回撤 %.0f / 近1年 %.0f，最长持仓 %d 天\n",
                r.p().label(), BtcV4Study.monthCoverage(r.full().winMonths(), from, covEnd),
                BtcV4Study.monthCoverage(r.recent().winMonths(), recentFrom, covEnd), r.full().total(), r.recent().total(),
                r.full().maxDd(), r.recent().maxDd(), r.full().maxHoldDays()));
    }
}
