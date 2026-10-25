package com.trendpilot.backtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.trendpilot.config.StrategyParams;
import com.trendpilot.signal.EarlySignalCalculator;
import com.trendpilot.variant.VariantOverrides;

/**
 * 配置化变体的回测与自动发布前检查（H1/H2/H4 研究 2026-10-26，以及每周六的自动回测）。
 * 基准 = 线上现行 V2（ATR×1.5 止损 + 3 天等反弹 + 冻结修复，买入审核不加门槛）；每个候选只叠加一组 {@link VariantOverrides}，
 * 判断代码和线上变体账本是同一份。每个候选配一个同规则的随机对照（同一轮、同笔数、同金额，币换成同币池随机一个）。
 * 输出 report.md（对比表）和 candidates.json（每个候选的检查结论；status = PASS 的才会被服务器拿去作为影子变体上线）。
 * <pre>
 * java -cp ... com.trendpilot.backtest.CandidateStudy 数据目录 输出目录 起始月 结束月 最后一天 开始交易 验证期开始 线程数
 *      [名字:key=v,key=v ...]   （不给候选时用 {@link #weeklyGrid()}）
 * </pre>
 * 环境变量 EVIDENCE_URL：结果目录在 GitHub 上的链接，写进 candidates.json 作为证据链接。
 */
public final class CandidateStudy {

    /** 候选：名字 + 参数改动 + 属于哪个假设。 */
    public record Candidate(String id, String group, VariantOverrides overrides) {
    }

    /** H1/H2/H4 研究（2026-10-26）登记的方案，看结果之前定好，共 14 组。 */
    static List<Candidate> h124() {
        List<Candidate> c = new ArrayList<>();
        for (String act : new String[]{"SKIP", "HALF"}) {
            String a = act.equals("SKIP") ? "不开" : "半仓";
            c.add(cand("H1a_BTC24h>1.5_" + a, "H1", "btc24h-above-pct=1.5,hot-entry=" + act));
            c.add(cand("H1b_币24h5-10_" + a, "H1", "coin24h-band-pct=5-10,hot-entry=" + act));
            c.add(cand("H1c_两者任一_" + a, "H1", "btc24h-above-pct=1.5,coin24h-band-pct=5-10,hot-entry=" + act));
        }
        for (int h : new int[]{12, 24}) {
            for (int x : new int[]{2, 4, 6}) {
                c.add(cand("H2_" + h + "h_浮盈<1%_亏>" + x + "%", "H2",
                        "early-exit-hours=" + h + ",early-exit-max-mfe-pct=1,early-exit-loss-pct=" + x));
            }
        }
        c.add(cand("H4_放量≤4倍", "H4", "max-volume-accel=4"));
        c.add(cand("H4_放量≤6倍", "H4", "max-volume-accel=6"));
        return c;
    }

    /** 每周自动回测的小格子（H1/H2/H4 的代表档 + rel1h 与每笔金额邻域），共 10 组。 */
    static List<Candidate> weeklyGrid() {
        List<Candidate> c = new ArrayList<>();
        c.add(cand("W_H1_BTC24h>1.5_半仓", "H1", "btc24h-above-pct=1.5,hot-entry=HALF"));
        c.add(cand("W_H1_币24h5-10_半仓", "H1", "coin24h-band-pct=5-10,hot-entry=HALF"));
        c.add(cand("W_H2_24h_浮盈<1%_亏>4%", "H2", "early-exit-hours=24,early-exit-max-mfe-pct=1,early-exit-loss-pct=4"));
        c.add(cand("W_H2_12h_浮盈<1%_亏>4%", "H2", "early-exit-hours=12,early-exit-max-mfe-pct=1,early-exit-loss-pct=4"));
        c.add(cand("W_H4_放量≤4倍", "H4", "max-volume-accel=4"));
        c.add(cand("W_rel1h≥4.5", "rel1h", "min-buy-rel1h=4.5"));
        c.add(cand("W_rel1h≥5.855", "rel1h", "min-buy-rel1h=5.855"));
        c.add(cand("W_rel1h≥7.5", "rel1h", "min-buy-rel1h=7.5"));
        c.add(cand("W_rel1h≥5.855_每笔1500", "size", "min-buy-rel1h=5.855,trade-usdt=1500"));
        c.add(cand("W_每笔1250", "size", "trade-usdt=1250"));
        return c;
    }

    private static Candidate cand(String id, String group, String inline) {
        return new Candidate(id, group, VariantOverrides.parseInline(inline));
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        YearMonth from = YearMonth.parse(args[2]);
        YearMonth to = YearMonth.parse(args[3]);
        LocalDate lastDay = args[4].equals("-") ? null : LocalDate.parse(args[4]);
        LocalDate tradeFrom = LocalDate.parse(args[5]);
        LocalDate split = LocalDate.parse(args[6]);
        int threads = Integer.parseInt(args[7]);
        List<Candidate> cands = new ArrayList<>();
        String gridName = "weekly";
        for (int a = 8; a < args.length; a++) {
            if (args[a].equals("h124")) {
                cands.addAll(h124());
                gridName = "h124";
                continue;
            }
            int colon = args[a].indexOf(':');
            cands.add(new Candidate(args[a].substring(0, colon), "custom", VariantOverrides.parseInline(args[a].substring(colon + 1))));
            gridName = "custom";
        }
        if (cands.isEmpty()) cands = weeklyGrid();
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
        todo.add(EntryStudy.live("现行", data, regimes, tape, params, fromQ, size));
        todo.add(EntryStudy.live("随机1", data, regimes, tape, params, fromQ, size).randomEntry(1001));
        todo.add(EntryStudy.live("随机2", data, regimes, tape, params, fromQ, size).randomEntry(1002));
        int seed = 5000;
        for (Candidate c : cands) {
            todo.add(EntryStudy.live(c.id(), data, regimes, tape, params, fromQ, size).overrides(c.overrides()));
            todo.add(EntryStudy.live(c.id() + " 随机", data, regimes, tape, params, fromQ, size).overrides(c.overrides()).randomEntry(seed++));
        }
        Map<String, Sim> byName = new LinkedHashMap<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Sim>> fs = new ArrayList<>();
            for (B0Sim sim : todo) {
                fs.add(pool.submit(() -> {
                    sim.run();
                    System.out.println("完成 " + sim.name() + "：" + sim.trades().size() + " 笔");
                    return sim;
                }));
            }
            for (Future<Sim> f : fs) {
                Sim s = f.get();
                byName.put(s.name(), s);
            }
        }
        List<Sim> sims = new ArrayList<>(byName.values());
        Sim base = byName.get("现行");
        ReleaseCheck.Period bF = ReleaseCheck.Period.of(base, regimes, fromQ, size);
        ReleaseCheck.Period bV = ReleaseCheck.Period.of(base, regimes, splitQ, size);
        double[] bReg = Metrics.of(base, regimes, fromQ, size).regimeEquity();

        String evidence = System.getenv().getOrDefault("EVIDENCE_URL", "");
        List<Map<String, Object>> out = new ArrayList<>();
        StringBuilder md = new StringBuilder();
        md.append("# 配置化变体回测与发布前检查（").append(tradeFrom).append(" ～ ").append(endDay.minusDays(1)).append("）\n\n");
        md.append("基准 = 线上现行 V2（ATR×1.5 止损 + 3 天等反弹 + 冻结修复）。校准期 ").append(tradeFrom).append(" ～ ")
                .append(split.minusDays(1)).append("，验证期 ").append(split).append(" ～ ").append(endDay.minusDays(1))
                .append("（最后 16 个月，不参与调参）。候选 ").append(cands.size()).append(" 组（").append(gridName)
                .append("），每组配一个同规则随机对照。\n\n");
        md.append("## 发布前检查\n\n");
        md.append("| 候选 | 改动 | 结论 | 验证期 权益/回撤 | 全期 权益/回撤 | 笔数 | 每笔差 ±95%（对现行） | 每笔差 ±95%（对随机） | 不满足的检查 |\n");
        md.append("|---|---|---|---:|---:|---:|---:|---:|---|\n");
        md.append(String.format(Locale.ROOT, "| 现行 | — | 基准 | %,.0f / %,.0f | %,.0f / %,.0f | %d | — | — | — |%n",
                bV.equity(), bV.maxDd(), bF.equity(), bF.maxDd(), bF.trades()));
        for (Candidate c : cands) {
            Sim s = byName.get(c.id());
            ReleaseCheck.Period f = ReleaseCheck.Period.of(s, regimes, fromQ, size);
            ReleaseCheck.Period v = ReleaseCheck.Period.of(s, regimes, splitQ, size);
            ReleaseCheck.Period rnd = ReleaseCheck.Period.of(byName.get(c.id() + " 随机"), regimes, fromQ, size);
            ReleaseCheck.Result r = ReleaseCheck.evaluate(f, v, bF, bV, Metrics.of(s, regimes, fromQ, size).regimeEquity(), bReg, rnd);
            List<String> failed = r.checks().entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList();
            List<String> why = new ArrayList<>(failed);
            why.addAll(r.notes());
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | %,.0f / %,.0f | %,.0f / %,.0f | %d | %+.2f ±%.2f | %+.2f ±%.2f | %s |%n",
                    c.id(), c.overrides().describe(), label(r.verdict()), v.equity(), v.maxDd(), f.equity(), f.maxDd(), f.trades(),
                    r.perTradeDiff()[0], r.perTradeDiff()[1], r.randomDiff()[0], r.randomDiff()[1],
                    why.isEmpty() ? "—" : String.join("；", why)));
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("id", c.id());
            j.put("group", c.group());
            j.put("overrides", c.overrides().toMap());
            j.put("status", r.verdict().name());
            j.put("checks", r.checks());
            j.put("full", Map.of("equity", round(f.equity()), "maxDrawdown", round(f.maxDd()), "trades", f.trades(), "perTrade", round(f.meanPnl())));
            j.put("validation", Map.of("equity", round(v.equity()), "maxDrawdown", round(v.maxDd()), "trades", v.trades(), "perTrade", round(v.meanPnl())));
            j.put("evidence", evidence.isBlank() ? "" : evidence + "/report.md");
            out.add(j);
        }
        md.append("\n结论：PASS = 全部检查通过（先作为影子变体跑满 30 天，再按自动转正条件决定）；PROMISING = 有希望但没全部通过（作为影子变体观察）；")
                .append("WORSE = 明显更差（只存档）。检查项见 ReleaseCheck。\n\n");
        md.append(AsymStudy.splitTable(sims, regimes, fromQ, splitQ, size));
        md.append(AsymStudy.asymmetry(sims.stream().filter(s -> !s.name().contains("随机")).toList(), fromQ));
        Nav nav = new Nav(data, params.capital().hardCap());
        md.append(StopStudy.report(sims, data, regimes, nav, fromQ, size, tradeFrom + " ～ " + endDay.minusDays(1), tradeFrom, endDay)
                .replaceFirst("# 防洗盘止损检验（[^）]*）\\n\\n", "## 发布前检查用的完整对照\n\n"));
        Files.writeString(outDir.resolve("report.md"), md.toString());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", 1);
        root.put("generatedAt", Instant.now().toString());
        root.put("grid", gridName);
        root.put("strategyVersion", params.version());
        root.put("dataFrom", tradeFrom.toString());
        root.put("dataTo", endDay.minusDays(1).toString());
        root.put("validationFrom", split.toString());
        root.put("evidence", evidence);
        root.put("baseline", Map.of("full", Map.of("equity", round(bF.equity()), "maxDrawdown", round(bF.maxDd()), "trades", bF.trades()),
                "validation", Map.of("equity", round(bV.equity()), "maxDrawdown", round(bV.maxDd()), "trades", bV.trades())));
        root.put("candidates", out);
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(outDir.resolve("candidates.json").toFile(), root);
        for (Sim s : sims) {
            if (s.name().contains("随机")) continue;
            Files.writeString(outDir.resolve("trades-" + s.name().replaceAll("[^\\p{L}\\p{N}]+", "_") + ".csv"), BacktestMain.tradesCsv(s, data));
        }
        System.out.println(md);
    }

    private static String label(ReleaseCheck.Verdict v) {
        return switch (v) {
            case PASS -> "**通过**";
            case PROMISING -> "有希望";
            case WORSE -> "更差";
        };
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static int q(MarketData data, LocalDate d) {
        return (int) ((d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - data.start) / Series.Q);
    }
}
