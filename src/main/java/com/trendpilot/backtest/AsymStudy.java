package com.trendpilot.backtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.risk.Regime;
import com.trendpilot.signal.EarlySignalCalculator;

/**
 * V2 盈亏不对称研究（用户 2026-10-23："止损金额远大于止盈，赚好几个还不如一个亏的"；授权"回撤更好且证明强于改之前就自己优化"）。
 * 基线 = 线上现行 v3.4-2026-10-17-altstop：日线 ATR×1.5 止损（6%～25%，1h 收盘确认，−30% 灾难线）+ 3 天仍亏等反弹 + 冻结修复。
 * 候选（看结果之前登记，不事后加）：利润保护放宽、未走强按时离场、止损收紧、只在大盘偏强时开仓，以及组合。
 * 参数按校准期（前 2/3）挑，验证期（后 1/3）只看一次。只是研究，不改线上规则。
 * <pre>
 * java -cp ... com.trendpilot.backtest.AsymStudy 数据目录 输出目录 [起始月 2022-09] [结束月 2026-09] [最后一天 2026-10-14]
 *      [开始交易 2022-10-01] [验证期开始 2025-06-15] [线程数 4]
 * </pre>
 */
public final class AsymStudy {

    static StrategyParams exit(StrategyParams p, double arm, double giveback, double lock) {
        StrategyParams.Exit e = p.exit();
        StrategyParams.Exit x = new StrategyParams.Exit(arm, e.protectMinFloorPct(), giveback, lock, e.reviewTargetPct(),
                e.decayMinWeak(), e.decayConsecutive(), e.hardMaxSpreadBps(), e.hardMaxSlippageBps(), e.hardMinBidDepthUsdt());
        return new StrategyParams(p.version(), p.universe(), p.early(), p.review(), p.capital(), p.add(), x, p.risk(), p.feeRate());
    }

    /** 事先登记的方案。每个都在线上现行规则（ATR×1.5 止损 + 3 天等反弹 + 冻结修复）之上只改一处或组合。 */
    static List<B0Sim> sims(MarketData data, Regimes regimes, SignalTape tape, StrategyParams p, int fromQ, int size) {
        StopRule atr15 = StopRule.atr("ATR×1.5", 1.5, false, false, "HOUR");
        StopRule atr10 = StopRule.atr("ATR×1.0", 1.0, false, false, "HOUR");
        StopRule max12 = new StopRule("ATR×1.5 上限12%", 0, 1.5, 6, 12, false, 0, false, "HOUR", 30);
        StopRule max8 = new StopRule("ATR×1.5 上限8%", 0, 1.5, 6, 8, false, 0, false, "HOUR", 30);
        Set<Regime> strong = EnumSet.of(Regime.CONSTRUCTIVE, Regime.STRONG);
        StrategyParams.Exit e = p.exit();
        List<B0Sim> out = new ArrayList<>();
        java.util.function.BiFunction<String, StrategyParams, B0Sim> live = (name, pp) ->
                new B0Sim(name, data, regimes, tape, pp, fromQ, size, true).stop(atr15).staleExit(72, true, 24);
        out.add(live.apply("现行", p));
        // a) 利润保护放宽
        out.add(live.apply("a 保护启动3%", exit(p, 3, e.protectGivebackPct(), e.protectLockRatio())));
        out.add(live.apply("a 保护启动4%", exit(p, 4, e.protectGivebackPct(), e.protectLockRatio())));
        out.add(live.apply("a 保护启动5%", exit(p, 5, e.protectGivebackPct(), e.protectLockRatio())));
        out.add(live.apply("a 回吐放宽(2%,锁50%)", exit(p, e.protectArmPct(), 2, 0.5)));
        out.add(live.apply("a 启动4%+回吐放宽", exit(p, 4, 2, 0.5)));
        // c) 未走强按时离场（持有满 N 小时利润保护仍未启动就卖）
        for (int h : new int[]{4, 8, 12, 24}) out.add(live.apply("c " + h + "小时未走强离场", p).timeExit(h));
        // d) 止损收紧
        out.add(new B0Sim("d ATR×1.0", data, regimes, tape, p, fromQ, size, true).stop(atr10).staleExit(72, true, 24));
        out.add(new B0Sim("d 上限12%", data, regimes, tape, p, fromQ, size, true).stop(max12).staleExit(72, true, 24));
        out.add(new B0Sim("d 上限8%", data, regimes, tape, p, fromQ, size, true).stop(max8).staleExit(72, true, 24));
        // e) 只在大盘偏强时开新仓
        out.add(live.apply("e 只在偏强时开仓", p).buyOnlyIn(strong));
        // 组合
        out.add(live.apply("组合 启动4%+12小时离场", exit(p, 4, e.protectGivebackPct(), e.protectLockRatio())).timeExit(12));
        out.add(live.apply("组合 启动4%+偏强开仓", exit(p, 4, e.protectGivebackPct(), e.protectLockRatio())).buyOnlyIn(strong));
        out.add(live.apply("组合 12小时离场+偏强开仓", p).timeExit(12).buyOnlyIn(strong));
        return out;
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "backtest-data");
        Path outDir = Path.of(args.length > 1 ? args[1] : "backtest-out");
        YearMonth from = YearMonth.parse(args.length > 2 ? args[2] : "2022-09");
        YearMonth to = YearMonth.parse(args.length > 3 ? args[3] : "2026-09");
        LocalDate lastDay = args.length <= 4 ? LocalDate.of(2026, 10, 14) : args[4].equals("-") ? null : LocalDate.parse(args[4]);
        LocalDate tradeFrom = LocalDate.parse(args.length > 5 ? args[5] : "2022-10-01");
        LocalDate split = LocalDate.parse(args.length > 6 ? args[6] : "2025-06-15");
        int threads = args.length > 7 ? Integer.parseInt(args[7]) : 4;
        Files.createDirectories(outDir);

        StrategyParams params = BacktestMain.loadParams();
        Set<String> excluded = new HashSet<>();
        Archive archive = new Archive(dataDir);
        List<String> symbols = new ArrayList<>();
        for (String s : archive.usdtSymbols()) {
            String base = s.substring(0, s.length() - 4);
            if (params.universe().excludedBaseAssets().contains(base) || params.universe().tokenizedStockBaseAssets().contains(base)
                    || params.universe().blocklist().contains(s)) {
                excluded.add(s);
                continue;
            }
            symbols.add(s);
        }
        archive.download(symbols, from, to, lastDay);
        long start = from.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        LocalDate endDay = lastDay != null ? lastDay.plusDays(1) : to.plusMonths(1).atDay(1);
        long end = endDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        int size = (int) ((end - start) / Series.Q);
        Map<String, Series> series = new ConcurrentHashMap<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> fs = new ArrayList<>();
            for (String s : symbols) {
                fs.add(pool.submit(() -> {
                    Series x = archive.load(s, start, size, from, to, lastDay);
                    if (x != null) series.put(s, x);
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
        }
        MarketData data = new MarketData(start, size, series, 150, excluded);
        Regimes regimes = new Regimes(data);
        SignalTape tape = new SignalTape(data, new EarlySignalCalculator(params));
        int fromQ = q(data, tradeFrom);
        int splitQ = q(data, split);

        List<Sim> sims = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Sim>> fs = new ArrayList<>();
            for (B0Sim sim : sims(data, regimes, tape, params, fromQ, size)) {
                fs.add(pool.submit(() -> {
                    sim.run();
                    System.out.println("完成 " + sim.name() + "：" + sim.trades().size() + " 笔");
                    return sim;
                }));
            }
            for (Future<Sim> f : fs) sims.add(f.get());
        }
        Nav nav = new Nav(data, params.capital().hardCap());
        StringBuilder md = new StringBuilder();
        md.append("# V2 盈亏不对称研究（").append(tradeFrom).append(" ～ ").append(endDay.minusDays(1)).append("）\n\n");
        md.append("基线 = 线上现行规则（ATR×1.5 止损 + 3 天等反弹 + 冻结修复）。校准期 ").append(tradeFrom).append(" ～ ")
                .append(split.minusDays(1)).append("，验证期 ").append(split).append(" ～ ").append(endDay.minusDays(1)).append("。\n\n");
        md.append(asymmetry(sims, fromQ));
        md.append(splitTable(sims, regimes, fromQ, splitQ, size));
        md.append(StopStudy.report(sims, data, regimes, nav, fromQ, size, tradeFrom + " ～ " + endDay.minusDays(1), tradeFrom, endDay)
                .replaceFirst("# 防洗盘止损检验（[^）]*）\\n\\n", "## 发布前检查用的完整对照\n\n"));
        Files.writeString(outDir.resolve("report.md"), md.toString());
        for (Sim s : sims) Files.writeString(outDir.resolve("trades-" + s.name().replaceAll("[^\\p{L}\\p{N}]+", "_") + ".csv"),
                BacktestMain.tradesCsv(s, data));
        System.out.println(md);
    }

    /** 盈亏不对称量化：平均盈利/亏损、盈亏比、每笔期望、按卖出原因分组、盈利单卖早程度、亏损单曾经浮盈的比例。 */
    static String asymmetry(List<Sim> sims, int fromQ) {
        StringBuilder b = new StringBuilder("## 盈亏不对称（全期，每笔 U）\n\n");
        b.append("| 方案 | 笔数 | 盈利笔 | 亏损笔 | 平均盈利 | 平均亏损 | 盈亏比 | 每笔期望 | 盈利单最大浮盈均值 | 盈利单实际卖出/最大浮盈 | 亏损单曾浮盈>0比例 |\n");
        b.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Sim s : sims) {
            List<Trade> ts = s.trades().stream().filter(t -> t.entryQ() >= fromQ).toList();
            List<Trade> win = ts.stream().filter(t -> t.pnl() > 0).toList();
            List<Trade> loss = ts.stream().filter(t -> t.pnl() < 0).toList();
            double aw = win.stream().mapToDouble(Trade::pnl).average().orElse(0);
            double al = loss.stream().mapToDouble(Trade::pnl).average().orElse(0);
            double exp = ts.stream().mapToDouble(Trade::pnl).average().orElse(0);
            double mfeW = win.stream().mapToDouble(Trade::maxNet).average().orElse(0);
            double capture = win.stream().mapToDouble(Trade::pnl).sum() / Math.max(1e-9, win.stream().mapToDouble(Trade::maxNet).sum());
            double lossWasUp = loss.isEmpty() ? Double.NaN : loss.stream().filter(t -> t.maxNet() > 0).count() / (double) loss.size();
            b.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %.2f | %.2f | %s | %.2f | %.2f | %.0f%% | %s |%n",
                    s.name(), ts.size(), win.size(), loss.size(), aw, al, al == 0 ? "—" : String.format(Locale.ROOT, "%.2f", aw / -al),
                    exp, mfeW, capture * 100, Double.isNaN(lossWasUp) ? "—" : String.format(Locale.ROOT, "%.0f%%", lossWasUp * 100)));
        }
        Sim base = sims.getFirst();
        b.append("\n### 现行规则按卖出原因（全期）\n\n| 卖出原因 | 笔数 | 合计 U | 每笔平均 U | 每笔平均 % |\n|---|---:|---:|---:|---:|\n");
        Map<String, List<Trade>> by = new LinkedHashMap<>();
        base.trades().stream().filter(t -> t.entryQ() >= fromQ).forEach(t -> by.computeIfAbsent(t.reason(), k -> new ArrayList<>()).add(t));
        by.entrySet().stream().sorted((x, y) -> Double.compare(sum(x.getValue()), sum(y.getValue()))).forEach(en ->
                b.append(String.format(Locale.ROOT, "| %s | %d | %,.0f | %.2f | %.2f |%n", en.getKey(), en.getValue().size(),
                        sum(en.getValue()), sum(en.getValue()) / en.getValue().size(),
                        en.getValue().stream().mapToDouble(Trade::pct).average().orElse(0))));
        return b.append('\n').toString();
    }

    /** 校准期 / 验证期 / 全期：权益变化、最大回撤、笔数、每笔期望。 */
    static String splitTable(List<Sim> sims, Regimes regimes, int fromQ, int splitQ, int toQ) {
        StringBuilder b = new StringBuilder("## 校准期 / 验证期 / 全期（权益变化 U，最大回撤 U，每笔期望 U）\n\n");
        b.append("| 方案 | 校准期 | 验证期 | 全期 | 全期笔数 |\n|---|---:|---:|---:|---:|\n");
        for (Sim s : sims) {
            Metrics c = Metrics.of(s, regimes, fromQ, splitQ);
            Metrics v = Metrics.of(s, regimes, splitQ, toQ);
            Metrics a = Metrics.of(s, regimes, fromQ, toQ);
            b.append(String.format(Locale.ROOT, "| %s | %,.0f / %,.0f / %.2f | %,.0f / %,.0f / %.2f | %,.0f / %,.0f / %.2f | %d |%n",
                    s.name(), c.equityChange(), c.maxDrawdown(), c.trades() == 0 ? 0 : c.totalPnl() / c.trades(),
                    v.equityChange(), v.maxDrawdown(), v.trades() == 0 ? 0 : v.totalPnl() / v.trades(),
                    a.equityChange(), a.maxDrawdown(), a.trades() == 0 ? 0 : a.totalPnl() / a.trades(), a.trades()));
        }
        return b.append('\n').toString();
    }

    private static double sum(List<Trade> ts) {
        return ts.stream().mapToDouble(Trade::pnl).sum();
    }

    private static int q(MarketData data, LocalDate d) {
        return (int) ((d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - data.start) / Series.Q);
    }
}
