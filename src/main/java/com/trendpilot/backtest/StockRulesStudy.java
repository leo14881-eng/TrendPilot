package com.trendpilot.backtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.trendpilot.config.StrategyParams;

/**
 * 美股 StockPilot 策略 V1 原样套到币安现货日线上（docs/美股与币圈逻辑对比.md 第 5 节的验证）。
 * 规则、门槛和顺序照抄 StockPilot 的 strategy 包（评分、入场结构、回调加仓、结构破坏只盈利卖、
 * 利润保护线、三道止损），也照抄它自己的回测口径：每天收盘跑一次 Main 再跑一次 Monitor，按收盘价成交。
 * 换成币圈的只有三处：基准 SPY/QQQ → BTC/ETH；收盘 → UTC 0 点；成交额用 USDT 成交额。没有调任何参数。
 * <pre>
 * java -cp ... com.trendpilot.backtest.StockRulesStudy 数据目录 输出目录 [起始月 2022-06] [最后一天 2026-10-14] [开始交易 2022-10-15]
 * </pre>
 */
public final class StockRulesStudy {

    static final long DAY_MS = 24 * Series.H;
    static final String BENCH_A = MarketData.BTC;
    static final String BENCH_B = MarketData.ETH;
    static final double CAPITAL = 100_000;
    static final double TRANCHE = 1000;
    static final int MAX_TRANCHES = 5;
    static final int LOOKBACK = 60;
    static final int REQUIRED_BARS = 22;
    static final int STOP_BARS = 51;
    /** 币安现货单边手续费；冲击按成交额分档另加（Costs.impactBps）。 */
    static final double FEE = 0.001;

    /** StockPilot StrategyConfig.v1Params 的取值（价格低于 2 的规则对币价没有意义，不用）。 */
    static final double MIN_DOLLAR_VOLUME = 10_000_000, MAX_RET5 = 35, MAX_RET20 = 80, MIN_RET20 = -8, BELOW_TREND = 0.94,
            MAX_SMA_EXT = 30;
    static final double EARLY_MIN_SCORE = 62, EARLY_MIN_R5 = -4, EARLY_MAX_R5 = 8, EARLY_MIN_R20 = -3, EARLY_MAX_R20 = 18,
            EARLY_MIN_DIST = -3, EARLY_MAX_DIST = 8, EARLY_MIN_VR = 1.35, EARLY_MIN_RANGE = 0.45;
    static final double TREND_MIN_SCORE = 68, PULLBACK_MIN_R5 = -3, PULLBACK_MIN_DIST = -3, PULLBACK_MAX_DIST = 12,
            MOMENTUM_MAX_R5 = 18, MOMENTUM_MAX_R20 = 45, MOMENTUM_MAX_DIST = 18;
    static final double BROKEN_DIST = -6, RELATIVE_WEAK = -5;
    static final double ADD_TRIGGER = -1;
    static final double[] FLOOR_MFE = {1, 8, 15, 30};
    static final double[] FLOOR_RATIO = {0.50, 0.65, 0.75, 0.80};
    static final double FLOOR_MIN = 0.10;
    static final double CRASH_LOSS = 25, CRASH_DAY_DROP = 15, CRASH_VOLUME = 3, TREND_MIN_LOSS = 10, TREND_ATR = 3;

    private static final java.util.regex.Pattern LEVERAGED = java.util.regex.Pattern.compile(
            "(BTC|ETH|BNB|ADA|XRP|DOT|LINK|TRX|EOS|XTZ|LTC|BCH|FIL|SXP|YFI|SUSHI|UNI|AAVE|1INCH|XLM)(UP|DOWN)");

    public record Variant(String name, int topN, boolean stops, double flatFee) {
    }

    /** 一天收盘时一个币的日线特征（同 StockPilot Snapshot）。 */
    record Snap(double price, double ret5, double ret20, double sma20, double dollarVolume20, double vol20, double volumeRatio,
                double range) {
        double dist() {
            return (price / sma20 - 1) * 100;
        }
    }

    record Stop(double prevClose, double prevSma50, double last49Sum, double avgVolume20, double atr20Pct) {
        double sma50With(double price) {
            return (last49Sum + price) / 50;
        }
    }

    static final class Pos {
        final String symbol;
        final int openDay;
        final double feeRate;
        final String structure;
        double cost, qty;
        int tranches;
        double mfe = Double.NaN, mae = Double.NaN;
        Double swingHigh, pullbackLow, prevRel;
        boolean pullbackSeen, rebound;
        double lastPrice;

        Pos(String symbol, int openDay, double feeRate, String structure) {
            this.symbol = symbol;
            this.openDay = openDay;
            this.feeRate = feeRate;
            this.structure = structure;
        }

        void add(double price) {
            cost += TRANCHE;
            qty += TRANCHE / price;
            tranches++;
            lastPrice = price;
        }

        double net(double price) {
            double gross = qty * price;
            return gross - cost - feeRate * cost - feeRate * gross;
        }

        double netPct(double price) {
            return net(price) / cost * 100;
        }

        double observe(double price) {
            lastPrice = price;
            double r = netPct(price);
            mfe = Double.isNaN(mfe) ? r : Math.max(mfe, r);
            mae = Double.isNaN(mae) ? r : Math.min(mae, r);
            return r;
        }
    }

    public record Closed(String symbol, int openDay, int closeDay, double cost, double pnl, double pct, String reason,
                         String structure, int tranches) {
    }

    public record Result(Variant v, double[] equity, List<Closed> closed, Map<String, Pos> open, int buys, int adds,
                         double avgCostUsed, double maxDdPct, double maxDdUsd) {
    }

    final Map<String, Series> series;
    final int days;
    final long start;
    /** 第 d 天收盘时的特征，懒算缓存。 */
    private final Map<String, Snap[]> snaps = new HashMap<>();
    private final Map<String, Stop[]> stops = new HashMap<>();
    private final Map<Integer, List<String>> universes = new HashMap<>();

    StockRulesStudy(Map<String, Series> series, long start, int days) {
        this.series = series;
        this.start = start;
        this.days = days;
    }

    // ---------- 特征 ----------

    Snap snap(String symbol, int d) {
        Series s = series.get(symbol);
        if (s == null || d < 0 || d >= days) return null;
        Snap[] arr = snaps.computeIfAbsent(symbol, k -> new Snap[days]);
        if (arr[d] == null && s.has(d)) arr[d] = computeSnap(s, d);
        return arr[d];
    }

    /** 同 StockPilot Snapshots.fromBars：只用截至 d（含）、最近 60 天里有效的日线，至少 22 根。 */
    static Snap computeSnap(Series s, int d) {
        if (!s.has(d)) return null;
        List<Integer> rows = new ArrayList<>();
        for (int i = Math.max(0, d - LOOKBACK + 1); i <= d; i++) if (s.has(i)) rows.add(i);
        int n = rows.size();
        if (n < REQUIRED_BARS) return null;
        double[] p = new double[n];
        for (int k = 0; k < n; k++) p[k] = s.close[rows.get(k)];
        double price = p[n - 1];
        double ret5 = (price / p[n - 6] - 1) * 100;
        double ret20 = (price / p[n - 21] - 1) * 100;
        double closeSum = 0, dollar = 0;
        for (int k = n - 20; k < n; k++) {
            closeSum += p[k];
            dollar += s.quoteVolume[rows.get(k)];
        }
        double sma20 = closeSum / 20;
        double avgDollar = dollar / 20;
        double volumeRatio = avgDollar == 0 ? 0 : s.quoteVolume[rows.get(n - 1)] / avgDollar;
        double squares = 0;
        for (int k = n - 19; k < n; k++) {
            double r = (p[k] / p[k - 1] - 1) * 100;
            squares += r * r;
        }
        double vol = Math.sqrt(squares / 19);
        double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
        for (int k = n - 20; k < n; k++) {
            int i = rows.get(k);
            hi = Math.max(hi, s.high[i]);
            lo = Math.min(lo, s.low[i]);
        }
        double range = hi <= lo ? 0.5 : (price - lo) / (hi - lo);
        return new Snap(price, ret5, ret20, sma20, avgDollar, vol, volumeRatio, range);
    }

    /** 同 StockPilot StopRules.context：只用 d 之前已收盘的日线，至少 51 根。 */
    Stop stop(String symbol, int d) {
        Series s = series.get(symbol);
        if (s == null) return null;
        Stop[] arr = stops.computeIfAbsent(symbol, k -> new Stop[days]);
        if (arr[d] == null) arr[d] = computeStop(s, d);
        return arr[d];
    }

    static Stop computeStop(Series s, int d) {
        List<Integer> rows = new ArrayList<>();
        for (int i = Math.max(0, d - 60); i < d; i++) if (s.has(i)) rows.add(i);
        int n = rows.size();
        if (n < STOP_BARS) return null;
        double prevClose = s.close[rows.get(n - 1)];
        double sum49 = 0;
        for (int k = n - 49; k < n; k++) sum49 += s.close[rows.get(k)];
        double prevSma50 = (sum49 + s.close[rows.get(n - 50)]) / 50;
        double volume = 0, tr = 0;
        for (int k = n - 20; k < n; k++) {
            int i = rows.get(k);
            volume += s.quoteVolume[i];
            double prev = s.close[rows.get(k - 1)];
            tr += Math.max(s.high[i] - s.low[i], Math.max(Math.abs(s.high[i] - prev), Math.abs(s.low[i] - prev)));
        }
        return new Stop(prevClose, prevSma50, sum49, volume / 20, tr / 20 / prevClose * 100);
    }

    /** d 天收盘时的标的池：最近 20 天 USDT 成交额前 topN（含 BTC、ETH），要有当天 K 线和足够历史。 */
    List<String> universe(int d, int topN) {
        List<String> ranked = universes.computeIfAbsent(d, k -> series.keySet().stream()
                .filter(sym -> snap(sym, d) != null)
                .sorted(Comparator.comparingDouble((String sym) -> -snap(sym, d).dollarVolume20()).thenComparing(sym -> sym))
                .limit(200).toList());
        return ranked.subList(0, Math.min(topN, ranked.size()));
    }

    // ---------- 规则（照抄 StockPilot） ----------

    static double relative(Snap m, Snap a, Snap b) {
        return m.ret20() - (a.ret20() + b.ret20()) / 2;
    }

    static boolean rejected(Snap m) {
        return m.dollarVolume20() < MIN_DOLLAR_VOLUME || m.ret5() > MAX_RET5 || m.ret20() > MAX_RET20 || m.ret20() < MIN_RET20
                || m.price() < m.sma20() * BELOW_TREND || m.dist() > MAX_SMA_EXT;
    }

    static double score(Snap m, Snap a, Snap b) {
        double rel = relative(m, a, b);
        double liquidity = clip(5 + 5 * Math.log10(Math.max(m.dollarVolume20(), 1) / 10_000_000.0), 0, 15);
        double trend = clip(10 + 0.45 * m.ret20(), 0, 20);
        double momentum = clip(10 + 0.8 * m.ret5(), 0, 20);
        double relative = clip(12.5 + 0.8 * rel, 0, 25);
        double structure = clip(7 + 0.35 * m.dist(), 0, 10);
        double v = m.vol20();
        double volatility = v >= 1 && v <= 4.5 ? 10 : v <= 7 ? 6 : 1;
        return Math.round((liquidity + trend + momentum + relative + structure + volatility) * 100) / 100.0;
    }

    /** 入场结构，按 EARLY → PULLBACK → MOMENTUM 先命中先用；不满足返回 null。 */
    static String entry(Snap m, Snap a, Snap b) {
        if (rejected(m)) return null;
        double sc = score(m, a, b), r5 = m.ret5(), r20 = m.ret20(), dist = m.dist(), rel = relative(m, a, b);
        if (sc >= EARLY_MIN_SCORE && r5 >= EARLY_MIN_R5 && r5 <= EARLY_MAX_R5 && r20 >= EARLY_MIN_R20 && r20 <= EARLY_MAX_R20
                && dist >= EARLY_MIN_DIST && dist <= EARLY_MAX_DIST && m.volumeRatio() >= EARLY_MIN_VR && rel >= 0
                && m.range() >= EARLY_MIN_RANGE) return "EARLY_ACCUMULATION";
        if (sc >= TREND_MIN_SCORE && r5 > PULLBACK_MIN_R5 && r5 < 0 && r20 > 0 && dist >= PULLBACK_MIN_DIST
                && dist <= PULLBACK_MAX_DIST && rel > 0) return "PULLBACK_IN_TREND";
        if (sc >= TREND_MIN_SCORE && r5 >= 0 && r5 <= MOMENTUM_MAX_R5 && r20 <= MOMENTUM_MAX_R20 && dist <= MOMENTUM_MAX_DIST)
            return "MOMENTUM_TREND";
        return null;
    }

    static boolean broken(Snap m, Snap a, Snap b) {
        return m.dist() < BROKEN_DIST && m.ret20() < 0 && relative(m, a, b) < RELATIVE_WEAK;
    }

    static double floor(double mfe) {
        for (int k = FLOOR_MFE.length - 1; k >= 0; k--) {
            if (mfe >= FLOOR_MFE[k]) return Math.max(FLOOR_MIN, mfe * FLOOR_RATIO[k]);
        }
        return Double.NaN;
    }

    static boolean giveback(double mfe, double r) {
        double f = floor(mfe);
        return !Double.isNaN(f) && r > 0 && r <= f;
    }

    /** StopRules.check，收盘时调用（closingWindow = true）。 */
    static String stopReason(double r, double price, Stop ctx, double dayVolume) {
        if (!(r < 0)) return null;
        if (r <= -CRASH_LOSS) return "STOP_LOSS_CRASH";
        if (ctx == null) return null;
        double drop = (1 - price / ctx.prevClose()) * 100;
        if (ctx.avgVolume20() > 0 && drop >= CRASH_DAY_DROP && dayVolume >= CRASH_VOLUME * ctx.avgVolume20()) return "STOP_LOSS_CRASH_DAY";
        double allowed = Math.max(TREND_MIN_LOSS, TREND_ATR * ctx.atr20Pct());
        if (ctx.prevClose() < ctx.prevSma50() && price < ctx.sma50With(price) && r <= -allowed) return "STOP_LOSS_TREND_BREAK";
        return null;
    }

    static double clip(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---------- 模拟 ----------

    Result run(Variant v, int fromDay) {
        Map<String, Pos> book = new TreeMap<>();
        List<Closed> closed = new ArrayList<>();
        double realized = 0, costSum = 0, peak = CAPITAL, maxDd = 0, maxDdUsd = 0;
        int buys = 0, adds = 0;
        double[] equity = new double[days - fromDay];
        for (int d = fromDay; d < days; d++) {
            Snap a = snap(BENCH_A, d), b = snap(BENCH_B, d);
            // 下架：之后再也没有 K 线的持仓按最后收盘价平掉
            for (Pos p : new ArrayList<>(book.values())) {
                if (series.get(p.symbol).lastIndex() < d) {
                    double pnl = p.net(p.lastPrice);
                    realized += pnl;
                    closed.add(new Closed(p.symbol, p.openDay, d, p.cost, pnl, pnl / p.cost * 100, "DELISTED", p.structure, p.tranches));
                    book.remove(p.symbol);
                }
            }
            if (a != null && b != null) {
                // 1. BUY：池内全部合格的，按分数从高到低，受资金上限约束
                record Cand(String symbol, Snap m, String structure, double score) {
                }
                List<Cand> ready = new ArrayList<>();
                for (String sym : universe(d, v.topN())) {
                    if (book.containsKey(sym)) continue;
                    Snap m = snap(sym, d);
                    String st = entry(m, a, b);
                    if (st != null) ready.add(new Cand(sym, m, st, score(m, a, b)));
                }
                ready.sort(Comparator.comparingDouble((Cand c) -> -c.score()).thenComparing(Cand::symbol));
                List<String> opened = new ArrayList<>();
                for (Cand c : ready) {
                    if (openCost(book) + TRANCHE > CAPITAL + 1e-9) continue;
                    double fee = v.flatFee() > 0 ? v.flatFee() : FEE + Costs.impactBps(c.m().dollarVolume20()) / 10_000;
                    Pos p = new Pos(c.symbol(), d, fee, c.structure());
                    p.add(c.m().price());
                    p.observe(c.m().price());
                    p.prevRel = round4(relative(c.m(), a, b));
                    book.put(c.symbol(), p);
                    opened.add(c.symbol());
                    buys++;
                }
                // 2. 持仓：估值、回调加仓、结构破坏只盈利卖
                for (Pos p : new ArrayList<>(book.values())) {
                    if (opened.contains(p.symbol)) continue;
                    Snap m = snap(p.symbol, d);
                    if (m == null) continue;
                    double rBefore = p.observe(m.price());
                    boolean isBroken = broken(m, a, b);
                    double rel = round4(relative(m, a, b));
                    double high = p.swingHigh == null ? m.price() : Math.max(p.swingHigh, m.price());
                    double dd = (m.price() / high - 1) * 100;
                    p.swingHigh = high;
                    if (dd < ADD_TRIGGER) {
                        p.pullbackSeen = true;
                        p.pullbackLow = p.pullbackLow == null ? m.price() : Math.min(p.pullbackLow, m.price());
                    }
                    boolean eligible = p.pullbackSeen && !isBroken && p.pullbackLow != null && m.price() > p.pullbackLow
                            && p.prevRel != null && rel > p.prevRel;
                    p.prevRel = rel;
                    if (p.tranches < MAX_TRANCHES && eligible && entry(m, a, b) != null && openCost(book) + TRANCHE <= CAPITAL + 1e-9) {
                        p.add(m.price());
                        p.pullbackSeen = false;
                        p.pullbackLow = null;
                        adds++;
                    }
                    String exit = null;
                    if (isBroken) {
                        if (rBefore > 0) exit = "STRUCTURE_BROKEN_PROFIT_EXIT";
                        else p.rebound = true;
                    } else if (p.rebound && rBefore > 0) {
                        exit = "REBOUND_PROFIT_EXIT";
                    }
                    if (exit != null && p.net(m.price()) > 0) {
                        double pnl = p.net(m.price());
                        realized += pnl;
                        closed.add(new Closed(p.symbol, p.openDay, d, p.cost, pnl, pnl / p.cost * 100, exit, p.structure, p.tranches));
                        book.remove(p.symbol);
                    } else if (exit != null) {
                        p.rebound = true;
                    }
                }
            }
            // 3. Monitor：同一收盘价，利润保护和止损
            for (Pos p : new ArrayList<>(book.values())) {
                Snap m = snap(p.symbol, d);
                if (m == null) continue;
                double r = p.observe(m.price());
                String reason = v.stops() ? stopReason(r, m.price(), stop(p.symbol, d), series.get(p.symbol).quoteVolume[d]) : null;
                if (reason == null && giveback(p.mfe, r)) reason = "NET_PROFIT_GIVEBACK";
                if (reason != null) {
                    double pnl = p.net(m.price());
                    realized += pnl;
                    closed.add(new Closed(p.symbol, p.openDay, d, p.cost, pnl, pnl / p.cost * 100, reason, p.structure, p.tranches));
                    book.remove(p.symbol);
                }
            }
            double unrealized = 0;
            for (Pos p : book.values()) unrealized += p.net(p.lastPrice);
            double eq = CAPITAL + realized + unrealized;
            equity[d - fromDay] = eq;
            costSum += openCost(book);
            peak = Math.max(peak, eq);
            maxDd = Math.max(maxDd, (peak - eq) / peak * 100);
            maxDdUsd = Math.max(maxDdUsd, peak - eq);
        }
        return new Result(v, equity, closed, book, buys, adds, costSum / Math.max(1, days - fromDay), maxDd, maxDdUsd);
    }

    static double openCost(Map<String, Pos> book) {
        double c = 0;
        for (Pos p : book.values()) c += p.cost;
        return c;
    }

    static double round4(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }

    // ---------- 对照 ----------

    /** 一直持有一个币（换算成 10 万本金的权益曲线）。 */
    double[] hold(String symbol, int fromDay) {
        Series s = series.get(symbol);
        double[] eq = new double[days - fromDay];
        double base = s.lastClose(fromDay, 5);
        for (int d = fromDay; d < days; d++) eq[d - fromDay] = CAPITAL * s.lastClose(d, 5) / base;
        return eq;
    }

    /** 池内等权、每天收盘调仓（不计成本），代表"整个池子的平均走势"。 */
    double[] equalWeight(int topN, int fromDay) {
        double[] eq = new double[days - fromDay];
        double nav = CAPITAL;
        eq[0] = nav;
        for (int d = fromDay + 1; d < days; d++) {
            List<String> u = universe(d - 1, topN);
            double sum = 0;
            int n = 0;
            for (String sym : u) {
                Series s = series.get(sym);
                double c0 = s.close[d - 1];
                double c1 = s.has(d) ? s.close[d] : s.lastIndex() < d ? c0 : Double.NaN; // 下架按最后价卖出，和策略一致
                if (Double.isNaN(c1)) continue;
                sum += c1 / c0 - 1;
                n++;
            }
            nav *= 1 + (n == 0 ? 0 : sum / n);
            eq[d - fromDay] = nav;
        }
        return eq;
    }

    // ---------- 报告 ----------

    int year(int d) {
        return Instant.ofEpochMilli(start + d * DAY_MS).atZone(ZoneOffset.UTC).getYear();
    }

    String date(int d) {
        return Instant.ofEpochMilli(start + d * DAY_MS).atZone(ZoneOffset.UTC).toLocalDate().toString();
    }

    /** 按年份收益%（用上一年最后一天的权益做基数）。 */
    Map<Integer, Double> yearly(double[] eq, int fromDay) {
        Map<Integer, Double> out = new LinkedHashMap<>();
        double base = eq[0];
        int y = year(fromDay);
        for (int k = 1; k < eq.length; k++) {
            int yk = year(fromDay + k);
            if (yk != y) {
                out.put(y, (eq[k - 1] / base - 1) * 100);
                base = eq[k - 1];
                y = yk;
            }
        }
        out.put(y, (eq[eq.length - 1] / base - 1) * 100);
        return out;
    }

    static double maxDd(double[] eq) {
        double peak = eq[0], dd = 0;
        for (double e : eq) {
            peak = Math.max(peak, e);
            dd = Math.max(dd, (peak - e) / peak * 100);
        }
        return dd;
    }

    String markdown(List<Result> results, Map<String, double[]> benches, int fromDay) {
        StringBuilder b = new StringBuilder();
        b.append("# 美股 StockPilot 规则套到币圈日线（").append(date(fromDay)).append(" ～ ").append(date(days - 1)).append("）\n\n");
        b.append("规则照抄 StockPilot V1（评分、三种入场结构、回调加仓、结构破坏只盈利卖、利润保护线、三道止损），没有调任何参数。")
                .append("每天 UTC 0 点收盘跑一次 Main 再跑一次 Monitor，按收盘价成交，和 StockPilot 自己的回测口径一样。")
                .append("基准 SPY/QQQ 换成 BTC/ETH；每档 1000U，每币最多 5 档，总资金 10 万。")
                .append("成本：单边手续费 0.1% 加按成交额分档的冲击（前 20 大币大多是 0.05%）；另有一行按美股的单边 0.2% 计。")
                .append("标的池每天按最近 20 天 USDT 成交额排名（含已下架的币，没有幸存者偏差），持仓掉出池子不会被卖。\n\n");
        b.append("## 总表\n\n| 方案 | 期末权益 | 总收益% | 最大回撤% | 最大回撤 U | 买入 | 加仓 | 平仓 | 胜率% | 每笔平均% | 止损笔数 | 止损合计 U | 期末持仓 | 期末浮动 U | 平均资金占用% |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Result r : results) {
            double end = r.equity()[r.equity().length - 1];
            long wins = r.closed().stream().filter(c -> c.pnl() > 0).count();
            double avg = r.closed().stream().mapToDouble(Closed::pct).average().orElse(Double.NaN);
            List<Closed> st = r.closed().stream().filter(c -> c.reason().startsWith("STOP_LOSS")).toList();
            double floating = 0;
            for (Pos p : r.open().values()) floating += p.net(p.lastPrice);
            b.append(String.format(Locale.ROOT, "| %s | %,.0f | %.1f | %.1f | %,.0f | %d | %d | %d | %.0f | %.2f | %d | %,.0f | %d | %,.0f | %.0f |%n",
                    r.v().name(), end, (end / CAPITAL - 1) * 100, r.maxDdPct(), r.maxDdUsd(), r.buys(), r.adds(), r.closed().size(),
                    r.closed().isEmpty() ? 0 : wins * 100.0 / r.closed().size(), avg, st.size(),
                    st.stream().mapToDouble(Closed::pnl).sum(), r.open().size(), floating, r.avgCostUsed() / CAPITAL * 100));
        }
        for (Map.Entry<String, double[]> e : benches.entrySet()) {
            double[] eq = e.getValue();
            double end = eq[eq.length - 1];
            b.append(String.format(Locale.ROOT, "| %s | %,.0f | %.1f | %.1f | - | - | - | - | - | - | - | - | - | - | 100 |%n",
                    e.getKey(), end, (end / CAPITAL - 1) * 100, maxDd(eq)));
        }
        b.append("| 拿现金 | 100,000 | 0.0 | 0.0 | - | - | - | - | - | - | - | - | - | - | 0 |\n\n");

        b.append("## 按年份收益%\n\n| 方案 |");
        List<Integer> years = new ArrayList<>(yearly(results.get(0).equity(), fromDay).keySet());
        for (int y : years) b.append(' ').append(y).append(" |");
        b.append("\n|---|");
        years.forEach(y -> b.append("---|"));
        b.append('\n');
        Map<String, double[]> all = new LinkedHashMap<>();
        results.forEach(r -> all.put(r.v().name(), r.equity()));
        all.putAll(benches);
        for (Map.Entry<String, double[]> e : all.entrySet()) {
            Map<Integer, Double> yr = yearly(e.getValue(), fromDay);
            b.append("| ").append(e.getKey()).append(" |");
            for (int y : years) b.append(String.format(Locale.ROOT, " %.1f |", yr.getOrDefault(y, Double.NaN)));
            b.append('\n');
        }

        for (Result r : results) {
            b.append("\n## ").append(r.v().name()).append("：按退出原因\n\n| 原因 | 笔数 | 合计 U | 平均% | 平均持有天数 |\n|---|---|---|---|---|\n");
            Map<String, List<Closed>> by = new TreeMap<>();
            r.closed().forEach(c -> by.computeIfAbsent(c.reason(), k -> new ArrayList<>()).add(c));
            for (Map.Entry<String, List<Closed>> e : by.entrySet()) {
                List<Closed> cs = e.getValue();
                b.append(String.format(Locale.ROOT, "| %s | %d | %,.0f | %.2f | %.1f |%n", e.getKey(), cs.size(),
                        cs.stream().mapToDouble(Closed::pnl).sum(), cs.stream().mapToDouble(Closed::pct).average().orElse(0),
                        cs.stream().mapToInt(c -> c.closeDay() - c.openDay()).average().orElse(0)));
            }
            b.append("\n入场结构：\n\n| 结构 | 笔数 | 合计 U | 平均% |\n|---|---|---|---|\n");
            Map<String, List<Closed>> bs = new TreeMap<>();
            r.closed().forEach(c -> bs.computeIfAbsent(c.structure(), k -> new ArrayList<>()).add(c));
            for (Map.Entry<String, List<Closed>> e : bs.entrySet()) {
                List<Closed> cs = e.getValue();
                b.append(String.format(Locale.ROOT, "| %s | %d | %,.0f | %.2f |%n", e.getKey(), cs.size(),
                        cs.stream().mapToDouble(Closed::pnl).sum(), cs.stream().mapToDouble(Closed::pct).average().orElse(0)));
            }
            if (!r.open().isEmpty()) {
                b.append("\n期末持仓（浮动最差的 10 个）：\n\n| 币 | 开仓日 | 成本 | 浮动 U | 浮动% |\n|---|---|---|---|---|\n");
                r.open().values().stream().sorted(Comparator.comparingDouble(p -> p.net(p.lastPrice))).limit(10)
                        .forEach(p -> b.append(String.format(Locale.ROOT, "| %s | %s | %,.0f | %,.0f | %.1f |%n", p.symbol, date(p.openDay),
                                p.cost, p.net(p.lastPrice), p.netPct(p.lastPrice))));
            }
        }
        return b.toString();
    }

    static String tradesCsv(StockRulesStudy st, Result r) {
        StringBuilder b = new StringBuilder("symbol,open,close,cost,pnl,pct,reason,structure,tranches\n");
        for (Closed c : r.closed()) {
            b.append(String.format(Locale.ROOT, "%s,%s,%s,%.2f,%.2f,%.3f,%s,%s,%d%n", c.symbol(), st.date(c.openDay()), st.date(c.closeDay()),
                    c.cost(), c.pnl(), c.pct(), c.reason(), c.structure(), c.tranches()));
        }
        return b.toString();
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args.length > 0 ? args[0] : "backtest-data");
        Path outDir = Path.of(args.length > 1 ? args[1] : "backtest-out");
        YearMonth from = YearMonth.parse(args.length > 2 ? args[2] : "2022-06");
        LocalDate lastDay = LocalDate.parse(args.length > 3 ? args[3] : "2026-10-14");
        LocalDate tradeFrom = LocalDate.parse(args.length > 4 ? args[4] : "2022-10-15");
        Files.createDirectories(outDir);

        StrategyParams params = BacktestMain.loadParams();
        Archive archive = new Archive(dataDir, "1d");
        List<String> symbols = new ArrayList<>();
        for (String s : archive.usdtSymbols()) {
            String base = s.substring(0, s.length() - 4);
            if (params.universe().excludedBaseAssets().contains(base) || params.universe().tokenizedStockBaseAssets().contains(base)
                    || params.universe().blocklist().contains(s) || excludedDerivative(base)) continue;
            symbols.add(s);
        }
        System.out.println("候选 USDT 交易对 " + symbols.size() + " 个");
        archive.download(symbols, from, YearMonth.from(lastDay).minusMonths(1), lastDay);
        long start = from.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        int days = (int) ((lastDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - start) / DAY_MS);
        Map<String, Series> series = new ConcurrentHashMap<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<?>> fs = new ArrayList<>();
            for (String s : symbols) {
                fs.add(pool.submit(() -> {
                    Series x = archive.load(s, start, days, from, YearMonth.from(lastDay).minusMonths(1), lastDay);
                    if (x != null) series.put(s, x);
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
        }
        System.out.println("读入 " + series.size() + " 个交易对的日线");
        if (!series.containsKey(BENCH_A) || !series.containsKey(BENCH_B)) throw new IllegalStateException("缺少 BTC/ETH 日线");

        StockRulesStudy st = new StockRulesStudy(series, start, days);
        int fromDay = (int) ((tradeFrom.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - start) / DAY_MS);
        List<Variant> variants = List.of(
                new Variant("前20大币-美股规则原样", 20, true, 0),
                new Variant("前20大币-去掉止损", 20, false, 0),
                new Variant("前20大币-按美股单边0.2%成本", 20, true, 0.002),
                new Variant("前50大币-美股规则原样", 50, true, 0),
                new Variant("前150大币-美股规则原样", 150, true, 0));
        List<Result> results = new ArrayList<>();
        for (Variant v : variants) {
            Result r = st.run(v, fromDay);
            results.add(r);
            System.out.println("完成 " + v.name() + "：平仓 " + r.closed().size() + " 笔，期末权益 " + Math.round(r.equity()[r.equity().length - 1]));
            Files.writeString(outDir.resolve("trades-" + v.name() + ".csv"), tradesCsv(st, r));
        }
        Map<String, double[]> benches = new LinkedHashMap<>();
        benches.put("BTC 一直持有", st.hold(BENCH_A, fromDay));
        benches.put("ETH 一直持有", st.hold(BENCH_B, fromDay));
        benches.put("前20大币等权持有", st.equalWeight(20, fromDay));
        benches.put("前150大币等权持有", st.equalWeight(150, fromDay));
        String md = st.markdown(results, benches, fromDay);
        Files.writeString(outDir.resolve("report.md"), md);
        StringBuilder csv = new StringBuilder("date");
        results.forEach(r -> csv.append(',').append(r.v().name()));
        benches.keySet().forEach(k -> csv.append(',').append(k));
        csv.append('\n');
        for (int k = 0; k < days - fromDay; k++) {
            csv.append(st.date(fromDay + k));
            for (Result r : results) csv.append(String.format(Locale.ROOT, ",%.2f", r.equity()[k]));
            for (double[] e : benches.values()) csv.append(String.format(Locale.ROOT, ",%.2f", e[k]));
            csv.append('\n');
        }
        Files.writeString(outDir.resolve("equity.csv"), csv.toString());
        System.out.println(md);
    }

    /** 杠杆代币和包装币（BTCUP、ETHDOWN、WBTC、WBETH 等）不是独立标的。 */
    static boolean excludedDerivative(String base) {
        return LEVERAGED.matcher(base).matches() || base.endsWith("BULL") || base.endsWith("BEAR")
                || Set.of("WBTC", "WBETH", "BETH", "BNSOL", "WSTETH").contains(base);
    }
}
