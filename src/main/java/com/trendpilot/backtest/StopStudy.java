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
 * 防洗盘止损研究（用户 2026-10-15：可以止损，但山寨经常跟着 BTC 一起跌、BTC 恢复后又猛涨，不能在回调里被反复止损）。
 * 买入和现行退出规则完全用现行 V2（B0），只叠加不同的止损，和不止损对比收益、回撤、被洗出后又涨回的比例。
 * 只是研究，不改线上规则。
 * <pre>
 * java -cp ... com.trendpilot.backtest.StopStudy 数据目录 输出目录 [起始月 2022-09] [结束月 2026-09] [最后一天 2026-10-14] [开始交易 2022-10-01] [线程数 4] [quick]
 * </pre>
 * 第 8 个参数 quick：检验用户 2026-10-17 提出的"亏损超过 2% 止损、或 3 天没收益就找个高点卖"（方案见 {@link #quickSims}）。
 */
public final class StopStudy {

    /** 事先登记的方案（看结果之前定好，不事后加）。 */
    static List<StopRule> rules() {
        return List.of(
                StopRule.fixed("固定-8%（15m收盘）", 8, "BAR15"),
                StopRule.fixed("固定-15%（1h收盘）", 15, "HOUR"),
                StopRule.atr("日线ATR×1.5（1h收盘）", 1.5, false, false, "HOUR"),
                StopRule.atr("日线ATR×1.5+大盘跌时暂缓", 1.5, false, true, "HOUR"),
                StopRule.atr("相对BTC×1.5（1h收盘）", 1.5, true, false, "HOUR"),
                StopRule.atr("相对BTC×1.5+大盘跌时暂缓+2小时确认", 1.5, true, true, "HOUR2"),
                StopRule.atr("日线ATR×2+大盘跌时暂缓+2小时确认", 2, false, true, "HOUR2"));
    }

    /**
     * 用户 2026-10-17 提议的方案（看结果之前定好）："亏损超过 2%，或者三天没有收益就选择一个高点退出"。
     * 拆成单独的两部分和组合，并和现行、已选定的日线 ATR×1.5 止损对照。有卖出亏损仓的方案都配冻结修复（同 10-15 口径）。
     */
    static List<B0Sim> quickSims(MarketData data, Regimes regimes, SignalTape tape, StrategyParams params, int fromQ, int size) {
        StopRule l2bar = StopRule.fixed("-2%（15m收盘）", 2, "BAR15");
        StopRule l2hour = StopRule.fixed("-2%（1h收盘）", 2, "HOUR");
        StopRule atr = StopRule.atr("日线ATR×1.5（1h收盘）", 1.5, false, false, "HOUR");
        return List.of(
                new B0Sim("不止损（现行）", data, regimes, tape, params, fromQ, size),
                new B0Sim("不止损+冻结修复", data, regimes, tape, params, fromQ, size, true),
                new B0Sim("亏2%止损（15m收盘）", data, regimes, tape, params, fromQ, size, true).stop(l2bar),
                new B0Sim("亏2%止损（1h收盘）", data, regimes, tape, params, fromQ, size, true).stop(l2hour),
                new B0Sim("3天仍亏损：立即卖", data, regimes, tape, params, fromQ, size, true).staleExit(72, false, 0),
                new B0Sim("3天仍亏损：等反弹高点卖（最多等24h）", data, regimes, tape, params, fromQ, size, true).staleExit(72, true, 24),
                new B0Sim("用户方案：亏2%（1h收盘）或3天等反弹", data, regimes, tape, params, fromQ, size, true).stop(l2hour).staleExit(72, true, 24),
                new B0Sim("对照：日线ATR×1.5止损", data, regimes, tape, params, fromQ, size, true).stop(atr),
                new B0Sim("对照：ATR×1.5止损+3天等反弹", data, regimes, tape, params, fromQ, size, true).stop(atr).staleExit(72, true, 24));
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "backtest-data");
        Path outDir = Path.of(args.length > 1 ? args[1] : "backtest-out");
        YearMonth from = YearMonth.parse(args.length > 2 ? args[2] : "2022-09");
        YearMonth to = YearMonth.parse(args.length > 3 ? args[3] : "2026-09");
        LocalDate lastDay = args.length <= 4 ? LocalDate.of(2026, 10, 14) : args[4].equals("-") ? null : LocalDate.parse(args[4]);
        LocalDate tradeFrom = LocalDate.parse(args.length > 5 ? args[5] : "2022-10-01");
        int threads = args.length > 6 ? Integer.parseInt(args[6]) : 4;
        boolean quick = args.length > 7 && args[7].equals("quick");
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
        int fromQ = (int) ((tradeFrom.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - start) / Series.Q);
        String period = tradeFrom + " ～ " + endDay.minusDays(1);

        List<Sim> sims = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Sim>> fs = new ArrayList<>();
            if (quick) {
                for (B0Sim sim : quickSims(data, regimes, tape, params, fromQ, size)) fs.add(pool.submit(() -> run(sim)));
            } else {
            fs.add(pool.submit(() -> run(new B0Sim("不止损（现行）", data, regimes, tape, params, fromQ, size))));
            fs.add(pool.submit(() -> run(new B0Sim("不止损+冻结修复", data, regimes, tape, params, fromQ, size, true))));
            fs.add(pool.submit(() -> run(new B0Sim("24小时没进保护就卖+冻结修复", data, regimes, tape, params, fromQ, size, true).timeExit(24))));
            for (StopRule r : rules()) {
                // 有止损时亏损仓会被卖出，冻结按"已实现亏损 + 新跌破的持仓"计，有固定解冻时间（冻结修复）
                fs.add(pool.submit(() -> run(new B0Sim(r.id(), data, regimes, tape, params, fromQ, size, true).stop(r))));
            }
            }
            for (Future<Sim> f : fs) sims.add(f.get());
        }
        Nav nav = new Nav(data, params.capital().hardCap());
        String md = report(sims, data, regimes, nav, fromQ, size, period, tradeFrom, endDay);
        if (quick) md = md.replaceFirst("# 防洗盘止损检验", "# 亏损 2% 止损 / 3 天不赚钱离场检验");
        Files.writeString(outDir.resolve("report.md"), md);
        for (Sim s : sims) Files.writeString(outDir.resolve("trades-" + s.name().replaceAll("[^\\p{L}\\p{N}]+", "_") + ".csv"),
                BacktestMain.tradesCsv(s, data));
        System.out.println(md);
    }

    private static Sim run(Sim s) {
        s.run();
        System.out.println("完成 " + s.name() + "：" + s.trades().size() + " 笔");
        return s;
    }

    static String report(List<Sim> sims, MarketData data, Regimes regimes, Nav nav, int fromQ, int toQ, String period,
                         LocalDate tradeFrom, LocalDate endDay) {
        StringBuilder b = new StringBuilder();
        b.append("# 防洗盘止损检验（").append(period).append("）\n\n");
        b.append("买入、利润保护、论点失效、风控全部是现行 V2（调用线上同一套类），只叠加不同的止损。币安现货 15m K 线，每天前 150 个币；")
                .append("成本 = 手续费 0.1% × 2 + 按成交额估算的冲击。止损都按收盘价确认后、以收盘价卖出（不按插针最低价）。")
                .append("有止损的方案都配冻结修复（深度浮亏只在第一次跌破后 24 小时内计入，冻结能自己解开）。\n\n");
        b.append("**被洗出后又涨回** = 止损卖出后 3 天内价格回到买入成本以上的比例。**正常行情止损占比** = 止损笔数 ÷ 总笔数。\n\n");

        b.append("## 全期\n\n");
        b.append("| 方案 | 笔数 | 胜率 | 权益变化 U | 最大回撤 U | 每笔均值% | 盈亏比 | 最差5%均值% | 止损笔数 | 止损占比 | 被洗出后又涨回 | 灾难线卖出 | 最长亏损持有(天) |\n");
        b.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Sim s : sims) {
            Metrics m = Metrics.of(s, regimes, fromQ, toQ);
            List<Trade> ts = s.trades().stream().filter(t -> t.entryQ() >= fromQ).toList();
            long stops = ts.stream().filter(t -> t.reason().startsWith("L1_")).count();
            long floors = ts.stream().filter(t -> t.reason().equals("L1_FLOOR")).count();
            long back = ts.stream().filter(t -> t.reason().startsWith("L1_") && t.recoveredAfter()).count();
            b.append(String.format(Locale.ROOT, "| %s | %d | %s | %,.0f | %,.0f | %.2f | %s | %.1f | %d | %s | %s | %d | %.0f |%n",
                    s.name(), m.trades(), pct(m.winRate()), m.equityChange(), m.maxDrawdown(), m.meanPct(), f(m.payoff()),
                    m.cvar5Pct(), stops, pct(ts.isEmpty() ? Double.NaN : stops / (double) ts.size()),
                    pct(stops == 0 ? Double.NaN : back / (double) stops), floors, m.longestLossDays()));
        }

        b.append("\n## 按年（每年的权益变化 / 最大回撤，U）\n\n");
        List<int[]> years = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (LocalDate y = tradeFrom; y.isBefore(endDay); y = y.plusYears(1)) {
            LocalDate z = y.plusYears(1).isBefore(endDay) ? y.plusYears(1) : endDay;
            years.add(new int[]{q(data, y), Math.min(q(data, z), toQ)});
            labels.add(y + "～" + z.minusDays(1));
        }
        b.append("| 方案 |");
        labels.forEach(l -> b.append(' ').append(l).append(" |"));
        b.append("\n|---|");
        labels.forEach(l -> b.append("---:|"));
        b.append('\n');
        for (Sim s : sims) {
            b.append("| ").append(s.name()).append(" |");
            for (int[] y : years) {
                Metrics m = Metrics.of(s, regimes, y[0], y[1]);
                b.append(String.format(Locale.ROOT, " %,.0f / %,.0f |", m.equityChange(), m.maxDrawdown()));
            }
            b.append('\n');
        }

        b.append("\n## 六种行情（全期，该行情期间的权益变化 U / 买入笔数）\n\n| 方案 |");
        for (int r = 1; r <= 6; r++) b.append(' ').append(Regimes.NAMES[r]).append(" |");
        b.append("\n|---|---:|---:|---:|---:|---:|---:|\n");
        for (Sim s : sims) {
            Metrics m = Metrics.of(s, regimes, fromQ, toQ);
            b.append("| ").append(s.name()).append(" |");
            for (int r = 1; r <= 6; r++) b.append(String.format(Locale.ROOT, " %,.0f / %d |", m.regimeEquity()[r], m.regimeTrades()[r]));
            b.append('\n');
        }

        b.append("\n## 净值对照（含浮亏、手续费、资金占用；资金按 ").append(String.format(Locale.ROOT, "%,.0f", nav.capital())).append(" U）\n\n");
        int a = fromQ / 4;
        int z = Math.min(toQ / 4, sims.getFirst().equity().length - 1);
        List<Nav.Row> rows = new ArrayList<>();
        double[] cash = new double[z - a + 1];
        java.util.Arrays.fill(cash, 1);
        rows.add(nav.row("现金", cash, null, a, null));
        rows.add(nav.row("持有 BTC", nav.hold(nav.btcHourly(), a, z), null, a, null));
        rows.add(nav.row("标的池等权（满仓）", nav.hold(nav.poolHourly(), a, z), null, a, null));
        for (Sim s : sims) rows.add(nav.row(s.name(), nav.curve(s, a, z), s.deployed(), a, nav.matched(s, a, z)));
        b.append(Nav.table(rows));
        return b.toString();
    }

    private static int q(MarketData data, LocalDate d) {
        return (int) ((d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - data.start) / Series.Q);
    }

    private static String pct(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.0f%%", v * 100);
    }

    private static String f(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.2f", v);
    }
}
