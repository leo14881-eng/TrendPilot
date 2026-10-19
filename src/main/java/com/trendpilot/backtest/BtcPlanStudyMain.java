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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.trendpilot.btcdaily.BtcDailyProperties;
import com.trendpilot.btcdaily.BtcDailyRules;
import com.trendpilot.btcdaily.BtcGridStudy;
import com.trendpilot.btcdaily.BtcPlanStudy;
import com.trendpilot.btcdaily.BtcPlanStudy.Cfg;
import com.trendpilot.btcdaily.EqTracker;
import com.trendpilot.market.Kline;

/**
 * BTC 短线方案重研（研究，不改线上规则）：F1 现行 V2 基线，F2 ATR 阶梯 + 大止盈，F3 摆动区间，F4 趋势回调，F5 月度均值回归。
 * 前 2/3 时间校准选参，后 1/3 验证；同期随机基准；月度波动统计。
 * 参数：数据目录 输出目录 [起始月 结束月 最后一天 回测开始日 随机次数]
 */
public final class BtcPlanStudyMain {

    private BtcPlanStudyMain() {
    }

    static final String[] CATS = {"上涨", "震荡", "下跌", "暴跌", "暴跌后反弹"};

    public static void main(String[] args) throws Exception {
        Path outDir = Path.of(args.length > 1 ? args[1] : "backtest-out");
        Files.createDirectories(outDir);
        try {
            run(args, outDir);
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            Files.writeString(outDir.resolve("report.md"), "# 运行出错\n\n```\n" + sw + "\n```\n");
            System.out.println(sw);
        }
        System.exit(0);
    }

    record Sel(String why, Cfg cfg, BtcPlanStudy.Run cal) {
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
        System.out.println("日线 " + daily.size() + " 根，15m " + bars.size() + " 根");
        long span = lastDay.toEpochDay() - t0.toEpochDay();
        LocalDate vStart = LocalDate.ofEpochDay(t0.toEpochDay() + span * 2 / 3).withDayOfMonth(1);
        LocalDate cEnd = vStart.minusDays(1);
        LocalDate covEnd = lastDay.withDayOfMonth(1).minusDays(1);
        double yCal = (cEnd.toEpochDay() - t0.toEpochDay() + 1) / 365.25;
        double yVal = (lastDay.toEpochDay() - vStart.toEpochDay() + 1) / 365.25;
        BtcDailyProperties base = BtcDailyProperties.defaults();
        BtcPlanStudy.Days dd = new BtcPlanStudy.Days(daily, base);
        long vStartMs = vStart.toEpochDay() * 86_400_000L;
        List<Kline> barsCal = bars.stream().filter(b -> b.openTime() < vStartMs).toList();

        StringBuilder md = new StringBuilder();
        md.append("# BTC 短线方案重研（研究，不改线上规则）\n\n");
        md.append(String.format(Locale.ROOT, "数据：data.binance.vision BTCUSDT 日线 + 15m。校准期 %s ～ %s（%.1f 年），验证期 %s ～ %s（%.1f 年）。"
                + "全部只做现货、不止损（只在含费均价 +0.3%% 以上卖）、手续费 %.2f%%、滑点 %d bp；资金 10000 U（另报 20000 / 50000 作参考）。"
                + "月度盈利覆盖率 = 有盈利平仓的完整月份占比。年化 = （已实现 + 期末浮动）/ 资金 / 年数。\n\n",
                t0, cEnd, yCal, vStart, lastDay, yVal, base.feeRate() * 100, base.slippageBps()));

        // A. 月度波动
        monthly(md, daily, t0, covEnd);

        // F1 基线
        BtcDailyProperties curP = BtcGridStudy.params(base, 0.25, 0.6, 0.5, 0.5, true);
        BtcGridStudy.Run f1Cal = BtcGridStudy.run("F1", daily, barsCal, t0, curP, BtcGridStudy.filterContext(curP));
        BtcGridStudy.Run f1Val = BtcGridStudy.run("F1", daily, bars, vStart, curP, BtcGridStudy.filterContext(curP));
        double f1CovCal = BtcPlanStudy.coverage(f1Cal.winMonths(), t0, cEnd);
        double f1CovVal = BtcPlanStudy.coverage(f1Val.winMonths(), vStart, covEnd);

        // 网格
        Map<String, List<Cfg>> grids = new LinkedHashMap<>();
        grids.put("F2", grid("F2", new double[][]{{0}, {0.1}, {0.25}}, false));
        List<double[]> f3 = new ArrayList<>();
        for (double n : new double[]{10, 20, 30}) for (double k : new double[]{2, 3, 4}) f3.add(new double[]{n, k});
        grids.put("F3", grid("F3", f3.toArray(new double[0][]), true));
        List<double[]> f4 = new ArrayList<>();
        for (double m : new double[]{50, 100}) for (double x : new double[]{0.5, 1.0}) f4.add(new double[]{m, x});
        grids.put("F4", grid("F4", f4.toArray(new double[0][]), false));
        grids.put("F5", grid("F5", new double[][]{{5}, {8}, {10}}, false));

        List<Sel> sels = new ArrayList<>();
        md.append("\n## 2. 各类方案校准（前 2/3），每类选 2～3 个：A = 覆盖率不低于 F1 时年化最高；B = 年化 > 0 时覆盖率最高；C = U 计盈利时按 BTC 计权益增长最多\n\n");
        md.append(String.format(Locale.ROOT, "F1 现行（校准期）：%d 轮，年化 %.1f%%，覆盖率 %.1f%%，最大回撤 %.0f U，最长持仓 %d 天。\n\n",
                f1Cal.rounds(), f1Cal.total() / 10_000 / yCal * 100, f1CovCal, f1Cal.maxDd(), f1Cal.maxHoldDays()));
        for (var e : grids.entrySet()) {
            List<BtcPlanStudy.Run> rs = runAll(e.getValue(), dd, barsCal, t0, cEnd, base);
            int bestA = -1, bestB = -1, bestC = -1;
            for (int i = 0; i < rs.size(); i++) {
                BtcPlanStudy.Run r = rs.get(i);
                double cov = BtcPlanStudy.coverage(r.winMonths(), t0, cEnd), ann = r.total() / 10_000 / yCal;
                if (cov >= f1CovCal && (bestA < 0 || ann > rs.get(bestA).total() / 10_000 / yCal)) bestA = i;
                if (ann > 0 && (bestB < 0 || cov > BtcPlanStudy.coverage(rs.get(bestB).winMonths(), t0, cEnd)
                        || (cov == BtcPlanStudy.coverage(rs.get(bestB).winMonths(), t0, cEnd) && r.total() > rs.get(bestB).total()))) bestB = i;
            }
            for (int i = 0; i < rs.size(); i++) {
                BtcPlanStudy.Run r = rs.get(i);
                if (r.total() > 0 && (bestC < 0 || r.eq().btcChangePct() > rs.get(bestC).eq().btcChangePct())) bestC = i;
            }
            if (bestA < 0) {
                for (int i = 0; i < rs.size(); i++) if (bestA < 0 || rs.get(i).total() > rs.get(bestA).total()) bestA = i;
            }
            md.append("### " + e.getKey() + "（" + e.getValue().size() + " 组）\n\n");
            md.append(head());
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < rs.size(); i++) order.add(i);
            order.sort(Comparator.comparingDouble(i -> -rs.get(i).total()));
            for (int i = 0; i < Math.min(8, order.size()); i++) md.append(row(rs.get(order.get(i)), t0, cEnd, yCal, 10_000));
            md.append("\n");
            sels.add(new Sel(e.getKey() + "-A", e.getValue().get(bestA), rs.get(bestA)));
            if (bestB >= 0 && bestB != bestA) sels.add(new Sel(e.getKey() + "-B", e.getValue().get(bestB), rs.get(bestB)));
            if (bestC >= 0 && bestC != bestA && bestC != bestB) sels.add(new Sel(e.getKey() + "-C", e.getValue().get(bestC), rs.get(bestC)));
        }

        // 验证
        md.append("\n## 3. 验证期（后 1/3，只看一次）\n\n");
        md.append(head());
        md.append(String.format(Locale.ROOT, "| F1 现行 V2 | %d | %.1f | %.1f%% | %.1f%% | %.0f | %.0f | %d | %.2f%% | — | — |", f1Val.rounds(),
                f1Val.rounds() / yVal, f1Val.total() / 10_000 / yVal * 100, f1CovVal, f1Val.total(), f1Val.maxDd(), f1Val.maxHoldDays(),
                mean(f1Val.roundPcts())));
        for (String c : CATS) md.append(String.format(Locale.ROOT, " %.0fU |", f1Val.cat().get(c)[1]));
        md.append("\n");
        List<BtcPlanStudy.Run> vals = new ArrayList<>();
        for (Sel s : sels) {
            BtcPlanStudy.Run v = BtcPlanStudy.run(s.cfg(), dd, bars, vStart, lastDay, base, d -> true);
            vals.add(v);
            md.append(row(v, vStart, covEnd, yVal, 10_000).replaceFirst("\\| ", "| " + s.why() + " "));
        }

        // 随机基准 + 发布前检查
        md.append("\n## 4. 验证期随机基准（随机日开盘买第一单，同样加仓和卖法，" + seeds + " 次）与发布前检查\n\n");
        md.append("差值 = 方案 − 随机（2.5%～97.5% 分位）。发布前检查（对比 F1 验证期）：合计更高、覆盖率不低于 F1、五类行情每类不低于 F1 − 200 U、合计差值区间下限 > 0；并且 U 计和 BTC 计都不差于每月定投和随机均值。\n\n");
        md.append("| 方案 | 合计 U | 随机合计均值 | 合计差值 95% | 覆盖率 | 随机覆盖率均值 | 覆盖率差值 95% | 每轮%（95%） | 回撤 vs F1 | 结论 |\n|---|---:|---:|---|---:|---:|---|---|---|---|\n");
        List<Integer> passed = new ArrayList<>();
        Map<Integer, double[]> rndBtc = new LinkedHashMap<>();
        EqTracker dcaV = bench(daily, vStart, lastDay, 10_000, "DCA");
        for (int i = 0; i < sels.size(); i++) {
            Sel s = sels.get(i);
            BtcPlanStudy.Run v = vals.get(i);
            int flat = Math.max(1, v.days() - v.heldDays());
            double q = Math.min(1, (v.opened() + 0.5) / flat);
            Cfg rc = s.cfg().asRandom();
            double[] tot = new double[seeds], cov = new double[seeds], btc = new double[seeds];
            try (ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
                List<Future<BtcPlanStudy.Run>> fs = new ArrayList<>();
                for (int k = 0; k < seeds; k++) {
                    long seed = 5000L + k;
                    fs.add(pool.submit(() -> BtcPlanStudy.run(rc, dd, bars, vStart, lastDay, base, BtcGridStudy.randomDays(q, seed))));
                }
                for (int k = 0; k < seeds; k++) {
                    BtcPlanStudy.Run y = fs.get(k).get();
                    tot[k] = v.total() - y.total();
                    btc[k] = v.eq().btcChangePct() - y.eq().btcChangePct();
                    cov[k] = BtcPlanStudy.coverage(v.winMonths(), vStart, covEnd) - BtcPlanStudy.coverage(y.winMonths(), vStart, covEnd);
                }
            }
            Arrays.sort(tot);
            Arrays.sort(cov);
            Arrays.sort(btc);
            rndBtc.put(i, new double[]{v.eq().btcChangePct() - avg(btc), BtcGridStudyMain.q(btc, 0.025), BtcGridStudyMain.q(btc, 0.975), v.total() - avg(tot)});
            double vc = BtcPlanStudy.coverage(v.winMonths(), vStart, covEnd);
            boolean catsOk = true;
            for (String c : CATS) if (v.cat().get(c)[1] < f1Val.cat().get(c)[1] - 200) catsOk = false;
            boolean ddOk = v.maxDd() <= f1Val.maxDd() * 1.10;
            boolean btcOk = v.eq().btcChangePct() >= dcaV.btcChangePct() && BtcGridStudyMain.q(btc, 0.025) > -1e9 && v.eq().btcChangePct() >= v.eq().btcChangePct() - avg(btc)
                    && v.total() >= dcaV.lastEq - 10_000;
            // 用户 2026-10-20：中途浮亏和被套只作参考，不作否决（回撤照常报告）
            boolean ok = v.total() > f1Val.total() && vc >= f1CovVal && catsOk && BtcGridStudyMain.q(tot, 0.025) > 0 && btcOk;
            if (ok) passed.add(i);
            String verdict = ok ? (ddOk ? "通过" : "通过（回撤超过 F1×1.1，按用户要求只作参考）") : !btcOk && v.total() > f1Val.total() ? "U 计更好，但 BTC 计或对定投不占优" : (!ddOk && v.total() > f1Val.total() && vc >= f1CovVal && BtcGridStudyMain.q(tot, 0.025) > 0
                    ? "回撤超标，收益/覆盖率更好（待用户决定）" : "不通过");
            double[] ci = BtcGridStudyMain.tci(v.roundPcts());
            md.append(String.format(Locale.ROOT, "| %s %s | %.0f | %.0f | %.0f ～ %.0f | %.1f%% | %.1f%% | %.1f ～ %.1f | %.2f（%.2f ～ %.2f） | %.0f / %.0f | %s |\n",
                    s.why(), s.cfg().label(), v.total(), v.total() - avg(tot), BtcGridStudyMain.q(tot, 0.025), BtcGridStudyMain.q(tot, 0.975),
                    vc, vc - avg(cov), BtcGridStudyMain.q(cov, 0.025), BtcGridStudyMain.q(cov, 0.975), ci[0], ci[1], ci[2],
                    v.maxDd(), f1Val.maxDd(), verdict));
        }

        md.append("\n## 4b. U 计与 BTC 计（验证期 " + vStart + " ～ " + lastDay + "）\n\n");
        md.append("BTC 计 = 期末权益（U + 持有 BTC 折 U）÷ 期末价格，相对期初 10000 U 全买 BTC 的数量的变化。阶段按季度 BTC 涨跌：≥ +20% 大涨、≤ −20% 大跌、其余震荡。\n\n");
        md.append(btcHead());
        md.append(btcRow("一直拿 USDT", 0, bench(daily, vStart, lastDay, 10_000, "HOLD"), 0));
        md.append(btcRow("期初一次全买 BTC", Double.NaN, bench(daily, vStart, lastDay, 10_000, "ALL"), Double.NaN));
        md.append(btcRow("每月定投（同额）", Double.NaN, dcaV, Double.NaN));
        md.append(btcRow("F1 现行 V2", f1Val.total(), f1Val.eq(), Double.NaN));
        for (int i = 0; i < sels.size(); i++) {
            double[] rb = rndBtc.get(i);
            md.append(btcRow(sels.get(i).why() + " " + sels.get(i).cfg().label(), vals.get(i).total(), vals.get(i).eq(), vals.get(i).endQty()));
            md.append(String.format(Locale.ROOT, "| 　└ 同样卖法的随机基准（均值） | %.0f | — | %.2f%% | — | — | — | — |\n", rb[3], rb[0]));
        }

        // 资金档位、满额后用利润加仓对比（每类 A）
        md.append("\n## 5. 参考：资金 10000 / 20000 / 50000 U，满额后停 vs 用已实现利润继续加（全期 " + t0 + " ～ " + lastDay + "，只写报告不上线）\n\n");
        md.append(head());
        double yAll = (lastDay.toEpochDay() - t0.toEpochDay() + 1) / 365.25;
        md.append(String.format(Locale.ROOT, "（F1 现行全期见第 1 节月度表下方说明）\n"));
        for (Sel s : sels) {
            if (!s.why().endsWith("-A")) continue;
            for (double cap : new double[]{10_000, 20_000, 50_000}) {
                for (String after : new String[]{"STOP", "PROFIT"}) {
                    Cfg c = new Cfg(s.cfg().family(), s.cfg().e1(), s.cfg().e2(), s.cfg().levels(), s.cfg().spacing(), s.cfg().amounts(),
                            after, s.cfg().exit(), s.cfg().exitArg(), s.cfg().trailGive(), s.cfg().timeoutDays(), cap);
                    BtcPlanStudy.Run r = BtcPlanStudy.run(c, dd, bars, t0, lastDay, base, d -> true);
                    md.append(row(r, t0, covEnd, yAll, cap));
                }
            }
        }
        BtcGridStudy.Run f1All = BtcGridStudy.run("F1", daily, bars, t0, curP, BtcGridStudy.filterContext(curP));
        md.append("\n### 全期 U 计与 BTC 计\n\n");
        md.append(btcHead());
        md.append(btcRow("一直拿 USDT", 0, bench(daily, t0, lastDay, 10_000, "HOLD"), 0));
        md.append(btcRow("期初一次全买 BTC", Double.NaN, bench(daily, t0, lastDay, 10_000, "ALL"), Double.NaN));
        md.append(btcRow("每月定投（同额）", Double.NaN, bench(daily, t0, lastDay, 10_000, "DCA"), Double.NaN));
        md.append(btcRow("F1 现行 V2", f1All.total(), f1All.eq(), Double.NaN));
        for (Sel s : sels) {
            BtcPlanStudy.Run r = BtcPlanStudy.run(s.cfg(), dd, bars, t0, lastDay, base, d -> true);
            md.append(btcRow(s.why() + " " + s.cfg().label(), r.total(), r.eq(), r.endQty()));
        }
        md.append(String.format(Locale.ROOT, "\nF1 现行全期：%d 轮，年化 %.1f%%，覆盖率 %.1f%%，合计 %.0f U，最大回撤 %.0f U，最长持仓 %d 天。\n",
                f1All.rounds(), f1All.total() / 10_000 / yAll * 100, BtcPlanStudy.coverage(f1All.winMonths(), t0, covEnd),
                f1All.total(), f1All.maxDd(), f1All.maxHoldDays()));
        // 滚动 4 年窗口（一个减半周期）：主要口径
        LocalDate[][] wins = {{LocalDate.of(2020, 1, 1), LocalDate.of(2023, 12, 31)}, {LocalDate.of(2021, 1, 1), LocalDate.of(2024, 12, 31)},
                {LocalDate.of(2022, 1, 1), LocalDate.of(2025, 12, 31)}, {lastDay.minusYears(4).plusDays(1), lastDay}};
        md.append("\n## 5b. 滚动 4 年窗口（主要口径；中途浮亏、被套只作参考）\n\n注意：前三个窗口与校准期重叠，属于样本内；只有第 4 个窗口的后 1/3 是验证期。\n\n");
        md.append("| 窗口 | 方案 | U 计合计 | 年化 | BTC 计权益变化 | 覆盖率 | 最大回撤 | 最长持仓天 | U 计 ≥ 定投 | BTC 计 ≥ 定投 |\n|---|---|---:|---:|---:|---:|---:|---:|---|---|\n");
        Map<String, int[]> winScore = new LinkedHashMap<>();
        for (LocalDate[] w : wins) {
            double yw = (w[1].toEpochDay() - w[0].toEpochDay() + 1) / 365.25;
            LocalDate wCov = w[1].plusDays(1).withDayOfMonth(1).minusDays(1);
            if (wCov.isAfter(covEnd)) wCov = covEnd;
            EqTracker dca = bench(daily, w[0], w[1], 10_000, "DCA");
            EqTracker all = bench(daily, w[0], w[1], 10_000, "ALL");
            String wl = w[0] + " ～ " + w[1];
            md.append(String.format(Locale.ROOT, "| %s | 每月定投 | %.0f | %.1f%% | %.2f%% | — | — | — | — | — |\n", wl, dca.lastEq - 10_000,
                    (dca.lastEq - 10_000) / 10_000 / yw * 100, dca.btcChangePct()));
            md.append(String.format(Locale.ROOT, "| %s | 期初全买 | %.0f | %.1f%% | %.2f%% | — | — | — | — | — |\n", wl, all.lastEq - 10_000,
                    (all.lastEq - 10_000) / 10_000 / yw * 100, all.btcChangePct()));
            long wEndMs = w[1].plusDays(1).toEpochDay() * 86_400_000L;
            List<Kline> wb = bars.stream().filter(b -> b.openTime() < wEndMs).toList();
            BtcGridStudy.Run f1w = BtcGridStudy.run("F1", daily, wb, w[0], curP, BtcGridStudy.filterContext(curP));
            md.append(String.format(Locale.ROOT, "| %s | F1 现行 V2 | %.0f | %.1f%% | %.2f%% | %.1f%% | %.0f | %d | %s | %s |\n", wl, f1w.total(),
                    f1w.total() / 10_000 / yw * 100, f1w.eq().btcChangePct(), BtcPlanStudy.coverage(f1w.winMonths(), w[0], wCov),
                    f1w.maxDd(), f1w.maxHoldDays(), f1w.total() >= dca.lastEq - 10_000 ? "是" : "否",
                    f1w.eq().btcChangePct() >= dca.btcChangePct() ? "是" : "否"));
            for (Sel sl : sels) {
                BtcPlanStudy.Run x = BtcPlanStudy.run(sl.cfg(), dd, bars, w[0], w[1], base, d -> true);
                boolean u = x.total() >= dca.lastEq - 10_000, b = x.eq().btcChangePct() >= dca.btcChangePct();
                int[] sc = winScore.computeIfAbsent(sl.why(), k -> new int[2]);
                if (u) sc[0]++;
                if (b) sc[1]++;
                md.append(String.format(Locale.ROOT, "| %s | %s %s | %.0f | %.1f%% | %.2f%% | %.1f%% | %.0f | %d | %s | %s |\n", wl, sl.why(),
                        sl.cfg().label(), x.total(), x.total() / 10_000 / yw * 100, x.eq().btcChangePct(),
                        BtcPlanStudy.coverage(x.winMonths(), w[0], wCov), x.maxDd(), x.maxHoldDays(), u ? "是" : "否", b ? "是" : "否"));
            }
        }
        md.append("\n4 个窗口里不差于每月定投的次数（U 计 / BTC 计）：");
        for (var e : winScore.entrySet()) md.append(e.getKey()).append(" ").append(e.getValue()[0]).append("/").append(e.getValue()[1]).append("；");
        md.append("\n");
        md.append("\n## 6. 结论\n\n通过全部发布前检查：" + (passed.isEmpty() ? "无" : passed.stream().map(i -> sels.get(i).why() + " " + sels.get(i).cfg().label()).toList()) + "\n");
        Files.writeString(outDir.resolve("report.md"), md.toString());
        System.out.println(md);
    }

    /** 公共加仓 / 卖出组合。entries：每类的入场参数（e1[, e2]）。 */
    static List<Cfg> grid(String fam, double[][] entries, boolean withHH) {
        List<Cfg> out = new ArrayList<>();
        List<Object[]> exits = new ArrayList<>();
        exits.add(new Object[]{"BASE", 0.0, 0.0});
        for (double m : new double[]{1, 2, 3}) exits.add(new Object[]{"ATR", m, 0.0});
        for (double t : new double[]{5, 8, 10}) exits.add(new Object[]{"FIX", t, 0.0});
        for (double act : new double[]{-1, 3}) for (double g : new double[]{1, 2}) exits.add(new Object[]{"TRAIL", act, g});
        if (withHH) exits.add(new Object[]{"HH", 0.0, 0.0});
        for (double[] e : entries)
            for (int lv : new int[]{6, 10})
                for (String sp : new String[]{"C1", "INC"})
                    for (String am : new String[]{"EQ", "INC"})
                        for (String after : new String[]{"STOP", "PROFIT"})
                            for (Object[] x : exits)
                                for (int d : new int[]{0, 14, 30})
                                    out.add(new Cfg(fam, e[0], e.length > 1 ? e[1] : 0, lv, sp, am, after, (String) x[0],
                                            (double) x[1], (double) x[2], d, 10_000));
        return out;
    }

    static List<BtcPlanStudy.Run> runAll(List<Cfg> cfgs, BtcPlanStudy.Days dd, List<Kline> bars, LocalDate f, LocalDate t,
                                        BtcDailyProperties p) throws Exception {
        List<BtcPlanStudy.Run> out = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
            List<Future<BtcPlanStudy.Run>> fs = new ArrayList<>();
            for (Cfg c : cfgs) fs.add(pool.submit(() -> BtcPlanStudy.run(c, dd, bars, f, t, p, d -> true)));
            for (Future<BtcPlanStudy.Run> x : fs) out.add(x.get());
        }
        return out;
    }

    static String head() {
        return "| 方案 | 轮数 | 年均轮数 | 年化 | 覆盖率 | 合计 U | 最大回撤 | 最长持仓天 | 每轮均% | 盈亏比（均盈/最大浮亏） | 资金占用 | 上涨 | 震荡 | 下跌 | 暴跌 | 暴跌后反弹 |\n"
                + "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---|\n";
    }

    static String row(BtcPlanStudy.Run r, LocalDate f, LocalDate covEnd, double years, double cap) {
        double avgWin = r.roundPnls().length == 0 ? 0 : Arrays.stream(r.roundPnls()).average().orElse(0);
        StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "| %s | %d | %.1f | %.1f%% | %.1f%% | %.0f | %.0f | %d | %.2f%% | %.0f / %.0f = %.2f | %.0f%% |",
                r.name(), r.rounds(), r.rounds() / years, r.total() / cap / years * 100, BtcPlanStudy.coverage(r.winMonths(), f, covEnd),
                r.total(), r.maxDd(), r.maxHoldDays(), mean(r.roundPcts()), avgWin, -r.maxFloatLoss(),
                r.maxFloatLoss() == 0 ? 0 : avgWin / -r.maxFloatLoss(), r.utilization() * 100));
        for (String c : CATS) s.append(String.format(Locale.ROOT, " %.0f轮 %.0fU |", r.cat().get(c)[0], r.cat().get(c)[1]));
        return s.append("\n").toString();
    }

    static void monthly(StringBuilder md, List<Kline> daily, LocalDate f, LocalDate t) {
        Map<YearMonth, double[]> m = new LinkedHashMap<>(); // open, high, low, maxRiseFromLow, runningLow
        for (Kline k : daily) {
            LocalDate d = BtcDailyRules.dayOf(k.openTime());
            if (d.isBefore(f) || d.isAfter(t)) continue;
            double[] v = m.computeIfAbsent(YearMonth.from(d), x -> new double[]{k.open(), 0, Double.MAX_VALUE, 0, Double.MAX_VALUE});
            v[1] = Math.max(v[1], k.high());
            v[2] = Math.min(v[2], k.low());
            v[4] = Math.min(v[4], k.low());
            v[3] = Math.max(v[3], k.high() / v[4] - 1);
        }
        double[] range = m.values().stream().mapToDouble(v -> (v[1] - v[2]) / v[0] * 100).sorted().toArray();
        double[] rise = m.values().stream().mapToDouble(v -> v[3] * 100).sorted().toArray();
        List<String> low = new ArrayList<>();
        for (var e : m.entrySet()) {
            double r = (e.getValue()[1] - e.getValue()[2]) / e.getValue()[0] * 100;
            if (r < 10) low.add(String.format(Locale.ROOT, "%s（%.1f%%）", e.getKey(), r));
        }
        md.append("## 1. BTC 每月波动（" + f + " ～ " + t + "，" + m.size() + " 个月，日线口径）\n\n");
        md.append("| 指标 | 10% 分位 | 25% 分位 | 中位数 | 75% 分位 | 最小 | 最大 |\n|---|---:|---:|---:|---:|---:|---:|\n");
        md.append(String.format(Locale.ROOT, "| 月振幅（最高−最低）/月开盘 | %.1f%% | %.1f%% | %.1f%% | %.1f%% | %.1f%% | %.1f%% |\n",
                BtcGridStudyMain.q(range, 0.10), BtcGridStudyMain.q(range, 0.25), BtcGridStudyMain.q(range, 0.5), BtcGridStudyMain.q(range, 0.75), range[0], range[range.length - 1]));
        md.append(String.format(Locale.ROOT, "| 月内从低点到之后高点的最大涨幅 | %.1f%% | %.1f%% | %.1f%% | %.1f%% | %.1f%% | %.1f%% |\n\n",
                BtcGridStudyMain.q(rise, 0.10), BtcGridStudyMain.q(rise, 0.25), BtcGridStudyMain.q(rise, 0.5), BtcGridStudyMain.q(rise, 0.75), rise[0], rise[rise.length - 1]));
        md.append("振幅低于 10% 的月份 " + low.size() + " 个：" + String.join("、", low) + "\n\n");
        md.append("注：日线口径下同一天的最低价和最高价先后顺序不确定，\"低点到之后高点\"按同日也算，略偏高；能赚到多少还取决于买在哪、卖在哪。\n");
    }

    static String btcHead() {
        return "| 方案 | U 计合计 | 期末持有 BTC | BTC 计权益变化 | 大涨段 U / BTC | 大跌段 U / BTC | 震荡段 U / BTC | 期末权益 U |\n|---|---:|---:|---:|---|---|---|---:|\n";
    }

    static String btcRow(String name, double total, EqTracker e, double qty) {
        Map<String, double[]> ph = e.phases();
        StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "| %s | %.0f | %s | %.2f%% |", name,
                Double.isNaN(total) ? e.lastEq - e.cap : total, Double.isNaN(qty) ? String.format(Locale.ROOT, "%.4f", e.lastEq / e.lastPrice) : String.format(Locale.ROOT, "%.4f", qty),
                e.btcChangePct()));
        for (String k : new String[]{"大涨", "大跌", "震荡"}) {
            double[] v = ph.get(k);
            s.append(String.format(Locale.ROOT, " %.0f季 %.0fU / %+.4f |", v[0], v[1], v[2]));
        }
        return s.append(String.format(Locale.ROOT, " %.0f |\n", e.lastEq)).toString();
    }

    /** 基准：HOLD 一直拿 USDT；ALL 期初一次全买；DCA 每月第一天开盘同额买入（金额 = 资金 / 月数）。按日线记录权益。 */
    static EqTracker bench(List<Kline> daily, LocalDate f, LocalDate t, double cap, String mode) {
        EqTracker e = new EqTracker(cap);
        int months = 0;
        for (YearMonth m = YearMonth.from(f); !m.isAfter(YearMonth.from(t)); m = m.plusMonths(1)) months++;
        double cash = cap, qty = 0;
        YearMonth last = null;
        for (Kline k : daily) {
            LocalDate d = BtcDailyRules.dayOf(k.openTime());
            if (d.isBefore(f) || d.isAfter(t)) continue;
            if ("ALL".equals(mode) && qty == 0 && cash > 0) {
                qty = cash / k.open();
                cash = 0;
            }
            if ("DCA".equals(mode) && !YearMonth.from(d).equals(last)) {
                last = YearMonth.from(d);
                double amt = Math.min(cash, cap / months);
                qty += amt / k.open();
                cash -= amt;
            }
            e.on(d, k.open(), cash + qty * k.close(), k.close());
        }
        return e;
    }

    static double mean(double[] x) {
        return x.length == 0 ? 0 : Arrays.stream(x).average().orElse(0);
    }

    static double avg(double[] x) {
        return mean(x);
    }
}
