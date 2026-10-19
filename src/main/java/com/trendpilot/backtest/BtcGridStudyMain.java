package com.trendpilot.backtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.trendpilot.btcdaily.BtcDailyProperties;
import com.trendpilot.btcdaily.BtcGridStudy;
import com.trendpilot.market.Kline;

/**
 * BTC 每日短线挂单深度网格 + 随机基准（研究，不改线上规则）。
 * 参数：数据目录 输出目录 [起始月 结束月 最后一天 回测开始日 随机次数]
 */
public final class BtcGridStudyMain {

    private BtcGridStudyMain() {
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "backtest-data");
        Path outDir = Path.of(args.length > 1 ? args[1] : "backtest-out");
        YearMonth from = YearMonth.parse(args.length > 2 ? args[2] : "2019-08");
        YearMonth to = YearMonth.parse(args.length > 3 ? args[3] : "2026-09");
        LocalDate lastDay = args.length > 4 ? LocalDate.parse(args[4]) : LocalDate.of(2026, 10, 18);
        LocalDate tradeFrom = LocalDate.parse(args.length > 5 ? args[5] : "2020-01-01");
        int seeds = args.length > 6 ? Integer.parseInt(args[6]) : 300;
        Files.createDirectories(outDir);

        List<Kline> daily = load(new Archive(dataDir.resolve("btc-1d"), "1d"), "1d", from, to, lastDay, 86_400_000L);
        List<Kline> bars = load(new Archive(dataDir.resolve("btc-15m"), "15m"), "15m", from, to, lastDay, 15 * 60_000L);
        System.out.println("日线 " + daily.size() + " 根，15m " + bars.size() + " 根");
        LocalDate oneYear = lastDay.minusDays(365);

        BtcDailyProperties base = BtcDailyProperties.defaults();
        StringBuilder md = new StringBuilder();
        md.append("# BTC 每日短线挂单深度网格（研究）\n\n");
        md.append(String.format(Locale.ROOT, "数据：data.binance.vision BTCUSDT 日线 + 15m，%s ～ %s；线上同一套 BtcDailyRules（plan/onBar），计划日取前 119 根日线（同线上 120 根），不止损。\n\n", tradeFrom, lastDay));

        // 1. 第 1 档被触及的日频率
        md.append("## 1. 第 1 档（开盘 − k×ATR14）当天被触及的比例\n\n| k | 全部日 | 昨收>EMA20（强） | 昨收≤EMA20（弱） |\n|---|---:|---:|---:|\n");
        for (double k : new double[]{0.1, 0.25, 0.3, 0.4, 0.6, 0.8, 1.0}) {
            double[] r = BtcGridStudy.touchRate(daily, tradeFrom, k, base);
            md.append(String.format(Locale.ROOT, "| %.2f | %.1f%% | %.1f%% | %.1f%% |\n", k, r[0], r[1], r[2]));
        }
        md.append("\n");

        // 2. 网格
        List<BtcDailyProperties> grid = new ArrayList<>();
        for (boolean f : new boolean[]{true, false})
            for (double ks : new double[]{0.25, 0.1})
                for (double kw : new double[]{0.6, 0.4, 0.3})
                    for (double st : new double[]{0.5, 0.3})
                        for (double tp : new double[]{0.5, 0.35})
                            grid.add(BtcGridStudy.params(base, ks, kw, st, tp, f));
        BtcDailyProperties current = grid.getFirst();
        List<BtcGridStudy.Run> full = runAll(grid, daily, bars, tradeFrom);
        List<BtcGridStudy.Run> recent = runAll(grid, daily, bars, oneYear);
        BtcGridStudy.Run cur = full.getFirst();
        BtcGridStudy.Run curRecent = recent.getFirst();

        md.append("## 2. 现行规则（ks0.25 kw0.60 档距0.5 止盈0.5×ATR，过滤开）\n\n");
        md.append(String.format(Locale.ROOT, "全期 %d 天：完成 %d 轮，胜率 %.0f%%，已实现 %.0f U，期末浮动 %.0f U，合计 %.0f U；最大回撤（含浮亏）%.0f U，最大浮亏 %.0f U，最长持仓 %d 天；有买入的天 %d，过滤不开新一轮 %d 天；平均每月触发 %.2f 天。\n\n",
                cur.days(), cur.rounds(), pct(cur.wins(), cur.rounds()), cur.realized(), cur.endUnrealized(), cur.total(),
                cur.maxDd(), cur.maxFloatLoss(), cur.maxHoldDays(), cur.tradeDays(), cur.skipped(), cur.tradeDays() / (cur.days() / 30.44)));
        md.append("按年（完成的轮）：\n\n| 年 | 轮数 | 已实现 U |\n|---|---:|---:|\n");
        for (Map.Entry<Integer, double[]> e : cur.byYear().entrySet()) {
            md.append(String.format(Locale.ROOT, "| %d | %.0f | %.0f |\n", e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        md.append("\n");

        md.append("## 3. 网格（全期）\n\n");
        appendGrid(md, full);
        md.append("\n## 4. 网格（最近 1 年）\n\n");
        appendGrid(md, recent);

        // 5. 随机基准
        md.append("\n## 5. 随机基准（同期、同资金分批、同样卖法、不止损；随机日开盘即买第 1 档，其余档按同档距）\n\n");
        md.append("开新一轮的概率 q = 该方案有买入的天数 / 计划天数；随机 " + seeds + " 次，区间为 2.5%～97.5% 分位。每轮均值的 95% 区间：方案用 t 区间，随机用各次均值的分位。\n\n");
        md.append("| 方案 | 合计 U | 随机合计均值 | 随机合计 95% 区间 | 方案每轮均值（95% 区间） | 随机每轮均值（95% 区间） | 方案合计分位 |\n|---|---:|---:|---|---|---|---:|\n");
        List<Integer> compare = new ArrayList<>(List.of(0));
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < full.size(); i++) order.add(i);
        order.sort(Comparator.comparingDouble(i -> -full.get(i).total()));
        for (int i : order) if (compare.size() < 4 && !compare.contains(i)) compare.add(i);
        for (int i : compare) {
            BtcDailyProperties p = grid.get(i);
            BtcGridStudy.Run r = full.get(i);
            BtcDailyProperties rp = BtcGridStudy.params(p, 0, 0, p.stepAtr(), p.tpAtrMult(), p.filters().enabled());
            double q = (double) r.tradeDays() / Math.max(1, r.days());
            double[] totals = new double[seeds];
            double[] means = new double[seeds];
            List<Future<BtcGridStudy.Run>> fs = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
                for (int s = 0; s < seeds; s++) {
                    long seed = 1000L + s;
                    fs.add(pool.submit(() -> BtcGridStudy.run("rnd", daily, bars, tradeFrom, rp,
                            BtcGridStudy.randomContext(rp, q, seed))));
                }
                for (int s = 0; s < seeds; s++) {
                    BtcGridStudy.Run x = fs.get(s).get();
                    totals[s] = x.total();
                    means[s] = x.roundPnls().length == 0 ? 0 : Arrays.stream(x.roundPnls()).average().orElse(0);
                }
            }
            Arrays.sort(totals);
            Arrays.sort(means);
            double[] ci = tci(r.roundPnls());
            int below = 0;
            for (double t : totals) if (t < r.total()) below++;
            md.append(String.format(Locale.ROOT, "| %s | %.0f | %.0f | %.0f ～ %.0f | %.1f（%.1f ～ %.1f） | %.1f（%.1f ～ %.1f） | %.0f%% |\n",
                    label(p), r.total(), Arrays.stream(totals).average().orElse(0), q(totals, 0.025), q(totals, 0.975),
                    ci[0], ci[1], ci[2], Arrays.stream(means).average().orElse(0), q(means, 0.025), q(means, 0.975),
                    100.0 * below / seeds));
        }

        // 6. 发布前检查
        md.append("\n## 6. 发布前检查（对比现行，全期与最近 1 年都要满足）\n\n");
        md.append("条件：合计盈亏高于现行；最大回撤（含浮亏）不超过现行 × 1.10；五类行情（上涨/震荡/下跌/暴跌/暴跌后反弹）每类盈亏不低于现行减 200 U；全期合计高于随机基准 97.5% 分位（见第 5 节，仅前几名做了随机对比）。\n\n");
        int passed = 0;
        for (int i = 1; i < grid.size(); i++) {
            BtcGridStudy.Run a = full.get(i), b = recent.get(i);
            boolean ok = a.total() > cur.total() && b.total() > curRecent.total()
                    && a.maxDd() <= cur.maxDd() * 1.10 && b.maxDd() <= Math.max(curRecent.maxDd() * 1.10, 1)
                    && catsOk(a, cur) && catsOk(b, curRecent);
            if (ok) {
                passed++;
                md.append(String.format(Locale.ROOT, "- 通过前 5 项：%s（全期 %.0f U / 回撤 %.0f；近 1 年 %.0f U / 回撤 %.0f）\n",
                        label(grid.get(i)), a.total(), a.maxDd(), b.total(), b.maxDd()));
            }
        }
        if (passed == 0) md.append("- 没有组合同时满足：盈亏更高、回撤不超过现行 1.1 倍、各类行情不明显变差（全期和近 1 年）。\n");
        Files.writeString(outDir.resolve("report.md"), md.toString());
        System.out.println(md);
        System.exit(0);
    }

    static boolean catsOk(BtcGridStudy.Run a, BtcGridStudy.Run cur) {
        for (String c : new String[]{"上涨", "震荡", "下跌", "暴跌", "暴跌后反弹"}) {
            if (a.cat().get(c)[1] < cur.cat().get(c)[1] - 200) return false;
        }
        return true;
    }

    static List<BtcGridStudy.Run> runAll(List<BtcDailyProperties> grid, List<Kline> daily, List<Kline> bars, LocalDate from)
            throws Exception {
        List<Future<BtcGridStudy.Run>> fs = new ArrayList<>();
        List<BtcGridStudy.Run> out = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
            for (BtcDailyProperties p : grid) {
                fs.add(pool.submit(() -> BtcGridStudy.run(label(p), daily, bars, from, p,
                        p.filters().enabled() ? BtcGridStudy.filterContext(p) : null)));
            }
            for (Future<BtcGridStudy.Run> f : fs) out.add(f.get());
        }
        return out;
    }

    static void appendGrid(StringBuilder md, List<BtcGridStudy.Run> runs) {
        md.append("| 方案 | 轮数 | 胜率 | 合计 U | 已实现 | 期末浮动 | 最大回撤 | 最长持仓天 | 上涨 | 震荡 | 下跌 | 暴跌 | 暴跌后反弹 |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---|\n");
        for (BtcGridStudy.Run r : runs) {
            md.append(String.format(Locale.ROOT, "| %s | %d | %.0f%% | %.0f | %.0f | %.0f | %.0f | %d |", r.name(), r.rounds(),
                    pct(r.wins(), r.rounds()), r.total(), r.realized(), r.endUnrealized(), r.maxDd(), r.maxHoldDays()));
            for (String c : new String[]{"上涨", "震荡", "下跌", "暴跌", "暴跌后反弹"}) {
                double[] v = r.cat().get(c);
                md.append(String.format(Locale.ROOT, " %.0f轮 %.0fU |", v[0], v[1]));
            }
            md.append("\n");
        }
    }

    static String label(BtcDailyProperties p) {
        return String.format(Locale.ROOT, "ks%.2f kw%.2f 档距%.1f 止盈%.2f %s", p.kStrong(), p.kWeak(), p.stepAtr(),
                p.tpAtrMult(), p.filters().enabled() ? "过滤开" : "过滤关");
    }

    static double pct(int a, int b) {
        return b == 0 ? 0 : 100.0 * a / b;
    }

    static double q(double[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int i = (int) Math.min(sorted.length - 1, Math.max(0, Math.round(p * (sorted.length - 1))));
        return sorted[i];
    }

    /** 均值和 95% t 区间（n ≥ 30 用 1.96，否则 2.1 近似）。 */
    static double[] tci(double[] x) {
        int n = x.length;
        if (n == 0) return new double[]{0, 0, 0};
        double m = Arrays.stream(x).average().orElse(0);
        double v = 0;
        for (double d : x) v += (d - m) * (d - m);
        double sd = n > 1 ? Math.sqrt(v / (n - 1)) : 0;
        double h = (n >= 30 ? 1.96 : 2.1) * sd / Math.sqrt(Math.max(1, n));
        return new double[]{m, m - h, m + h};
    }

    static List<Kline> load(Archive a, String interval, YearMonth from, YearMonth to, LocalDate lastDay, long step)
            throws Exception {
        a.download(List.of("BTCUSDT"), from, to, lastDay);
        long start = from.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long end = lastDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        int size = (int) ((end - start) / step);
        Series s = new Series("BTCUSDT", start, size);
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) Archive.read(dataPath(a, a.monthlyPath("BTCUSDT", m)), s, step);
        for (LocalDate d = lastDay.withDayOfMonth(1); !d.isAfter(lastDay); d = d.plusDays(1)) {
            Archive.read(dataPath(a, a.dailyPath("BTCUSDT", d)), s, step);
        }
        List<Kline> out = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (!s.has(i)) continue;
            long t = start + i * step;
            out.add(new Kline(t, s.open[i], s.high[i], s.low[i], s.close[i], s.quoteVolume[i], t + step - 1));
        }
        return out;
    }

    static Path dataPath(Archive a, String rel) {
        return a.root().resolve(rel);
    }
}
