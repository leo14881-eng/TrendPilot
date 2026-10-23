package com.trendpilot.backtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.signal.EarlySignalCalculator;

/**
 * V2 买入端研究（用户 2026-10-23 同意"查买入端"）。卖法保持线上现行 v3.4-2026-10-17-altstop + 冻结修复不变，只看买什么、什么时候买。
 * <ul>
 *   <li>explore：现行规则 + 随机对照（同一轮、同笔数、同金额，币换成同币池随机一个），导出每笔交易和开仓特征。</li>
 *   <li>其余：参数 9 起每个是 "名字=过滤表达式"（见 {@link EntryFeat#parse}），和现行、随机对照一起跑，过滤版本也各配一个随机对照。</li>
 * </ul>
 * <pre>
 * java -cp ... com.trendpilot.backtest.EntryStudy 数据目录 输出目录 [起始月] [结束月] [最后一天] [开始交易] [验证期开始] [线程数]
 *      [explore 随机种子数 | 名字=表达式 ...]
 * </pre>
 */
public final class EntryStudy {

    static B0Sim live(String name, MarketData data, Regimes regimes, SignalTape tape, StrategyParams p, int fromQ, int size) {
        return new B0Sim(name, data, regimes, tape, p, fromQ, size, true)
                .stop(StopRule.atr("ATR×1.5", 1.5, false, false, "HOUR")).staleExit(72, true, 24);
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
        boolean explore = args.length <= 8 || args[8].equals("explore");
        int seeds = explore && args.length > 9 ? Integer.parseInt(args[9]) : 2;
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

        List<B0Sim> todo = new ArrayList<>();
        todo.add(live("现行", data, regimes, tape, params, fromQ, size));
        for (int i = 1; i <= seeds; i++) todo.add(live("随机" + i, data, regimes, tape, params, fromQ, size).randomEntry(1000 + i));
        if (!explore) {
            for (int a = 8; a < args.length; a++) {
                int eq = args[a].indexOf('=');
                String name = args[a].substring(0, eq);
                if (name.startsWith("cfgx_")) {
                    // 多个线上配置项一起覆盖：cfgx_名字=review.min-buy-rel1h@5.855+capital.trade-size@1500（键相对 trendpilot.strategy），另配一个随机对照
                    Map<String, Object> ov = new java.util.LinkedHashMap<>();
                    for (String kv : args[a].substring(eq + 1).split("\\+")) {
                        int at = kv.indexOf('@');
                        ov.put("trendpilot.strategy." + kv.substring(0, at), kv.substring(at + 1));
                    }
                    StrategyParams pp = BacktestMain.loadParams(ov);
                    todo.add(live(name, data, regimes, tape, pp, fromQ, size));
                    todo.add(live(name + " 随机", data, regimes, tape, pp, fromQ, size).randomEntry(3000 + a));
                    continue;
                }
                if (name.startsWith("cfg_")) {
                    // 用线上配置项跑（核对配置实现和研究过滤一致）
                    StrategyParams pp = BacktestMain.loadParams(Map.of("trendpilot.strategy.review.min-buy-rel1h", args[a].substring(eq + 1)));
                    todo.add(live(name, data, regimes, tape, pp, fromQ, size));
                    continue;
                }
                // 命令行里避开 shell 特殊字符：_ge_ _le_ _gt_ _lt_ _and_ _or_，开头 not_ 表示剔除
                String expr = args[a].substring(eq + 1).replace("_ge_", ">=").replace("_le_", "<=").replace("_gt_", ">")
                        .replace("_lt_", "<").replace("_and_", "&").replace("_or_", "|").replaceFirst("^not_", "NOT:");
                todo.add(live(name, data, regimes, tape, params, fromQ, size).entryFilter(EntryFeat.parse(expr)));
                todo.add(live(name + " 随机", data, regimes, tape, params, fromQ, size).entryFilter(EntryFeat.parse(expr)).randomEntry(2000 + a));
            }
        }
        List<Sim> sims = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Sim>> fs = new ArrayList<>();
            for (B0Sim sim : todo) {
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
        md.append("# V2 买入端研究（").append(tradeFrom).append(" ～ ").append(endDay.minusDays(1)).append("）\n\n");
        md.append("卖法 = 线上现行（ATR×1.5 止损 + 3 天等反弹 + 冻结修复）。随机 = 同一轮、同笔数、同金额，币换成同币池随机一个未持有的币。")
                .append("校准期 ").append(tradeFrom).append(" ～ ").append(split.minusDays(1)).append("，验证期 ").append(split)
                .append(" ～ ").append(endDay.minusDays(1)).append("。\n\n");
        md.append(AsymStudy.splitTable(sims, regimes, fromQ, splitQ, size));
        md.append(StopStudy.report(sims, data, regimes, nav, fromQ, size, tradeFrom + " ～ " + endDay.minusDays(1), tradeFrom, endDay)
                .replaceFirst("# 防洗盘止损检验（[^）]*）\\n\\n", "## 发布前检查用的完整对照\n\n"));
        Files.writeString(outDir.resolve("report.md"), md.toString());
        for (Sim s : sims) {
            Files.writeString(outDir.resolve("entries-" + s.name().replaceAll("[^\\p{L}\\p{N}]+", "_") + ".csv"),
                    entriesCsv((B0Sim) s, data));
        }
        StringBuilder eq = new StringBuilder("day");
        for (Sim s : sims) eq.append(',').append(s.name());
        eq.append('\n');
        for (int q = fromQ + MarketData.DAY - 1; q < size; q += MarketData.DAY) {
            eq.append(java.time.Instant.ofEpochMilli(data.time(q)).atZone(ZoneOffset.UTC).toLocalDate());
            for (Sim s : sims) eq.append(String.format(Locale.ROOT, ",%.2f", s.equity()[q / 4]));
            eq.append('\n');
        }
        Files.writeString(outDir.resolve("equity-daily.csv"), eq.toString());
        System.out.println(md);
    }

    /** 每笔交易 + 开仓特征（只含交易期内开仓的）。 */
    static String entriesCsv(B0Sim sim, MarketData data) {
        StringBuilder b = new StringBuilder("symbol,entry,exit,cost,pnl,pct,reason,regime,tranches,maxNet,minNet,hours,")
                .append(EntryFeat.HEADER).append('\n');
        for (Trade t : sim.trades()) {
            EntryFeat f = sim.entryFeats.get(t.symbol() + "@" + t.entryQ());
            if (f == null) continue;
            b.append(String.format(Locale.ROOT, "%s,%s,%s,%.2f,%.2f,%.3f,%s,%d,%d,%.2f,%.2f,%.1f,", t.symbol(),
                    java.time.Instant.ofEpochMilli(data.time(t.entryQ())), java.time.Instant.ofEpochMilli(data.time(t.exitQ())),
                    t.cost(), t.pnl(), t.pct(), t.reason(), t.regime(), t.tranches(), t.maxNet(), t.minNet(), t.hours()));
            b.append(f.csv()).append('\n');
        }
        return b.toString();
    }

    private static int q(MarketData data, LocalDate d) {
        return (int) ((d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - data.start) / Series.Q);
    }
}
