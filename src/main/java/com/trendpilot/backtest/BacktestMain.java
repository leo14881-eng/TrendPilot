package com.trendpilot.backtest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
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

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.signal.EarlySignalCalculator;
import com.trendpilot.v2n.V2nParams;

/**
 * 一年回测入口（docs/V2交易规则重设计.md 第 7 节）。不启动 Spring 服务、不连数据库，只读公开数据站的 K 线包。
 * <pre>
 * java -cp ... com.trendpilot.backtest.BacktestMain 数据目录 输出目录 [起始月 2025-09] [结束月 2026-09] [最后一天 2026-10-14，"-" 表示到结束月月底]
 *      [开始交易 2025-10-01] [验证期开始 2026-05-01] [线程数 4] [edge = 只做信号检验]
 * </pre>
 */
public final class BacktestMain {

    /** 合约持仓量、多空比数据（每天一个包，量大）只下载这一天起的。 */
    static final LocalDate METRICS_FROM = LocalDate.of(2025, 1, 1);

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "backtest-data");
        Path outDir = Path.of(args.length > 1 ? args[1] : "backtest-out");
        YearMonth from = YearMonth.parse(args.length > 2 ? args[2] : "2025-09");
        YearMonth to = YearMonth.parse(args.length > 3 ? args[3] : "2026-09");
        LocalDate lastDay = args.length <= 4 ? LocalDate.of(2026, 10, 14) : args[4].equals("-") ? null : LocalDate.parse(args[4]);
        LocalDate tradeFrom = LocalDate.parse(args.length > 5 ? args[5] : "2025-10-01");
        LocalDate split = LocalDate.parse(args.length > 6 ? args[6] : "2026-05-01");
        int threads = args.length > 7 ? Integer.parseInt(args[7]) : 4;
        boolean edgeOnly = args.length > 8 && args[8].equals("edge");
        Files.createDirectories(outDir);

        StrategyParams params = loadParams();
        Set<String> excluded = new HashSet<>();
        Archive archive = new Archive(dataDir);
        List<String> all = archive.usdtSymbols();
        List<String> symbols = new ArrayList<>();
        for (String s : all) {
            String base = s.substring(0, s.length() - 4);
            if (params.universe().excludedBaseAssets().contains(base) || params.universe().tokenizedStockBaseAssets().contains(base)
                    || params.universe().blocklist().contains(s)) {
                excluded.add(s);
                continue;
            }
            symbols.add(s);
        }
        System.out.println("USDT 交易对 " + all.size() + " 个，排除稳定币/法币/股票 " + excluded.size() + " 个");
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
        System.out.println("读入 " + series.size() + " 个交易对的 K 线");
        MarketData data = new MarketData(start, size, series, 150, excluded);
        System.out.println("进过标的池的币 " + (data.series.size() - 1) + " 个");
        Regimes regimes = new Regimes(data);
        SignalTape tape = new SignalTape(data, new EarlySignalCalculator(params));
        int fromQ = (int) ((tradeFrom.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - start) / Series.Q);
        int splitQ = (int) ((split.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - start) / Series.Q);
        String period = tradeFrom + " ～ " + endDay.minusDays(1) + "（验证期从 " + split + " 开始）";

        if (edgeOnly) {
            // 只做信号检验（长历史时不跑买卖模拟，省内存和时间）
            EdgeStudy edge = new EdgeStudy(data, regimes, tape, params.review().minScore());
            edge.run(fromQ, size);
            FactorStudy f150 = new FactorStudy(data, 150);
            f150.run(fromQ, size);
            FactorStudy f30 = new FactorStudy(data, 30);
            f30.run(fromQ, size);
            PumpStudy pump = new PumpStudy(data);
            pump.run(fromQ, size);
            PrePumpStudy pre = new PrePumpStudy(data);
            pre.run(fromQ, size);
            // 合约数据：资金费率和数据同起点；持仓量、多空比数据量大，只取 METRICS_FROM 起
            FuturesData fd = new FuturesData(dataDir.resolve("futures"), start, size / 4 + 1);
            LocalDate metricsTo = lastDay != null ? lastDay : to.atEndOfMonth();
            fd.load(data.series.keySet(), from, to, METRICS_FROM, metricsTo);
            PrePumpStudy deriv = new PrePumpStudy(data, DerivSigns.signs(fd),
                    "合约数据和成交结构的大涨前迹象（整点检查；下一根 15 分钟开盘买入；同一币同一迹象 24 小时只算一次；不含 BTC/ETH）", "deriv").minHistory(0);
            deriv.run(fromQ, size);
            TrendStudy trend = new TrendStudy(data);
            trend.run(fromQ, splitQ, size);
            String md = "# 信号检验（" + period + "）\n\n" + fd.summary() + "持仓量和多空比只有 " + METRICS_FROM + " 起的数据，相关迹象请和对照 C 比。\n\n" + deriv.markdown() + pre.markdown() + pump.markdown() + trend.markdown("前段", "后段") + f150.markdown() + f30.markdown() + edge.markdown();
            Files.writeString(outDir.resolve("report.md"), md);
            pre.writeCsv(outDir);
            deriv.writeCsv(outDir);
            pump.writeCsv(outDir.resolve("pump-trades.csv.gz"));
            trend.writeBtcCsv(outDir.resolve("trend-btc-daily.csv"));
            f150.writePoolCsv(outDir);
            System.out.println(md);
            return;
        }
        Experiments ex = Experiments.registered();
        List<Report.Run> runs = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Report.Run>> fs = new ArrayList<>();
            fs.add(pool.submit(() -> {
                Sim s = new B0Sim("B0", data, regimes, tape, params, fromQ, size);
                s.run();
                System.out.println("完成 B0：" + s.trades().size() + " 笔");
                return new Report.Run(s, null, "B0");
            }));
            // 2026-10-14 之前的利润保护：底线 = max(本金 0.35%, 最高净利 − 本金 2%)，没有 60% 锁定
            StrategyParams oldProtect = loadParams(Map.of("trendpilot.strategy.exit.protect-giveback-pct", 2,
                    "trendpilot.strategy.exit.protect-lock-ratio", 0));
            fs.add(pool.submit(() -> {
                Sim s = new B0Sim("B0-旧利润保护", data, regimes, tape, oldProtect, fromQ, size);
                s.run();
                System.out.println("完成 B0-旧利润保护：" + s.trades().size() + " 笔");
                return new Report.Run(s, null, "B0");
            }));
            fs.add(pool.submit(() -> {
                Sim s = new B0Sim("B0-冻结修复", data, regimes, tape, params, fromQ, size, true);
                s.run();
                System.out.println("完成 B0-冻结修复：" + s.trades().size() + " 笔");
                return new Report.Run(s, null, "B0");
            }));
            for (Map.Entry<V2nParams, String> e : ex.all().entrySet()) {
                fs.add(pool.submit(() -> {
                    Sim s = new NSim(e.getKey(), data, regimes, tape, params, fromQ, size);
                    s.run();
                    System.out.println("完成 " + s.name() + "：" + s.trades().size() + " 笔");
                    return new Report.Run(s, e.getKey(), e.getValue());
                }));
            }
            for (Future<Report.Run> f : fs) runs.add(f.get());
        }
        EdgeStudy edge = new EdgeStudy(data, regimes, tape, params.review().minScore());
        edge.run(fromQ, size);
        System.out.println("信号优势研究完成");
        Report report = new Report(regimes, fromQ, splitQ, size, period)
                .with(data, new Nav(data, params.capital().hardCap()), edge.markdown());
        runs.forEach(report::add);
        String md = report.build(ex.ids("grid"), ex.ids("ablation"), ex.ids("stop"));
        Files.writeString(outDir.resolve("report.md"), md);
        Files.writeString(outDir.resolve("summary.csv"), report.summaryCsv());
        for (Report.Run r : runs) Files.writeString(outDir.resolve("trades-" + r.sim().name() + ".csv"), tradesCsv(r.sim(), data));
        System.out.println(md);
    }

    static String tradesCsv(Sim sim, MarketData data) {
        StringBuilder b = new StringBuilder("symbol,entry,exit,cost,pnl,pct,reason,regime,r,tranches,maxNet,minNet,maeBeforePeak,mfe,atr1h,atr1d,recovered,altOpen\n");
        for (Trade t : sim.trades()) {
            b.append(String.format(Locale.ROOT, "%s,%s,%s,%.2f,%.2f,%.3f,%s,%d,%.1f,%d,%.2f,%.2f,%.3f,%.3f,%.3f,%.3f,%b,%b%n",
                    t.symbol(), java.time.Instant.ofEpochMilli(data.time(t.entryQ())), java.time.Instant.ofEpochMilli(data.time(t.exitQ())),
                    t.cost(), t.pnl(), t.pct(), t.reason(), t.regime(), t.r(), t.tranches(), t.maxNet(), t.minNet(),
                    t.maeBeforePeak(), t.mfePct(), t.atr1hPct(), t.atr1dPct(), t.recoveredAfter(), t.altGateOpen()));
        }
        return b.toString();
    }

    /** 读线上同一份 application.yml 里的策略参数。 */
    static StrategyParams loadParams() throws IOException {
        return loadParams(Map.of());
    }

    /** overrides 用 trendpilot.strategy.* 的完整键名覆盖个别参数（例如旧版利润保护）。 */
    static StrategyParams loadParams(Map<String, Object> overrides) throws IOException {
        List<PropertySource<?>> sources = new ArrayList<>(new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml")));
        if (!overrides.isEmpty()) sources.add(0, new org.springframework.core.env.MapPropertySource("override", overrides));
        Binder binder = new Binder(ConfigurationPropertySources.from(sources));
        return binder.bind("trendpilot.strategy", StrategyParams.class).get();
    }


    /** 事先登记的方案（设计文档 7.1 / 3.1）：分组 grid / ablation / stop。 */
    public static final class Experiments {
        private final Map<V2nParams, String> map = new LinkedHashMap<>();

        public Map<V2nParams, String> all() {
            return map;
        }

        public List<String> ids(String group) {
            return map.entrySet().stream().filter(e -> e.getValue().equals(group)).map(e -> e.getKey().id()).toList();
        }

        private void put(V2nParams p, String group) {
            if (map.keySet().stream().noneMatch(x -> x.id().equals(p.id()))) map.put(p, group);
        }

        public static Experiments registered() {
            Experiments e = new Experiments();
            V2nParams base = V2nParams.base();
            e.put(base, "grid");
            for (double k : new double[]{2.5, 3, 4}) {
                for (int l2 : new int[]{12, 24, 48}) {
                    for (double alt : new double[]{-2, -3, -4}) {
                        String id = String.format(Locale.ROOT, "N-k%s-L2%d-alt%s", trim(k), l2, trim(alt));
                        e.put(base.with(id, b -> b.set("stopAtr", k).set("timeStopHours", l2).set("altWeakPct", alt)
                                .set("altSemiPct", alt / 2)), "grid");
                    }
                }
            }
            for (double pr : new double[]{2, 3}) {
                for (double tr : new double[]{2.5, 3, 4}) {
                    String id = String.format(Locale.ROOT, "N-p%sR-trail%s", trim(pr), trim(tr));
                    e.put(base.with(id, b -> b.set("partialR", pr).set("trailAtr", tr)), "grid");
                }
            }
            e.put(base.with("N-无山寨门", b -> b.set("altGate", false)), "ablation");
            e.put(base.with("N-无趋势门", b -> b.set("trendGate", false)), "ablation");
            e.put(base.with("N-无追高门", b -> b.set("extensionGate", false)), "ablation");
            e.put(base.with("N-无时间止损", b -> b.set("timeStopHours", 0)), "ablation");
            e.put(base.with("N-论点失效直接卖", b -> b.set("decayMode", "SELL")), "ablation");
            e.put(base.with("N-论点失效亏损不卖", b -> b.set("decayMode", "PROFIT_ONLY")), "ablation");
            e.put(base.with("N-论点失效不处理", b -> b.set("decayMode", "NONE")), "ablation");
            e.put(base.with("N-跌了才加", b -> b.set("addMode", "DROPS")), "ablation");
            e.put(base.with("N-不加仓", b -> b.set("addMode", "NONE")), "ablation");
            e.put(base.with("N-现行利润保护", b -> b.set("exitMode", "B0")), "ablation");
            e.put(base.with("N-固定1000U", b -> b.set("fixedSize", 1000.0)), "ablation");
            e.put(base.with("N-系统冲击不动", b -> b.set("systemMode", "NONE")), "ablation");
            e.put(base.with("N-系统冲击全卖", b -> b.set("systemMode", "SELL")), "ablation");
            e.put(base.with("N-无价格止损", b -> b.set("stopMode", "OFF")), "stop");
            e.put(base.with("N-实时价止损", b -> b.set("stopMode", "REALTIME")), "stop");
            e.put(base.with("N-1h收盘止损", b -> b.set("stopMode", "HOUR")), "stop");
            for (double k : new double[]{5, 6}) {
                e.put(base.with("N-k" + trim(k), b -> b.set("stopAtr", k)), "stop");
            }
            for (double k : new double[]{0.75, 1.0, 1.5}) {
                e.put(base.with("N-日线ATR" + trim(k), b -> b.set("stopAtr", k).set("useDailyAtr", true)), "stop");
            }
            return e;
        }

        static String trim(double v) {
            return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
        }
    }
}
