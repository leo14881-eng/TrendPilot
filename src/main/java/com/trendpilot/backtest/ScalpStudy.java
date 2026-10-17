package com.trendpilot.backtest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.trendpilot.config.StrategyParams;

/**
 * 超短线检验（用户 2026-10-17："只做超短线，亏了就走，赚了就持有，回撤就启动利润保护"）。1 分钟 K 线，事先登记：
 * <ul>
 * <li>标的：每天按前一天成交额取前 100 个山寨（不含 BTC、稳定币、杠杆币、股票代币，含后来下架的币）。</li>
 * <li>三种买点（信号那一分钟收盘确认，下一分钟开盘买；同一币同一买点 60 分钟内只算一次）：
 *   E1 放量突破：收盘创 60 分钟新高，这一分钟成交额 ≥ 前 60 分钟平均的 5 倍，15 分钟涨幅在 1%～6%；
 *   E2 强于 BTC：5 分钟涨幅 ≥ 2% 且比 BTC 多涨 ≥ 2%，最近 5 分钟成交额 ≥ 之前 60 分钟同长度平均的 3 倍；
 *   E3 上涨回踩再起：60 分钟涨幅 ≥ 4%，上一分钟收盘离 60 分钟高点回落 ≥ 1.5%，这一分钟收盘站上上一分钟最高价。</li>
 * <li>卖法（12 组）：止损 1% / 2% / 3%（跌破就按止损价卖，开盘就跳空在止损价下面的按开盘价）；
 *   涨到 +1% / +2% 启动利润保护，之后从最高价回落 0.5% / 1% 卖；最长持有 24 小时按收盘卖。
 *   同一分钟先看止损、再看保护（保守）；保护线用上一分钟为止的最高价，不用当分钟的最高价。</li>
 * <li>成本：手续费 0.1% × 2，滑点每边 0.05%（另列滑点 ×3）。</li>
 * <li>对照：同一个币、同一天随机选一个分钟买入，同样卖法（"随便买"能不能也赚）。</li>
 * <li>2024-10 ～ 2025-09 选出最好的一组买点 × 卖法，2025-10 ～ 2026-10 只看一次。</li>
 * </ul>
 * 只量每笔的期望，不模拟资金上限和同时持仓（每笔 1,000 U 独立计算）。研究用，不接线上。
 */
public final class ScalpStudy {

    static final long M = 60_000L;
    static final int MAX_HOLD = 1440;
    static final double FEE = 0.001;
    static final double SLIP = 0.0005;
    static final int TOP = 100;
    static final String[] ENTRY = {"E1 放量突破", "E2 强于 BTC", "E3 上涨回踩再起"};

    record Exit(String name, double stop, double arm, double trail) {
    }

    static final List<Exit> EXITS = new ArrayList<>();

    static {
        for (double s : new double[]{0.01, 0.02, 0.03}) {
            for (double a : new double[]{0.01, 0.02}) {
                for (double t : new double[]{0.005, 0.01}) {
                    EXITS.add(new Exit(String.format(Locale.ROOT, "止损%.0f%% 保护+%.0f%% 回落%.1f%%", s * 100, a * 100, t * 100), s, a, t));
                }
            }
        }
    }

    /** 一笔：{净收益%, 天, 滑点×3 净收益%, 随机对照净收益%, 随机对照滑点×3 净收益%, 卖出时间(分钟), 持有分钟}。 */
    final Map<String, List<double[]>> results = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "backtest-data");
        Path outDir = Path.of(args.length > 1 ? args[1] : "backtest-out");
        YearMonth from = YearMonth.parse(args.length > 2 ? args[2] : "2024-10");
        YearMonth to = YearMonth.parse(args.length > 3 ? args[3] : "2026-09");
        LocalDate lastDay = args.length > 4 ? LocalDate.parse(args[4]) : LocalDate.of(2026, 10, 14);
        LocalDate split = LocalDate.parse(args.length > 5 ? args[5] : "2025-10-01");
        int threads = args.length > 6 ? Integer.parseInt(args[6]) : 4;
        Files.createDirectories(outDir);

        StrategyParams params = BacktestMain.loadParams();
        Archive daily = new Archive(dataDir.resolve("scalp-1d"), "1d");
        List<String> symbols = new ArrayList<>();
        for (String s : daily.usdtSymbols()) {
            String base = s.substring(0, s.length() - 4);
            if (s.equals("BTCUSDT") || params.universe().excludedBaseAssets().contains(base)
                    || params.universe().tokenizedStockBaseAssets().contains(base) || params.universe().blocklist().contains(s)) {
                continue;
            }
            symbols.add(s);
        }
        daily.download(symbols, from, to, lastDay);
        long start = from.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        LocalDate endDay = lastDay.plusDays(1);
        int days = (int) (endDay.toEpochDay() - from.atDay(1).toEpochDay());
        Map<String, Series> dailySeries = new LinkedHashMap<>();
        for (String s : symbols) {
            Series x = daily.load(s, start, days, from, to, lastDay);
            if (x != null) dailySeries.put(s, x);
        }
        // 每天的标的池：前一天成交额前 100
        Map<String, boolean[]> pool = new LinkedHashMap<>();
        for (int d = 1; d < days; d++) {
            int prev = d - 1;
            List<String> top = dailySeries.values().stream().filter(x -> x.has(prev))
                    .sorted(Comparator.comparingDouble((Series x) -> -x.quoteVolume[prev])).limit(TOP).map(x -> x.symbol).toList();
            for (String s : top) pool.computeIfAbsent(s, k -> new boolean[days])[d] = true;
        }
        System.out.println("标的池里出现过的币：" + pool.size() + " 个");

        int minutes = days * 1440;
        Archive minute = new Archive(dataDir.resolve("scalp-1m"), "1m");
        minute.download(List.of("BTCUSDT"), from, to, lastDay);
        Series btc = minute.load("BTCUSDT", start, minutes, from, to, lastDay);

        ScalpStudy study = new ScalpStudy();
        try (ExecutorService ex = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> fs = new ArrayList<>();
            for (var e : pool.entrySet()) {
                fs.add(ex.submit(() -> {
                    String sym = e.getKey();
                    boolean[] in = e.getValue();
                    Set<YearMonth> months = new java.util.TreeSet<>();
                    for (int d = 0; d < days; d++) if (in[d]) months.add(YearMonth.from(from.atDay(1).plusDays(d)));
                    for (YearMonth m : months) {
                        if (m.equals(YearMonth.from(lastDay))) {
                            for (LocalDate d = lastDay.withDayOfMonth(1); !d.isAfter(lastDay); d = d.plusDays(1)) minute.fetch(minute.dailyPath(sym, d));
                        } else {
                            minute.fetch(minute.monthlyPath(sym, m));
                        }
                    }
                    Series s = minute.load(sym, start, minutes, from, to, lastDay);
                    if (s != null) study.simulate(s, btc, in);
                    deleteQuietly(dataDir.resolve("scalp-1m/data/spot/monthly/klines/" + sym));
                    deleteQuietly(dataDir.resolve("scalp-1m/data/spot/daily/klines/" + sym));
                    return null;
                }));
            }
            int done = 0;
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (Exception e) {
                    System.out.println("跳过一个币：" + e.getMessage());
                }
                if (++done % 25 == 0) System.out.println("完成 " + done + "/" + fs.size());
            }
        }
        int splitDay = (int) (split.toEpochDay() - from.atDay(1).toEpochDay());
        String md = study.report(splitDay, from, split, lastDay, pool.size());
        Files.writeString(outDir.resolve("report.md"), md);
        System.out.println(md);
    }

    static void deleteQuietly(Path dir) {
        if (!Files.exists(dir)) return;
        try (var w = Files.walk(dir)) {
            w.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不影响结果
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    /** 一个币：找三种买点的信号，每个信号按 12 种卖法各算一笔，并配一笔同日随机分钟的对照。 */
    void simulate(Series s, Series btc, boolean[] in) {
        int n = s.size();
        SplittableRandom rnd = new SplittableRandom(s.symbol.hashCode());
        for (int e = 0; e < ENTRY.length; e++) {
            int next = 0;
            for (int i = 65; i < n - 1; i++) {
                if (i < next) continue;
                int d = i / 1440;
                if (!in[d] || !full(s, i - 64, i + 1)) continue;
                if (!signal(e, s, btc, i)) continue;
                next = i + 60;
                // 随机对照：同一天、同一个币里随机一个有数据的分钟
                int r = -1;
                for (int k = 0; k < 20 && r < 0; k++) {
                    int c = d * 1440 + rnd.nextInt(1440);
                    if (c + 1 < n && s.has(c) && s.has(c + 1)) r = c;
                }
                for (Exit x : EXITS) {
                    double[] a = trade(s, i + 1, x, SLIP);
                    if (a == null) continue;
                    double[] a3 = trade(s, i + 1, x, SLIP * 3);
                    double[] b = r < 0 ? null : trade(s, r + 1, x, SLIP);
                    double[] b3 = r < 0 ? null : trade(s, r + 1, x, SLIP * 3);
                    results.computeIfAbsent(e + "|" + x.name(), k -> java.util.Collections.synchronizedList(new ArrayList<>()))
                            .add(new double[]{a[0], d, a3[0], b == null ? Double.NaN : b[0], b3 == null ? Double.NaN : b3[0], a[1], a[2]});
                }
            }
        }
    }

    static boolean full(Series s, int from, int to) {
        for (int k = from; k <= to; k++) if (!s.has(k)) return false;
        return true;
    }

    static boolean signal(int e, Series s, Series btc, int i) {
        double c = s.close[i];
        switch (e) {
            case 0 -> {
                double hh = 0, vol = 0;
                for (int k = i - 60; k < i; k++) {
                    hh = Math.max(hh, s.high[k]);
                    vol += s.quoteVolume[k];
                }
                double r15 = c / s.close[i - 15] - 1;
                return c > hh && s.quoteVolume[i] >= 5 * vol / 60 && r15 >= 0.01 && r15 <= 0.06;
            }
            case 1 -> {
                if (btc == null || !btc.has(i) || !btc.has(i - 5)) return false;
                double r5 = c / s.close[i - 5] - 1;
                double b5 = btc.close[i] / btc.close[i - 5] - 1;
                double recent = 0, before = 0;
                for (int k = i - 4; k <= i; k++) recent += s.quoteVolume[k];
                for (int k = i - 64; k < i - 4; k++) before += s.quoteVolume[k];
                return r5 >= 0.02 && r5 - b5 >= 0.02 && recent >= 3 * before / 60 * 5;
            }
            default -> {
                double hh = 0;
                for (int k = i - 60; k < i; k++) hh = Math.max(hh, s.high[k]);
                double r60 = c / s.close[i - 60] - 1;
                double pull = 1 - s.close[i - 1] / hh;
                return r60 >= 0.04 && pull >= 0.015 && c > s.high[i - 1];
            }
        }
    }

    /** j 分钟开盘买入，按卖法走；返回 {净收益%, 卖出所在分钟, 持有分钟}，数据不够返回 null。 */
    static double[] trade(Series s, int j, Exit x, double slip) {
        if (!s.has(j)) return null;
        double p = s.open[j];
        double buy = p * (1 + slip) * (1 + FEE);
        double stopPx = p * (1 - x.stop());
        double peak = p;
        boolean armed = false;
        double exit = Double.NaN;
        int k = j, last = j;
        for (; k < Math.min(s.size(), j + MAX_HOLD); k++) {
            if (!s.has(k)) continue;
            last = k;
            double o = s.open[k], h = s.high[k], l = s.low[k];
            if (l <= stopPx) {
                exit = Math.min(o, stopPx);
                break;
            }
            if (armed) {
                double trailPx = peak * (1 - x.trail());
                if (l <= trailPx) {
                    exit = Math.min(o, trailPx);
                    break;
                }
            }
            peak = Math.max(peak, h);
            if (!armed && peak >= p * (1 + x.arm())) armed = true;
        }
        if (Double.isNaN(exit)) {
            k = last;
            exit = s.close[last];
        }
        double net = (exit * (1 - slip) * (1 - FEE) / buy - 1) * 100;
        return new double[]{net, k, k - j};
    }

    String report(int splitDay, YearMonth from, LocalDate split, LocalDate lastDay, int poolSize) {
        StringBuilder b = new StringBuilder("# 超短线检验（1 分钟 K 线，" + from + " ～ " + lastDay + "）\n\n");
        b.append("标的：每天前一天成交额前 ").append(TOP).append(" 的山寨（共 ").append(poolSize)
                .append(" 个币出现过，含后来下架的）。信号那一分钟收盘确认、下一分钟开盘买；止损按止损价成交（跳空按开盘价），")
                .append("同一分钟先算止损再算保护，保护线只用上一分钟为止的最高价。成本：手续费 0.1%×2 + 滑点每边 0.05%（另列 ×3）。")
                .append("「随机」= 同一个币同一天随机一个分钟买入、同样卖法。区间按天聚类；区间全在 0 以上才算能赚。")
                .append("每笔按 1,000 U 独立计算，不受资金上限限制，所以这里只看每笔期望，不代表组合收益。\n\n");
        record Row(int e, Exit x, List<double[]> cal, List<double[]> val) {
        }
        List<Row> rows = new ArrayList<>();
        for (int e = 0; e < ENTRY.length; e++) {
            for (Exit x : EXITS) {
                List<double[]> all = results.getOrDefault(e + "|" + x.name(), List.of());
                rows.add(new Row(e, x, all.stream().filter(r -> r[1] < splitDay).toList(), all.stream().filter(r -> r[1] >= splitDay).toList()));
            }
        }
        for (String period : new String[]{"选参数期（" + from + " ～ " + split.minusDays(1) + "）", "验证期（" + split + " ～ " + lastDay + "）"}) {
            boolean cal = period.startsWith("选");
            b.append("## ").append(period).append("\n\n");
            b.append("| 买点 | 卖法 | 笔数 | 每笔净收益% | 95% 区间 | 滑点×3 | 胜率 | 平均赚% | 平均亏% | 随机% | 比随机多% | 区间 | 平均持有(分钟) |\n");
            b.append("|---|---|---:|---:|---|---:|---:|---:|---:|---:|---:|---|---:|\n");
            for (Row r : rows) {
                List<double[]> v = cal ? r.cal() : r.val();
                if (v.size() < 2) continue;
                double[] ci = PumpStudy.clusterCi(v.stream().map(x -> new double[]{x[0], x[1]}).toList());
                double m3 = v.stream().mapToDouble(x -> x[2]).average().orElse(Double.NaN);
                double win = v.stream().filter(x -> x[0] > 0).count() * 100.0 / v.size();
                double aw = v.stream().filter(x -> x[0] > 0).mapToDouble(x -> x[0]).average().orElse(0);
                double al = v.stream().filter(x -> x[0] <= 0).mapToDouble(x -> x[0]).average().orElse(0);
                List<double[]> paired = v.stream().filter(x -> !Double.isNaN(x[3])).toList();
                double rm = paired.stream().mapToDouble(x -> x[3]).average().orElse(Double.NaN);
                double[] ex = PumpStudy.clusterCi(paired.stream().map(x -> new double[]{x[0] - x[3], x[1]}).toList());
                double hold = v.stream().mapToDouble(x -> x[6]).average().orElse(0);
                b.append(String.format(Locale.ROOT, "| %s | %s | %d | %.3f | %.3f ~ %.3f | %.3f | %.0f%% | %.2f | %.2f | %.3f | %.3f | %.3f ~ %.3f | %.0f |%n",
                        ENTRY[r.e()], r.x().name(), v.size(), ci[0], ci[1], ci[2], m3, win, aw, al, rm, ex[0], ex[1], ex[2], hold));
            }
            b.append('\n');
        }
        Row best = rows.stream().filter(r -> r.cal().size() >= 100)
                .max(Comparator.comparingDouble(r -> r.cal().stream().mapToDouble(x -> x[0]).average().orElse(-1e9))).orElse(null);
        if (best != null) {
            double[] c = PumpStudy.clusterCi(best.cal().stream().map(x -> new double[]{x[0], x[1]}).toList());
            double[] v = PumpStudy.clusterCi(best.val().stream().map(x -> new double[]{x[0], x[1]}).toList());
            b.append(String.format(Locale.ROOT, "## 结论用的一组（选参数期每笔最好，看结果前定好的选法）%n%n**%s + %s**：选参数期 %d 笔，每笔 %.3f%%（%.3f ~ %.3f）；"
                            + "验证期 %d 笔，每笔 %.3f%%（%.3f ~ %.3f）。%n%n",
                    ENTRY[best.e()], best.x().name(), best.cal().size(), c[0], c[1], c[2], best.val().size(), v[0], v[1], v[2]));
            // 验证期按月：每笔 1,000 U 的合计盈亏，以及按卖出时间累计的最大回撤（不限同时持仓）
            Map<String, double[]> months = new TreeMap<>();
            List<double[]> byExit = new ArrayList<>(best.val());
            byExit.sort(Comparator.comparingDouble(x -> x[5]));
            double eq = 0, peak = 0, dd = 0;
            for (double[] x : byExit) {
                eq += x[0] * 10;
                peak = Math.max(peak, eq);
                dd = Math.max(dd, peak - eq);
                String m = YearMonth.from(from.atDay(1).plusDays((long) x[1])).toString();
                months.computeIfAbsent(m, k -> new double[2])[0] += x[0] * 10;
                months.get(m)[1]++;
            }
            b.append(String.format(Locale.ROOT, "验证期每笔 1,000 U 合计 %.0f U，按卖出时间累计的最大回撤 %.0f U（不限同时持仓，仅供参考）。%n%n| 月份 | 笔数 | 合计 U |%n|---|---:|---:|%n", eq, dd));
            months.forEach((m, a) -> b.append(String.format(Locale.ROOT, "| %s | %.0f | %.0f |%n", m, a[1], a[0])));
        }
        return b.toString();
    }
}
