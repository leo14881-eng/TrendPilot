package com.trendpilot.backtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.GZIPOutputStream;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.signal.EarlySignalCalculator;

/**
 * 回测与实盘对账（研究用，2026-11-08）：在指定起点（精确到小时）用 {@link CandidateStudy} 同一套候选写法和 {@link EntryStudy#live}
 * 基准跑若干个变体，输出逐笔明细（含回测买入 / 卖出的参考价），并把对账时段内用到的 15m K 线导出成 kline15m.csv.gz，
 * 供离线和实盘成交逐笔配对。只读公开数据，不碰线上账本。
 * <pre>
 * java -cp ... com.trendpilot.backtest.ReconStudy 数据目录 输出目录 起始月 结束月 最后一天 线程数 导出K线起点(ISO|-) 运行...
 *   运行 = 名字@开始(ISO 时间或日期)[@结束]#key=v,key=v （写法同 CandidateStudy，sim.* 是回测专用设置）
 * </pre>
 */
public final class ReconStudy {

    private ReconStudy() {
    }

    record Run(String name, long startMs, long endMs, CandidateStudy.Candidate cand) {
    }

    static Run parseRun(String arg) {
        int hash = arg.indexOf('#');
        String head = hash < 0 ? arg : arg.substring(0, hash);
        String inline = hash < 0 ? "" : arg.substring(hash + 1);
        String[] p = head.split("@");
        long s = time(p[1]);
        long e = p.length > 2 ? time(p[2]) : Long.MAX_VALUE;
        return new Run(p[0], s, e, CandidateStudy.parse(p[0], "recon", inline));
    }

    static long time(String x) {
        if (x.length() == 10) return LocalDate.parse(x).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        return Instant.parse(x).toEpochMilli();
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        YearMonth from = YearMonth.parse(args[2]);
        YearMonth to = YearMonth.parse(args[3]);
        LocalDate lastDay = args[4].equals("-") ? null : LocalDate.parse(args[4]);
        int threads = Integer.parseInt(args[5]);
        Long dumpFrom = args[6].equals("-") ? null : time(args[6]);
        List<Run> runs = new ArrayList<>();
        for (int a = 7; a < args.length; a++) runs.add(parseRun(args[a]));
        Files.createDirectories(outDir);

        StrategyParams params = BacktestMain.loadParams();
        Set<String> excluded = new HashSet<>();
        Archive archive = new Archive(dataDir);
        List<String> symbols = new ArrayList<>();
        for (String s : archive.usdtSymbols()) {
            if (com.trendpilot.universe.UniverseFilter.excludedOffline(params.universe(), s)) {
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

        List<B0Sim> sims = new ArrayList<>();
        for (Run r : runs) {
            StrategyParams pp = r.cand().config().isEmpty() ? params : BacktestMain.loadParams(r.cand().config());
            int fromQ = Math.max(0, (int) Math.ceil((r.startMs() - start) / (double) Series.Q));
            int toQ = r.endMs() == Long.MAX_VALUE ? size : Math.min(size, (int) ((r.endMs() - start) / Series.Q));
            sims.add(r.cand().apply(EntryStudy.live(r.name(), data, regimes, tape, pp, fromQ, toQ)));
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> fs = new ArrayList<>();
            for (B0Sim s : sims) fs.add(pool.submit(() -> {
                s.run();
                System.out.println("完成 " + s.name() + "：" + s.trades().size() + " 笔");
                return null;
            }));
            for (Future<?> f : fs) f.get();
        }
        Set<String> used = new TreeSet<>();
        StringBuilder sum = new StringBuilder("| 运行 | 笔数 | 合计 U | 胜率 | 平均盈利 | 平均亏损 |\n|---|---:|---:|---:|---:|---:|\n");
        for (B0Sim s : sims) {
            Files.writeString(outDir.resolve("recon-" + s.name().replaceAll("[^\\p{L}\\p{N}]+", "_") + ".csv"), tradesCsv(s, data));
            Files.writeString(outDir.resolve("equity-" + s.name().replaceAll("[^\\p{L}\\p{N}]+", "_") + ".csv"), equityCsv(s, data));
            s.trades().forEach(t -> used.add(t.symbol()));
            List<Double> p = s.trades().stream().map(Trade::pnl).toList();
            double w = p.stream().filter(x -> x > 0).mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            double l = p.stream().filter(x -> x <= 0).mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            sum.append(String.format(Locale.ROOT, "| %s | %d | %,.0f | %.1f%% | %+.1f | %+.1f |%n", s.name(), p.size(),
                    p.stream().mapToDouble(Double::doubleValue).sum(), 100.0 * p.stream().filter(x -> x > 0).count() / Math.max(1, p.size()), w, l));
        }
        Files.writeString(outDir.resolve("summary.md"), sum.toString());
        System.out.println(sum);
        if (dumpFrom != null) {
            int q0 = Math.max(0, (int) ((dumpFrom - start) / Series.Q));
            // 对账时段内出现过的全部币（标的池 + 回测成交的币），实盘只在这些币里成交时才能配对
            Set<String> dump = new TreeSet<>(used);
            for (int q = q0; q < size; q += MarketData.DAY) dump.addAll(data.universe(q));
            dump.add(MarketData.BTC);
            try (var out = new java.io.PrintWriter(new java.io.OutputStreamWriter(
                    new GZIPOutputStream(Files.newOutputStream(outDir.resolve("kline15m.csv.gz"))), java.nio.charset.StandardCharsets.UTF_8))) {
                out.println("symbol,t,open,high,low,close,quote_volume");
                for (String sym : dump) {
                    Series x = data.get(sym);
                    if (x == null) continue;
                    for (int q = q0; q < size; q++) {
                        if (!x.has(q)) continue;
                        out.printf(Locale.ROOT, "%s,%d,%s,%s,%s,%s,%.0f%n", sym, data.time(q), x.open[q], x.high[q], x.low[q], x.close[q], x.quoteVolume[q]);
                    }
                }
            }
            List<String> uni = new ArrayList<>();
            for (int q = q0 - q0 % MarketData.DAY; q < size; q += MarketData.DAY) {
                uni.add(Instant.ofEpochMilli(data.time(q)).toString().substring(0, 10) + "," + String.join(" ", data.universe(q)));
            }
            Files.write(outDir.resolve("universe.csv"), uni);
        }
    }

    /** 逐笔明细：买入 / 卖出时间（15m K 线开盘时间）、成本、净收益、原因、最高 / 最低净利，以及买入那根的收盘价和 24h 成交额。 */
    static String tradesCsv(Sim sim, MarketData data) {
        StringBuilder b = new StringBuilder("symbol,entry,exit,cost,pnl,pct,reason,regime,maxNet,minNet,entryClose,entryVol24h,exitClose,hours\n");
        for (Trade t : sim.trades()) {
            Series s = data.get(t.symbol());
            b.append(String.format(Locale.ROOT, "%s,%s,%s,%.4f,%.4f,%.3f,%s,%d,%.2f,%.2f,%s,%.0f,%s,%.2f%n", t.symbol(),
                    Instant.ofEpochMilli(data.time(t.entryQ())), Instant.ofEpochMilli(data.time(t.exitQ())), t.cost(), t.pnl(), t.pct(),
                    t.reason(), t.regime(), t.maxNet(), t.minNet(), s.close[t.entryQ()], s.volume(t.entryQ(), MarketData.DAY),
                    s.has(t.exitQ()) ? s.close[t.exitQ()] : Double.NaN, t.hours()));
        }
        return b.toString();
    }

    /** 每小时权益（已实现 + 浮动），用于历史同长度窗口的收益分布。 */
    static String equityCsv(Sim sim, MarketData data) {
        StringBuilder b = new StringBuilder("t,equity\n");
        double[] e = sim.equity();
        for (int h = 0; h < e.length; h++) {
            long t = data.start + h * Series.H;
            if (t < data.time(0)) continue;
            b.append(t).append(',').append(String.format(Locale.ROOT, "%.2f", e[h])).append('\n');
        }
        return b.toString();
    }
}
