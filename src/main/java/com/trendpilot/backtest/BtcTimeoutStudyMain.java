package com.trendpilot.backtest;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.trendpilot.btcdaily.BtcDailyProperties;
import com.trendpilot.btcdaily.BtcGridStudy;
import com.trendpilot.btcdaily.BtcPlanStudy;
import com.trendpilot.market.Kline;

/**
 * BTC 短线方案 B 的"超时降目标"对比（用户 2026-10-20 质疑"拿满 30 天成本 +0.4% 就卖太低"）。
 * 全部用线上同一套规则代码（BtcDailyRules，经 BtcGridStudy.run）：第 1 档开盘 −0.1%，档距 0.5×ATR，3000/3000/4000，
 * 均价 +3% 激活、回落 3×ATR 跟踪卖出，绝不卖亏；只改超时天数和超时后的目标。
 * 参数：数据目录 输出目录 [起始月 结束月 最后一天 回测开始日]
 */
public final class BtcTimeoutStudyMain {

    private BtcTimeoutStudyMain() {
    }

    record V(String name, BtcDailyProperties p) {
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
        List<Kline> daily = BtcGridStudyMain.load(new Archive(dataDir.resolve("btc-1d"), "1d"), "1d", from, to, lastDay, 86_400_000L);
        List<Kline> bars = BtcGridStudyMain.load(new Archive(dataDir.resolve("btc-15m"), "15m"), "15m", from, to, lastDay, 15 * 60_000L);
        BtcDailyProperties base = BtcDailyProperties.defaults();
        BtcDailyProperties cur = BtcGridStudy.params(base, 0.25, 0.6, 0.5, 0.5, true);
        BtcDailyProperties b = cur.withExitMode("TRAIL_B").withFirstOpenPct(0.1);
        List<V> vs = new ArrayList<>();
        vs.add(new V("旧线上 V2（0.25/0.6×ATR，约 1% 止盈）", cur));
        vs.add(new V("开盘 −0.1% + 方案 B + 30 天降到成本 +3%", b.withExitTimeout(30, 3.0)));
        vs.add(new V("用户选定：0.25/0.40×ATR + 方案 B + 30 天降到成本 +3%",
                BtcGridStudy.params(base, 0.25, 0.4, 0.5, 0.5, true).withExitMode("TRAIL_B").withExitTimeout(30, 3.0)));
        vs.add(new V("参考：开盘 −0.1% + 方案 B + 30 天 +0.4%", b.withExitTimeout(30, 0.3)));
        vs.add(new V("参考：0.25/0.40×ATR + 方案 B + 30 天 +0.4%",
                BtcGridStudy.params(base, 0.25, 0.4, 0.5, 0.5, true).withExitMode("TRAIL_B").withExitTimeout(30, 0.3)));

        LocalDate covEnd = lastDay.withDayOfMonth(1).minusDays(1);
        double yAll = (lastDay.toEpochDay() - t0.toEpochDay() + 1) / 365.25;
        LocalDate[][] wins = {{LocalDate.of(2020, 1, 1), LocalDate.of(2023, 12, 31)}, {LocalDate.of(2021, 1, 1), LocalDate.of(2024, 12, 31)},
                {LocalDate.of(2022, 1, 1), LocalDate.of(2025, 12, 31)}, {lastDay.minusYears(4).plusDays(1), lastDay}};

        StringBuilder md = new StringBuilder("# BTC 短线：用户选定组合（0.25/0.40×ATR + 方案 B + 30 天降到 +3%）线上规则回测\n\n");
        md.append(String.format(Locale.ROOT, "数据 %s ～ %s（%.1f 年），15m 撮合，线上同一套规则代码（BtcDailyRules）。资金 10000 U，只做现货，"
                + "第 1 档开盘 −0.1%%（旧线上一行除外），档距 0.5×ATR，3000/3000/4000，下跌趋势 / 议息日不开新一轮；"
                + "卖出：均价 +3%% 激活，从最高价回落 3×ATR 全卖；绝不卖亏（所有卖出价 ≥ 含费均价 × (1 + 手续费 + 0.3%%)）。"
                + "\"成本 +0.4%%\" = 含费均价 × (1 + 0.1%% 手续费 + 0.3%%)。4 年窗口：2020–2023、2021–2024、2022–2025、最近 4 年。\n\n", t0, lastDay, yAll));
        md.append("| 方案 | 第1档当天成交率 | 亏损卖出 | 年均轮数 | 每轮均% | 全期 U | 4 年 U（4 个窗口） | 4 年 U 均值 | 4 年 BTC 计均值 | 覆盖率 | 最大回撤 | 最长被套(天) | 期末 |\n");
        md.append("|---|---:|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|---|\n");
        for (V v : vs) {
            BtcGridStudy.Run a = BtcGridStudy.run(v.name(), daily, bars, t0, v.p(), BtcGridStudy.filterContext(v.p()));
            double[] wu = new double[wins.length], wb = new double[wins.length];
            for (int i = 0; i < wins.length; i++) {
                long endMs = wins[i][1].plusDays(1).toEpochDay() * 86_400_000L;
                List<Kline> wbars = bars.stream().filter(k -> k.openTime() < endMs).toList();
                BtcGridStudy.Run r = BtcGridStudy.run(v.name(), daily, wbars, wins[i][0], v.p(), BtcGridStudy.filterContext(v.p()));
                wu[i] = r.total();
                wb[i] = r.eq().btcChangePct();
            }
            boolean holding = Math.abs(a.endUnrealized()) > 1e-9;
            double fill = a.firstEligibleDays() == 0 ? 0 : 100.0 * a.firstFilledDays() / a.firstEligibleDays();
            md.append(String.format(Locale.ROOT, "| %s | %.1f%% | %d | %.1f | %.2f%% | %.0f | %s | %.0f | %.1f%% | %.1f%% | %.0f | %d | %s |\n",
                    v.name(), fill, a.lossSells(), a.rounds() / yAll, BtcPlanStudyMain.mean(a.roundPcts()), a.total(),
                    BtcExitStudyMain.join(wu, "%.0f"), java.util.Arrays.stream(wu).average().orElse(0),
                    java.util.Arrays.stream(wb).average().orElse(0), BtcPlanStudy.coverage(a.winMonths(), t0, covEnd),
                    a.maxDd(), a.maxHoldDays(), holding ? String.format(Locale.ROOT, "仍被套（浮动 %.0f U）", a.endUnrealized()) : "空仓"));
        }
        md.append("\n第1档当天成交率 = 开盘时空仓且当天挂了第 1 档的日子里，当天就成交的比例。亏损卖出 = 到手金额不高于成本的卖出笔数（必须 0）。\n");
        Files.writeString(outDir.resolve("report.md"), md.toString());
        System.out.println(md);
    }
}
