package com.trendpilot.backtest;

import java.io.PrintWriter;
import java.io.StringWriter;
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
import com.trendpilot.btcdaily.BtcPlanStudy;
import com.trendpilot.btcdaily.BtcPlanStudy.Cfg;
import com.trendpilot.btcdaily.EqTracker;
import com.trendpilot.market.Kline;

/**
 * BTC 短线卖出端研究（不改线上规则）：现行买法 / F2 阶梯加仓买法 × 大止盈、跟踪止盈、分行情跟踪。
 * 重点：牛市段捕获比例、每轮平均盈利、滚动 4 年 U 计和 BTC 计、月度覆盖率。全部方案都列出，检查结果只标注不剔除。
 * 参数：数据目录 输出目录 [起始月 结束月 最后一天 回测开始日 随机次数]
 */
public final class BtcExitStudyMain {

    private BtcExitStudyMain() {
    }

    record Res(Cfg cfg, BtcPlanStudy.Run full, BtcPlanStudy.Run val, BtcPlanStudy.Run[] wins) {
    }

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 1 ? args[1] : "backtest-out");
        Files.createDirectories(outDir);
        try {
            run(args, outDir);
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            Files.writeString(outDir.resolve("report.md"), "# 运行出错\n\n```\n" + sw + "\n```\n");
        }
        System.exit(0);
    }

    static void run(String[] args, Path outDir) throws Exception {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "backtest-data");
        YearMonth from = YearMonth.parse(args.length > 2 ? args[2] : "2019-08");
        YearMonth to = YearMonth.parse(args.length > 3 ? args[3] : "2026-09");
        LocalDate lastDay = args.length > 4 ? LocalDate.parse(args[4]) : LocalDate.of(2026, 10, 18);
        LocalDate t0 = LocalDate.parse(args.length > 5 ? args[5] : "2020-01-01");
        int seeds = args.length > 6 ? Integer.parseInt(args[6]) : 200;
        List<Kline> daily = BtcGridStudyMain.load(new Archive(dataDir.resolve("btc-1d"), "1d"), "1d", from, to, lastDay, 86_400_000L);
        List<Kline> bars = BtcGridStudyMain.load(new Archive(dataDir.resolve("btc-15m"), "15m"), "15m", from, to, lastDay, 15 * 60_000L);
        BtcDailyProperties base = BtcDailyProperties.defaults();
        BtcPlanStudy.Days dd = new BtcPlanStudy.Days(daily, base);
        LocalDate vStart = LocalDate.of(2024, 7, 1);
        LocalDate covEnd = lastDay.withDayOfMonth(1).minusDays(1);
        double yAll = (lastDay.toEpochDay() - t0.toEpochDay() + 1) / 365.25;
        LocalDate[][] wins = {{LocalDate.of(2020, 1, 1), LocalDate.of(2023, 12, 31)}, {LocalDate.of(2021, 1, 1), LocalDate.of(2024, 12, 31)},
                {LocalDate.of(2022, 1, 1), LocalDate.of(2025, 12, 31)}, {lastDay.minusYears(4).plusDays(1), lastDay}};
        LocalDate[][] bulls = {{LocalDate.of(2020, 10, 1), LocalDate.of(2021, 4, 14)}, {LocalDate.of(2023, 10, 1), LocalDate.of(2024, 3, 14)},
                {LocalDate.of(2024, 10, 1), LocalDate.of(2025, 1, 20)}};

        // 买法 × 卖法
        List<Cfg> cfgs = new ArrayList<>();
        Object[][] buys = {{"V2B", 0.0, 3, "C0.5", "V2"}, {"F2", 0.1, 6, "C1", "EQ"}, {"F2", 0.1, 6, "INC", "EQ"}};
        List<Object[]> exits = new ArrayList<>();
        exits.add(new Object[]{"BASE", 0.0, 0.0});
        for (double t : new double[]{5, 8, 10, 15, 20}) exits.add(new Object[]{"FIX", t, 0.0});
        for (double act : new double[]{3, -1}) for (double g : new double[]{1, 2, 3, -5, -8}) exits.add(new Object[]{"TRAIL", act, g});
        for (String ad : new String[]{"ADAPT50", "ADAPT100"}) for (double g : new double[]{3, -8}) exits.add(new Object[]{ad, 3.0, g});
        for (Object[] b : buys)
            for (Object[] x : exits)
                for (int d : new int[]{0, 30})
                    cfgs.add(new Cfg((String) b[0], (double) b[1], 0, (int) b[2], (String) b[3], (String) b[4], "STOP",
                            (String) x[0], (double) x[1], (double) x[2], d, 10_000));

        List<Res> res = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
            List<Future<Res>> fs = new ArrayList<>();
            for (Cfg c : cfgs) {
                fs.add(pool.submit(() -> {
                    BtcPlanStudy.Run[] w = new BtcPlanStudy.Run[wins.length];
                    for (int i = 0; i < wins.length; i++) w[i] = BtcPlanStudy.run(c, dd, bars, wins[i][0], wins[i][1], base, d -> true);
                    return new Res(c, BtcPlanStudy.run(c, dd, bars, t0, lastDay, base, d -> true),
                            BtcPlanStudy.run(c, dd, bars, vStart, lastDay, base, d -> true), w);
                }));
            }
            for (Future<Res> f : fs) res.add(f.get());
        }

        // 基线 F1（线上同一套规则）与定投
        BtcDailyProperties curP = BtcGridStudy.params(base, 0.25, 0.6, 0.5, 0.5, true);
        BtcGridStudy.Run f1 = BtcGridStudy.run("F1", daily, bars, t0, curP, BtcGridStudy.filterContext(curP));
        BtcGridStudy.Run f1v = BtcGridStudy.run("F1", daily, bars, vStart, curP, BtcGridStudy.filterContext(curP));
        double[] f1w = new double[wins.length], f1wb = new double[wins.length], dcaU = new double[wins.length], dcaB = new double[wins.length];
        for (int i = 0; i < wins.length; i++) {
            long endMs = wins[i][1].plusDays(1).toEpochDay() * 86_400_000L;
            List<Kline> wb = bars.stream().filter(b -> b.openTime() < endMs).toList();
            BtcGridStudy.Run r = BtcGridStudy.run("F1", daily, wb, wins[i][0], curP, BtcGridStudy.filterContext(curP));
            f1w[i] = r.total();
            f1wb[i] = r.eq().btcChangePct();
            EqTracker e = BtcPlanStudyMain.bench(daily, wins[i][0], wins[i][1], 10_000, "DCA");
            dcaU[i] = e.lastEq - 10_000;
            dcaB[i] = e.btcChangePct();
        }

        StringBuilder md = new StringBuilder();
        md.append("# BTC 短线卖出端研究：大止盈 / 跟踪止盈（研究，不改线上规则）\n\n");
        md.append(String.format(Locale.ROOT, "数据 %s ～ %s（%.1f 年），15m 撮合；卖出成交价始终 ≥ 含费均价 ×（1 + 手续费 + 0.3%%），出现亏损卖出会直接报错（本次 0 笔）。"
                + "资金 10000 U，只做现货，满额后不再加仓。买法：现行买法（开盘 − 0.25/0.6×ATR，档距 0.5×ATR，3000/3000/4000，下跌趋势不开新一轮）、"
                + "F2（开盘 − 0.1×ATR，6 档等额，档距 1×ATR 或递增 1/1.5/2…×ATR），加仓都跨天有效。每个组合都列出，检查结果只标注、不剔除，由用户决定。\n\n",
                t0, lastDay, yAll));
        md.append("牛市段捕获 = 该段账本权益涨幅 ÷ 同段 BTC 涨幅（100% = 等同于 10000 U 全程满仓 BTC）。4 年窗口：");
        for (LocalDate[] w : wins) md.append(w[0]).append("～").append(w[1]).append("；");
        md.append("前三个与参数挑选无关（本次没有在任何时段上选参，全部列出）。\n\n");

        md.append("## 基准\n\n| 方案 | 全期 U | 全期 BTC 计 | 每轮均% | 覆盖率 | 最大回撤 | 最长持仓 | 4 年窗口 U（4 个） | 4 年窗口 BTC 计 | 牛市段捕获 |\n|---|---:|---:|---:|---:|---:|---:|---|---|---|\n");
        StringBuilder bc = new StringBuilder();
        for (LocalDate[] b : bulls) {
            double[] s = f1.eq().segment(b[0], b[1]);
            bc.append(s == null ? "— " : String.format(Locale.ROOT, "%.0f%% ", s[2] * 100));
        }
        md.append(String.format(Locale.ROOT, "| F1 现行 V2（线上规则） | %.0f | %.1f%% | %.2f%% | %.1f%% | %.0f | %d | %s | %s | %s|\n", f1.total(),
                f1.eq().btcChangePct(), BtcPlanStudyMain.mean(f1.roundPcts()), BtcPlanStudy.coverage(f1.winMonths(), t0, covEnd),
                f1.maxDd(), f1.maxHoldDays(), join(f1w, "%.0f"), join(f1wb, "%.1f%%"), bc));
        md.append(String.format(Locale.ROOT, "| 每月定投 | — | — | — | — | — | — | %s | %s | — |\n", join(dcaU, "%.0f"), join(dcaB, "%.1f%%")));
        for (LocalDate[] b : bulls) {
            EqTracker all = BtcPlanStudyMain.bench(daily, b[0], b[1], 10_000, "ALL");
            md.append(String.format(Locale.ROOT, "\n牛市段 %s ～ %s：BTC 涨幅 %.0f%%。", b[0], b[1], all.segment(b[0], b[1])[1] * 100));
        }
        md.append("\n\n");

        // 全部组合，按 4 年窗口 U 计均值排序
        res.sort(Comparator.comparingDouble((Res r) -> -Arrays.stream(r.wins()).mapToDouble(BtcPlanStudy.Run::total).average().orElse(0)));
        md.append("## 全部组合（" + res.size() + " 个，按 4 年窗口 U 计均值排序）\n\n");
        md.append("检查标记：U↑ = 全期和验证期 U 计都高于 F1；覆盖↑ = 全期月度覆盖率不低于 F1；回撤 = 全期最大回撤 ≤ F1 × 1.1；定投 = 4 个 4 年窗口里 U 计 / BTC 计不差于定投的次数。\n\n");
        md.append("| 方案 | 全期 U | 年化 | 全期 BTC 计 | 每轮均% | 年均轮数 | 覆盖率 | 最大回撤 | 最长持仓 | 4 年 U 均值 | 4 年 BTC 计均值 | 牛市段捕获（3 段） | 验证期 U / 覆盖率 | 检查 |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|\n");
        double f1Cov = BtcPlanStudy.coverage(f1.winMonths(), t0, covEnd);
        for (Res r : res) {
            BtcPlanStudy.Run a = r.full();
            StringBuilder cap = new StringBuilder();
            for (LocalDate[] b : bulls) {
                double[] s = a.eq().segment(b[0], b[1]);
                cap.append(s == null ? "— " : String.format(Locale.ROOT, "%.0f%% ", s[2] * 100));
            }
            int du = 0, db = 0;
            for (int i = 0; i < wins.length; i++) {
                if (r.wins()[i].total() >= dcaU[i]) du++;
                if (r.wins()[i].eq().btcChangePct() >= dcaB[i]) db++;
            }
            String chk = (a.total() > f1.total() && r.val().total() > f1v.total() ? "U↑ " : "U↓ ")
                    + (BtcPlanStudy.coverage(a.winMonths(), t0, covEnd) >= f1Cov ? "覆盖↑ " : "覆盖↓ ")
                    + (a.maxDd() <= f1.maxDd() * 1.1 ? "回撤✓ " : "回撤✗ ") + "定投 " + du + "/" + db;
            md.append(String.format(Locale.ROOT, "| %s | %.0f | %.1f%% | %.1f%% | %.2f%% | %.1f | %.1f%% | %.0f | %d | %.0f | %.1f%% | %s| %.0f / %.1f%% | %s |\n",
                    a.name(), a.total(), a.total() / 10_000 / yAll * 100, a.eq().btcChangePct(), BtcPlanStudyMain.mean(a.roundPcts()),
                    a.rounds() / yAll, BtcPlanStudy.coverage(a.winMonths(), t0, covEnd), a.maxDd(), a.maxHoldDays(),
                    Arrays.stream(r.wins()).mapToDouble(BtcPlanStudy.Run::total).average().orElse(0),
                    Arrays.stream(r.wins()).mapToDouble(x -> x.eq().btcChangePct()).average().orElse(0), cap,
                    r.val().total(), BtcPlanStudy.coverage(r.val().winMonths(), vStart, covEnd), chk));
        }

        // 随机基准：4 年 U 均值前 10 + 覆盖率最高 3 个，验证期
        List<Res> cmp = new ArrayList<>(res.subList(0, Math.min(10, res.size())));
        res.stream().sorted(Comparator.comparingDouble((Res r) -> -BtcPlanStudy.coverage(r.full().winMonths(), t0, covEnd)))
                .filter(r -> !cmp.contains(r)).limit(3).forEach(cmp::add);
        md.append("\n## 随机基准（全期；随机日开盘买第一单，同样加仓和卖法，" + seeds + " 次）\n\n");
        md.append("| 方案 | 全期 U | 随机均值 | 差值 95% | BTC 计 | 随机 BTC 计均值 | 差值 95% |\n|---|---:|---:|---|---:|---:|---|\n");
        for (Res r : cmp) {
            BtcPlanStudy.Run a = r.full();
            int flat = Math.max(1, a.days() - a.heldDays());
            double q = Math.min(1, (a.opened() + 0.5) / flat);
            Cfg rc = r.cfg().asRandom();
            double[] du = new double[seeds], db = new double[seeds];
            double mu = 0, mb = 0;
            try (ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
                List<Future<BtcPlanStudy.Run>> fs = new ArrayList<>();
                for (int k = 0; k < seeds; k++) {
                    long seed = 9000L + k;
                    fs.add(pool.submit(() -> BtcPlanStudy.run(rc, dd, bars, t0, lastDay, base, BtcGridStudy.randomDays(q, seed))));
                }
                for (int k = 0; k < seeds; k++) {
                    BtcPlanStudy.Run y = fs.get(k).get();
                    du[k] = a.total() - y.total();
                    db[k] = a.eq().btcChangePct() - y.eq().btcChangePct();
                    mu += y.total() / seeds;
                    mb += y.eq().btcChangePct() / seeds;
                }
            }
            Arrays.sort(du);
            Arrays.sort(db);
            md.append(String.format(Locale.ROOT, "| %s | %.0f | %.0f | %.0f ～ %.0f | %.1f%% | %.1f%% | %.1f ～ %.1f |\n", a.name(), a.total(), mu,
                    BtcGridStudyMain.q(du, 0.025), BtcGridStudyMain.q(du, 0.975), a.eq().btcChangePct(), mb,
                    BtcGridStudyMain.q(db, 0.025), BtcGridStudyMain.q(db, 0.975)));
        }
        Files.writeString(outDir.resolve("report.md"), md.toString());
        System.out.println(md);
    }

    static String join(double[] v, String fmt) {
        StringBuilder s = new StringBuilder();
        for (double d : v) s.append(s.isEmpty() ? "" : " / ").append(String.format(Locale.ROOT, fmt, d));
        return s.toString();
    }
}
