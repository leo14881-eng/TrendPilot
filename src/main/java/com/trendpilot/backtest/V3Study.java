package com.trendpilot.backtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.v3.TrendRules;

/**
 * V3 趋势跟踪回测（日线，规则见 {@link TrendRules}，事先登记、不调参）。
 * 日线回测与线上的差别：止损按当天最低价触及（按止损价成交，跳空按开盘价），线上按 1h 收盘确认；跟踪止损的最高价只用前一天为止的。
 * 对照：每笔买入同一天，从标的池随机挑一个没信号的币，同样止损、同样仓位（"随便买 + 同样卖法"）；以及 BTC 一直持有。
 */
public final class V3Study {

    static final double FEE = 0.001;

    record Pos(String sym, int entryDay, double entry, double qty, double cost, double initStop, double atr, double peak) {
    }

    record Trade(String sym, int entryDay, int exitDay, double cost, double pnl, String reason, boolean random) {
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "backtest-data");
        Path outDir = Path.of(args.length > 1 ? args[1] : "backtest-out");
        YearMonth from = YearMonth.parse(args.length > 2 ? args[2] : "2019-07");
        YearMonth to = YearMonth.parse(args.length > 3 ? args[3] : "2026-09");
        LocalDate lastDay = args.length > 4 ? LocalDate.parse(args[4]) : LocalDate.of(2026, 10, 14);
        LocalDate tradeFrom = LocalDate.parse(args.length > 5 ? args[5] : "2020-01-01");
        Files.createDirectories(outDir);

        StrategyParams params = BacktestMain.loadParams();
        Archive daily = new Archive(dataDir.resolve("scalp-1d"), "1d");
        List<String> all = daily.usdtSymbols();
        List<String> symbols = new ArrayList<>();
        for (String s : all) {
            String base = s.substring(0, s.length() - 4);
            if (params.universe().excludedBaseAssets().contains(base) || params.universe().tokenizedStockBaseAssets().contains(base)
                    || params.universe().blocklist().contains(s)) continue;
            symbols.add(s);
        }
        daily.download(symbols, from, to, lastDay);
        long start = from.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        int days = (int) (lastDay.plusDays(1).toEpochDay() - from.atDay(1).toEpochDay());
        Map<String, Series> data = new LinkedHashMap<>();
        for (String s : symbols) {
            Series x = daily.load(s, start, days, from, to, lastDay);
            if (x != null) data.put(s, x);
        }
        Series btc = data.remove("BTCUSDT");
        int fromDay = (int) (tradeFrom.toEpochDay() - from.atDay(1).toEpochDay());
        String md = run(data, btc, fromDay, days, from.atDay(1), outDir);
        Files.writeString(outDir.resolve("report.md"), md);
        System.out.println(md);
    }

    /** 某个币到第 end 天为止连续的已收盘日线（有缺口就只取缺口之后的）。 */
    static double[][] window(Series s, int end, int n) {
        int startIdx = end - n + 1;
        if (startIdx < 0) return null;
        double[][] w = new double[4][n];
        for (int i = 0; i < n; i++) {
            int k = startIdx + i;
            if (!s.has(k)) return null;
            w[0][i] = s.high[k];
            w[1][i] = s.low[k];
            w[2][i] = s.close[k];
            w[3][i] = s.quoteVolume[k];
        }
        return w;
    }

    static String run(Map<String, Series> data, Series btc, int fromDay, int days, LocalDate day0, Path outDir) throws Exception {
        List<Pos> open = new ArrayList<>();
        List<Pos> openRandom = new ArrayList<>();
        List<Trade> trades = new ArrayList<>();
        double realized = 0;
        double[] equity = new double[days];
        int regimeOn = 0, regimeDays = 0;
        SplittableRandom rnd = new SplittableRandom(20261017);
        for (int day = fromDay; day < days; day++) {
            final int d = day;
            final int y = d - 1; // 判断用的最后一根已收盘日线
            // 1. 管理持仓（当天的开高低收）
            realized += manage(open, data, d, trades, false);
            manage(openRandom, data, d, trades, true);
            // 2. 标的池和大盘
            List<Series> pool = data.values().stream().filter(s -> s.has(y) && s.has(d))
                    .sorted(Comparator.comparingDouble((Series s) -> -s.quoteVolume[y])).limit(TrendRules.POOL).toList();
            double[][] bw = btc == null ? null : window(btc, y, 51);
            boolean btcUp = bw != null && bw[2][50] > TrendRules.sma(bw[2], 50, 50);
            int above = 0, counted = 0;
            Map<Series, double[][]> windows = new LinkedHashMap<>();
            for (Series s : pool) {
                double[][] w = window(s, y, TrendRules.MIN_DAYS);
                if (w == null) continue;
                windows.put(s, w);
                counted++;
                if (w[2][TrendRules.MIN_DAYS - 1] > TrendRules.sma(w[2], TrendRules.MIN_DAYS - 1, 20)) above++;
            }
            boolean regime = btcUp && counted > 0 && above >= TrendRules.BREADTH_MIN * counted;
            regimeDays++;
            if (regime) regimeOn++;
            // 3. 买入（当天开盘）
            if (regime) {
                Set<String> held = new HashSet<>();
                open.forEach(p -> held.add(p.sym()));
                List<Series> signals = new ArrayList<>();
                List<Series> quiet = new ArrayList<>();
                for (var e : windows.entrySet()) {
                    double[][] w = e.getValue();
                    if (TrendRules.breakout(w[0], w[2], w[3], TrendRules.MIN_DAYS - 1)) signals.add(e.getKey());
                    else quiet.add(e.getKey());
                }
                for (Series s : signals) {
                    if (open.size() >= TrendRules.MAX_POSITIONS || held.contains(s.symbol)) continue;
                    double used = open.stream().mapToDouble(Pos::cost).sum();
                    Pos p = enter(s, windows.get(s), d, TrendRules.CAPITAL - used);
                    if (p == null) continue;
                    open.add(p);
                    held.add(s.symbol);
                    if (!quiet.isEmpty()) {
                        Series r = quiet.get(rnd.nextInt(quiet.size()));
                        Pos rp = enter(r, windows.get(r), d, Double.MAX_VALUE);
                        if (rp != null) openRandom.add(rp);
                    }
                }
            }
            double floating = 0;
            for (Pos p : open) {
                Series s = data.get(p.sym());
                int k = d;
                while (k > p.entryDay() && !s.has(k)) k--;
                double c = s.has(k) ? s.close[k] : p.entry();
                floating += p.qty() * c * (1 - Costs.impact(s.quoteVolume[Math.max(0, k)], p.cost())) * (1 - FEE) - p.cost();
            }
            equity[d] = realized + floating;
        }
        // 期末按最后收盘结算
        for (List<Pos> list : List.of(open, openRandom)) {
            boolean random = list == openRandom;
            for (Pos p : list) {
                Series s = data.get(p.sym());
                int k = days - 1;
                while (k > p.entryDay() && !s.has(k)) k--;
                double pnl = p.qty() * s.close[k] * (1 - FEE) - p.cost();
                trades.add(new Trade(p.sym(), p.entryDay(), days - 1, p.cost(), pnl, "END", random));
            }
        }
        writeCsv(trades, day0, outDir);
        return report(trades, equity, fromDay, days, day0, btc, regimeOn, regimeDays);
    }

    static Pos enter(Series s, double[][] w, int d, double cash) {
        int end = TrendRules.MIN_DAYS - 1;
        double atr = TrendRules.atr(w[0], w[1], w[2], end);
        double price = s.open[d];
        if (!(price > 0) || !(atr > 0)) return null;
        double dist = TrendRules.stopDistance(atr, price);
        double notional = Math.min(TrendRules.notional(dist), cash);
        if (notional < 100) return null;
        double imp = Costs.impact(s.quoteVolume[d - 1], notional);
        double fill = price * (1 + imp);
        double qty = notional / fill;
        double cost = notional * (1 + FEE);
        return new Pos(s.symbol, d, fill, qty, cost, price * (1 - dist), atr, price);
    }

    /** 当天管理：先看止损（最低价触及，按止损价或跳空开盘价），再看收盘跌破 10 日低点；最后用当天最高价更新峰值。 */
    static double manage(List<Pos> list, Map<String, Series> data, int d, List<Trade> trades, boolean random) {
        double pnlSum = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            Pos p = list.get(i);
            Series s = data.get(p.sym());
            if (!s.has(d)) {
                if (d > s.lastIndex()) { // 下架：按最后收盘价打 0.5% 卖出
                    double pnl = p.qty() * s.close[s.lastIndex()] * (1 - 0.005) * (1 - FEE) - p.cost();
                    trades.add(new Trade(p.sym(), p.entryDay(), d, p.cost(), pnl, "DELISTED", random));
                    list.remove(i);
                    pnlSum += pnl;
                }
                continue;
            }
            double stop = TrendRules.stopLine(p.initStop(), p.peak(), p.atr());
            double sell = Double.NaN;
            String why = null;
            if (s.low[d] <= stop) {
                sell = Math.min(s.open[d], stop);
                why = stop > p.initStop() + 1e-12 ? "TRAIL_STOP" : "STOP_LOSS";
            } else if (d - p.entryDay() >= 1) {
                double[][] w = window(s, d, TrendRules.EXIT_LOW_DAYS + 1);
                if (w != null && TrendRules.belowExitLow(w[1], w[2], TrendRules.EXIT_LOW_DAYS)) {
                    sell = s.close[d];
                    why = "LOW10_EXIT";
                }
            }
            if (why != null) {
                double imp = Costs.impact(s.quoteVolume[d], p.cost());
                double pnl = p.qty() * sell * (1 - imp) * (1 - FEE) - p.cost();
                trades.add(new Trade(p.sym(), p.entryDay(), d, p.cost(), pnl, why, random));
                list.remove(i);
                pnlSum += pnl;
            } else {
                list.set(i, new Pos(p.sym(), p.entryDay(), p.entry(), p.qty(), p.cost(), p.initStop(), p.atr(),
                        Math.max(p.peak(), s.high[d])));
            }
        }
        return pnlSum;
    }

    static void writeCsv(List<Trade> trades, LocalDate day0, Path outDir) throws Exception {
        List<String> l = new ArrayList<>();
        l.add("symbol,entry,exit,cost,pnl,pct,reason,random");
        for (Trade t : trades) {
            l.add(String.format(Locale.ROOT, "%s,%s,%s,%.2f,%.2f,%.3f,%s,%s", t.sym(), day0.plusDays(t.entryDay()), day0.plusDays(t.exitDay()),
                    t.cost(), t.pnl(), t.pnl() / t.cost() * 100, t.reason(), t.random()));
        }
        Files.write(outDir.resolve("v3-trades.csv"), l);
    }

    static String report(List<Trade> trades, double[] equity, int fromDay, int days, LocalDate day0, Series btc, int regimeOn,
                         int regimeDays) {
        StringBuilder b = new StringBuilder("# V3 趋势跟踪回测（日线，" + day0.plusDays(fromDay) + " ～ " + day0.plusDays(days - 1) + "）\n\n");
        b.append("规则事先登记、没有调参（见 TrendRules）：大盘多头时（BTC 在 50 日均线上方、池子过半在 20 日均线上方）买入创 20 日新高且放量的山寨；")
                .append("初始止损 2×ATR（5%～15%），之后跟踪止损 = 最高价 − 3×ATR，或日线收盘跌破前 10 天最低价卖出；每笔风险 200 U，最多 10 笔、总占用 ≤ 20,000 U。")
                .append("成本：手续费 0.1%×2 + 按成交额估算冲击。日线回测的止损按当天最低价触及成交（跳空按开盘价）。")
                .append("「随机」= 每笔买入同一天从池子随机挑一个没信号的币，同样止损、同样仓位（不受笔数和资金限制）。\n\n");
        b.append(String.format(Locale.ROOT, "大盘过滤打开的天数：%d / %d（%.0f%%）。%n%n", regimeOn, regimeDays, regimeOn * 100.0 / regimeDays));
        b.append("| 期间 | 笔数 | 胜率 | 平均赚% | 平均亏% | 盈亏比 | 每笔% | 合计 U | 最大回撤 U | 最大单笔赚% | 随机笔数 | 随机每笔% | 随机合计 U | BTC 持有% |\n");
        b.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        Map<String, int[]> periods = new TreeMap<>();
        for (int d = fromDay; d < days; d++) {
            String yr = String.valueOf(day0.plusDays(d).getYear());
            periods.computeIfAbsent(yr, k -> new int[]{Integer.MAX_VALUE, 0});
            periods.get(yr)[0] = Math.min(periods.get(yr)[0], d);
            periods.get(yr)[1] = Math.max(periods.get(yr)[1], d);
        }
        Map<String, int[]> rows = new LinkedHashMap<>(periods);
        rows.put("全部", new int[]{fromDay, days - 1});
        for (var e : rows.entrySet()) {
            int a = e.getValue()[0], z = e.getValue()[1];
            List<Trade> ts = trades.stream().filter(t -> !t.random() && t.exitDay() >= a && t.exitDay() <= z).toList();
            List<Trade> rs = trades.stream().filter(t -> t.random() && t.exitDay() >= a && t.exitDay() <= z).toList();
            double win = ts.isEmpty() ? 0 : ts.stream().filter(t -> t.pnl() > 0).count() * 100.0 / ts.size();
            double aw = ts.stream().filter(t -> t.pnl() > 0).mapToDouble(t -> t.pnl() / t.cost() * 100).average().orElse(0);
            double al = ts.stream().filter(t -> t.pnl() <= 0).mapToDouble(t -> t.pnl() / t.cost() * 100).average().orElse(0);
            double mean = ts.stream().mapToDouble(t -> t.pnl() / t.cost() * 100).average().orElse(0);
            double sum = ts.stream().mapToDouble(Trade::pnl).sum();
            double best = ts.stream().mapToDouble(t -> t.pnl() / t.cost() * 100).max().orElse(0);
            double rmean = rs.stream().mapToDouble(t -> t.pnl() / t.cost() * 100).average().orElse(0);
            double rsum = rs.stream().mapToDouble(Trade::pnl).sum();
            double peak = equity[a > 0 ? a - 1 : 0], dd = 0;
            for (int d = a; d <= z; d++) {
                peak = Math.max(peak, equity[d]);
                dd = Math.max(dd, peak - equity[d]);
            }
            double btcRet = Double.NaN;
            if (btc != null) {
                int i = a, j = z;
                while (i < j && !btc.has(i)) i++;
                while (j > i && !btc.has(j)) j--;
                if (btc.has(i) && btc.has(j)) btcRet = (btc.close[j] / btc.open[i] - 1) * 100;
            }
            b.append(String.format(Locale.ROOT, "| %s | %d | %.0f%% | %.2f | %.2f | %.2f | %.2f | %,.0f | %,.0f | %.0f | %d | %.2f | %,.0f | %.0f |%n",
                    e.getKey(), ts.size(), win, aw, al, al == 0 ? 0 : -aw / al, mean, sum, dd, best, rs.size(), rmean, rsum, btcRet));
        }
        Map<String, double[]> reasons = new TreeMap<>();
        for (Trade t : trades) {
            if (t.random()) continue;
            double[] a = reasons.computeIfAbsent(t.reason(), k -> new double[2]);
            a[0]++;
            a[1] += t.pnl();
        }
        b.append("\n| 卖出原因 | 笔数 | 合计 U |\n|---|---:|---:|\n");
        reasons.forEach((k, v) -> b.append(String.format(Locale.ROOT, "| %s | %.0f | %,.0f |%n", k, v[0], v[1])));
        return b.toString();
    }
}
