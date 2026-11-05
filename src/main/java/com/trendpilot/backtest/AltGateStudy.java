package com.trendpilot.backtest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
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
import java.util.function.IntFunction;

import com.trendpilot.altseason.AltSeasonRules;
import com.trendpilot.backtest.LitStudy.Choice;
import com.trendpilot.backtest.LitStudy.Curve;
import com.trendpilot.backtest.LitStudy.Panel;
import com.trendpilot.backtest.LitStudy.Row;
import com.trendpilot.backtest.LitStudy.Run;
import com.trendpilot.backtest.LitStudy.Spec;
import com.trendpilot.config.StrategyParams;

/**
 * Gated rotation (research only, 2026-11-05; pre-registered, two gates, no tuning): the weekly 21-day risk-adjusted rotation of
 * {@link LitStudy} holds coins only while an "alt season" gate is on and sits in USDT otherwise.
 * <ul>
 * <li>S1 = the live alt-season alert ({@link AltSeasonRules}, unchanged thresholds), computed on Binance daily data like the live
 * job (top 150 by daily quote volume, 201 closes); the regime condition (≥ CONSTRUCTIVE) is approximated on daily bars: BTC
 * day return &gt; 0 and at least {@code constructive-breadth} of the universe up on the day (no 4h data).</li>
 * <li>S2 = equal-weight top-40 basket (weekly rebalanced, no costs, an index only) / BTC above its 50-day mean AND the basket
 * above its own 50-day mean, judged at each daily close.</li>
 * </ul>
 * Gate on → the next Sunday close (Monday 00:00 UTC; the same close if it turns on on a Sunday) rebalances by the rotation rule
 * and keeps doing so weekly; gate off → everything sold at that day's close. Missing data leaves a gate unchanged.
 * Random twins: same days, same pick counts (0 while the gate is off), same candidates and weighting, random coins.
 * <pre>
 * java -cp ... com.trendpilot.backtest.AltGateStudy dataDir outDir binFromMonth toMonth binTradeFrom bybFromMonth bybTradeFrom threads nowLast
 *   nowLast = last day for the "current state" section (auto = yesterday UTC); its month is loaded beyond toMonth
 * </pre>
 */
public final class AltGateStudy {

    static final int PRIOR_TRIALS = 391;
    static final int S1_POOL = 150;
    static final int S1_DAYS = 201;
    static final int S1_MIN = 51;
    static final int S2_MA = 50;
    static final int GIVEBACK_DAYS = 60;

    // ------------------------------------------------------------------ gates

    record S1Day(boolean complete, AltSeasonRules.Metrics m, boolean regimeOk, double btc1d, double breadth24, int streak, boolean on) {
    }

    static S1Day[] s1(Panel p, StrategyParams.Risk risk) {
        int eth = index(p, MarketData.ETH);
        S1Day[] out = new S1Day[p.days];
        AltSeasonRules.State st = AltSeasonRules.State.OFF;
        int streak = 0;
        for (int d = 0; d < p.days; d++) {
            if (p.btc < 0 || !p.valid(p.btc, d)) {
                out[d] = new S1Day(false, null, false, Double.NaN, Double.NaN, streak, st == AltSeasonRules.State.EARLY);
                continue;
            }
            List<Integer> c = new ArrayList<>();
            for (int i = 0; i < p.syms.length; i++) if (p.alt[i] && p.valid(i, d) && p.qv[i][d] > 0) c.add(i);
            final int dd = d;
            c.sort((x, y) -> Double.compare(p.qv[y][dd], p.qv[x][dd]));
            Map<String, double[]> daily = new LinkedHashMap<>();
            for (int k = 0; k < Math.min(S1_POOL, c.size()); k++) {
                double[] a = closes(p, c.get(k), d);
                if (a != null) daily.put(p.syms[c.get(k)], a);
            }
            double[] btc = closes(p, p.btc, d);
            double[] e = eth < 0 || !p.valid(eth, d) ? null : closes(p, eth, d);
            AltSeasonRules.Metrics m = AltSeasonRules.compute(daily, btc, e);
            double b1 = 100 * p.ret(p.btc, d, 1);
            int up = 0, n = 0;
            for (int i = 0; i < p.syms.length; i++) {
                if (!p.alt[i] && i != p.btc) continue;
                double r = p.ret(i, d, 1);
                if (Double.isNaN(r)) continue;
                n++;
                if (r > 0) up++;
            }
            double br = n == 0 ? Double.NaN : up / (double) n;
            boolean regimeOk = !Double.isNaN(b1) && !Double.isNaN(br) && b1 > 0 && b1 > risk.riskOffBtc24hPct()
                    && br >= risk.constructiveBreadth() && br >= risk.riskOffBreadth();
            boolean complete = m.pool() >= AltSeasonRules.MIN_POOL && !Double.isNaN(m.breadth50()) && !Double.isNaN(m.altVsBtc30())
                    && !Double.isNaN(m.ethBtcVs50());
            if (complete) {
                streak = m.altVsBtc30() > 0 ? streak + 1 : 0;
                st = AltSeasonRules.next(st, m, streak, regimeOk);
            }
            out[d] = new S1Day(complete, m, regimeOk, b1, br, streak, st == AltSeasonRules.State.EARLY);
        }
        return out;
    }

    /** Up to 201 carried closes ending at d (like the live job's closed daily klines); null if fewer than 51. */
    static double[] closes(Panel p, int i, int d) {
        if (p.first[i] < 0 || d - p.first[i] + 1 < S1_MIN) return null;
        int len = Math.min(S1_DAYS, d - p.first[i] + 1);
        return Arrays.copyOfRange(p.cpx[i], d - len + 1, d + 1);
    }

    static int index(Panel p, String sym) {
        for (int i = 0; i < p.syms.length; i++) if (p.syms[i].equals(sym)) return i;
        return -1;
    }

    record S2Day(boolean known, double ratioVsMa, double idxVsMa, boolean on) {
    }

    /** Equal-weight top-40 index, rebalanced each Sunday close, no costs; NaN before the first pool. */
    static double[] basket(Panel p) {
        double[] idx = new double[p.days];
        Arrays.fill(idx, Double.NaN);
        double[] units = null;
        int[] mem = null;
        for (int d = 0; d < p.days; d++) {
            if (units != null) {
                double v = 0;
                for (int k = 0; k < mem.length; k++) v += units[k] * p.cpx[mem[k]][d];
                idx[d] = v;
            }
            if (p.sunday(d)) {
                int[] pool = p.pool(d, LitStudy.POOL);
                if (pool.length > 0) {
                    if (units == null) idx[d] = 1;
                    double base = idx[d];
                    units = new double[pool.length];
                    for (int k = 0; k < pool.length; k++) units[k] = base / pool.length / p.cpx[pool[k]][d];
                    mem = pool.clone();
                }
            }
        }
        return idx;
    }

    static S2Day[] s2(Panel p) {
        double[] idx = basket(p);
        S2Day[] out = new S2Day[p.days];
        boolean on = false;
        for (int d = 0; d < p.days; d++) {
            boolean ok = p.btc >= 0 && p.valid(p.btc, d) && d >= S2_MA - 1;
            double rs = 0, is = 0;
            if (ok) {
                for (int j = d - S2_MA + 1; j <= d; j++) {
                    if (Double.isNaN(idx[j]) || Double.isNaN(p.cpx[p.btc][j])) {
                        ok = false;
                        break;
                    }
                    rs += idx[j] / p.cpx[p.btc][j];
                    is += idx[j];
                }
            }
            if (!ok) {
                out[d] = new S2Day(false, Double.NaN, Double.NaN, on);
                continue;
            }
            double ratio = idx[d] / p.cpx[p.btc][d], rma = rs / S2_MA, ima = is / S2_MA;
            on = ratio > rma && idx[d] > ima;
            out[d] = new S2Day(true, 100 * (ratio / rma - 1), 100 * (idx[d] / ima - 1), on);
        }
        return out;
    }

    /** Rotation-21 choices that only invest while the gate is on: k = 0 on Sundays with the gate off and on each off day. */
    static Map<Integer, Choice> gated(Panel p, int from, int to, boolean[] on) {
        Map<Integer, Choice> rot = LitStudy.rotation(p, from, to, 21, false);
        Map<Integer, Choice> m = new TreeMap<>();
        Choice none = new Choice(new int[0], new double[0], new double[0], 0, true);
        for (int d = from; d < to; d++) {
            if (p.sunday(d)) {
                Choice c = rot.get(d);
                m.put(d, on[d] ? c : new Choice(c.cand(), c.score(), c.vol(), 0, true));
            } else if (!on[d] && d > from && on[d - 1]) {
                m.put(d, none);
            }
        }
        return m;
    }

    // ------------------------------------------------------------------ trade log (same book as LitStudy.runWeekly, hold = 1)

    record Trade(int sym, int buy, int sell, double priceRet, double pnlU) {
    }

    record Book(double[] nav, double[] expo, List<Trade> trades) {
    }

    /** Replays the real picks and logs each continuous holding; trade U = sells × (1 − cost) − buys × (1 + cost), on 2,000U. */
    static Book book(Panel p, int from, int to, Map<Integer, Choice> choices) {
        int n = p.syms.length;
        double[] units = new double[n], in = new double[n], out = new double[n];
        int[] buy = new int[n];
        double cash = 1;
        double[] nav = new double[to - from + 1], expo = new double[nav.length];
        List<Trade> trades = new ArrayList<>();
        for (int d = from; d <= to; d++) {
            for (int i = 0; i < n; i++) {
                if (units[i] > 0 && p.stale[i][d] >= LitStudy.DELIST_DAYS) {
                    double v = units[i] * p.cpx[i][d] * 0.995;
                    cash += v * (1 - LitStudy.COST);
                    out[i] += v;
                    units[i] = 0;
                    trades.add(close(p, i, buy[i], d, in[i], out[i]));
                }
            }
            Choice c = choices.get(d);
            if (c != null && d < to) {
                int[] picks = c.top();
                double[] w = c.weights(picks);
                double value = cash;
                for (int i = 0; i < n; i++) if (units[i] > 0) value += units[i] * p.cpx[i][d];
                double[] tv = new double[n];
                for (int j = 0; j < picks.length; j++) tv[c.cand()[picks[j]]] += w[j] * value;
                double traded = 0;
                for (int i = 0; i < n; i++) traded += Math.abs(tv[i] - (units[i] > 0 ? units[i] * p.cpx[i][d] : 0));
                double cost = traded * LitStudy.COST;
                double scale = value > 0 ? (value - cost) / value : 0;
                double invested = 0;
                for (int i = 0; i < n; i++) {
                    double v = tv[i] * scale, cur = units[i] > 0 ? units[i] * p.cpx[i][d] : 0;
                    if (v > 0 && units[i] == 0) {
                        buy[i] = d;
                        in[i] = 0;
                        out[i] = 0;
                    }
                    if (v > cur) in[i] += v - cur;
                    else out[i] += cur - v;
                    if (v == 0 && units[i] > 0) trades.add(close(p, i, buy[i], d, in[i], out[i]));
                    units[i] = v > 0 ? v / p.cpx[i][d] : 0;
                    invested += v;
                }
                cash = value - cost - invested;
            }
            double inv = 0;
            for (int i = 0; i < n; i++) if (units[i] > 0) inv += units[i] * p.cpx[i][d];
            nav[d - from] = cash + inv;
            expo[d - from] = cash + inv > 0 ? inv / (cash + inv) : 0;
        }
        for (int i = 0; i < n; i++) {
            if (units[i] > 0) {
                Trade t = close(p, i, buy[i], to, in[i], out[i] + units[i] * p.cpx[i][to]);
                trades.add(new Trade(t.sym(), t.buy(), -1, t.priceRet(), t.pnlU()));
            }
        }
        trades.sort((a, b) -> a.buy() != b.buy() ? Integer.compare(a.buy(), b.buy()) : Integer.compare(a.sym(), b.sym()));
        return new Book(nav, expo, trades);
    }

    private static Trade close(Panel p, int i, int buy, int sell, double in, double out) {
        double pr = p.cpx[i][sell] / p.cpx[i][buy] - 1;
        return new Trade(i, buy, sell, pr, LitStudy.START_U * (out * (1 - LitStudy.COST) - in * (1 + LitStudy.COST)));
    }

    // ------------------------------------------------------------------ episodes

    record Episode(int start, int end, boolean closed, boolean invested, double ret, double peakNav, double startNav, double endNav) {
    }

    /** Gate-on spells inside [from, to]; end = the off day (sold at that close) or to if still on. */
    static List<Episode> episodes(int from, int to, boolean[] on, Book b) {
        List<Episode> out = new ArrayList<>();
        int d = from;
        while (d <= to) {
            if (!on[d]) {
                d++;
                continue;
            }
            int s = d;
            while (d <= to && on[d]) d++;
            int e = Math.min(d, to);
            boolean closed = d <= to;
            boolean inv = false;
            double peak = 0;
            for (int k = s; k <= e; k++) {
                inv |= b.expo()[k - from] > 0;
                peak = Math.max(peak, b.nav()[k - from]);
            }
            double sn = b.nav()[s - from], en = b.nav()[e - from];
            out.add(new Episode(s, e, closed, inv, en / sn - 1, peak, sn, en));
            d = e + 1;
        }
        return out;
    }

    // ------------------------------------------------------------------ study

    record Gate(String key, String name, boolean[] on, Map<Integer, Choice> choices, Book book) {
    }

    static final class GRun {
        final Run run;
        final List<Gate> gates = new ArrayList<>();
        Book ref;
        S1Day[] s1;
        S2Day[] s2;

        GRun(Run run) {
            this.run = run;
        }
    }

    static GRun study(Panel p, String label, LocalDate tradeFrom, LocalDate last, boolean[] s1on, S1Day[] s1, int threads) throws Exception {
        Run run = new Run(p, label, tradeFrom, last);
        GRun g = new GRun(run);
        g.s1 = s1;
        g.s2 = s2(p);
        int from = run.from, to = run.to;
        boolean[] s2on = new boolean[p.days];
        for (int d = 0; d < p.days; d++) s2on[d] = g.s2[d].on();
        run.bench.put("BTC 持有", LitStudy.btcHold(p, from, to));
        run.bench.put("前 40 等权持有（每周再平衡）", LitStudy.runWeekly(p, from, to, 1, LitStudy.equalPool(p, from, to, LitStudy.POOL), null).nav());
        run.bench.put("前 20 等权持有（每周再平衡）", LitStudy.runWeekly(p, from, to, 1, LitStudy.equalPool(p, from, to, LitStudy.DON_POOL), null).nav());
        Map<Integer, Choice> ref = LitStudy.rotation(p, from, to, 21, false);
        g.ref = book(p, from, to, ref);
        g.gates.add(new Gate("g1", "S1 山寨季提醒", s1on, gated(p, from, to, s1on), null));
        g.gates.add(new Gate("g2", "S2 篮子强于 BTC", s2on, gated(p, from, to, s2on), null));
        for (int k = 0; k < g.gates.size(); k++) {
            Gate x = g.gates.get(k);
            g.gates.set(k, new Gate(x.key(), x.name(), x.on(), x.choices(), book(p, from, to, x.choices())));
        }
        List<Spec> specs = List.of(new Spec("无开关（上一轮）", "轮动 21 日（无开关，参照）", "rot21"),
                new Spec("S1", "轮动 21 日 + S1 山寨季提醒开关", "g1"), new Spec("S2", "轮动 21 日 + S2 篮子强于 BTC 开关", "g2"));
        List<Map<Integer, Choice>> chs = List.of(ref, g.gates.get(0).choices(), g.gates.get(1).choices());
        long[] seeds = {20261105, 20261205, 20261206};
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads))) {
            List<Future<Row>> fs = new ArrayList<>();
            for (int k = 0; k < specs.size(); k++) {
                Spec s = specs.get(k);
                Map<Integer, Choice> c = chs.get(k);
                long seed = seeds[k];
                fs.add(pool.submit(() -> {
                    Curve real = LitStudy.runWeekly(p, from, to, 1, c, null);
                    IntFunction<Curve> twin = j -> LitStudy.runWeekly(p, from, to, 1, c, new SplittableRandom(seed * 7919 + j));
                    return LitStudy.score(run, s, seed, real, twin);
                }));
            }
            for (Future<Row> f : fs) {
                Row r = f.get();
                run.rows.add(r);
                System.out.printf(Locale.ROOT, "%s %s: fullU %.0f valU %.0f pct %.3f%n", label, r.spec().name(), r.fullU(), r.valU(), r.pct());
            }
        }
        // the trade log must replay the same book as runWeekly
        for (int k = 0; k < 3; k++) {
            double[] a = run.rows.get(k).real().nav(), b = k == 0 ? g.ref.nav() : g.gates.get(k - 1).book().nav();
            double diff = 0;
            for (int j = 0; j < a.length; j++) diff = Math.max(diff, Math.abs(a[j] - b[j]));
            System.out.printf(Locale.ROOT, "%s 交易明细复刻净值最大差 %.2e%n", specs.get(k).name(), diff);
        }
        return g;
    }

    // ------------------------------------------------------------------ report helpers

    static String u(double x) {
        return LitStudy.u(x);
    }

    static String pc(double x) {
        return LitStudy.pc(x);
    }

    static String sym(Panel p, int i) {
        return p.syms[i].substring(0, p.syms[i].length() - 4);
    }

    static String gateSummary(GRun g) {
        Run run = g.run;
        Panel p = run.p;
        StringBuilder b = new StringBuilder();
        b.append("\n### 开关统计（").append(run.label).append("，").append(p.date(run.from)).append(" ～ ").append(p.date(run.to)).append("）\n\n");
        b.append("| 开关 | 打开天数（占比） | 打开次数 | 其中建过仓 | 打开后亏钱（错误打开） | 没等到周一就关了 | 关闭时比该次最高净值回吐（中位 / 最大） | 2025 年全年 |\n");
        b.append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
        int total = run.to - run.from + 1;
        for (int k = 0; k < g.gates.size(); k++) {
            Gate x = g.gates.get(k);
            List<Episode> eps = episodes(run.from, run.to, x.on(), x.book());
            int days = 0;
            for (int d = run.from; d <= run.to; d++) if (x.on()[d]) days++;
            int inv = 0, lose = 0, never = 0;
            List<Double> gb = new ArrayList<>();
            for (Episode e : eps) {
                if (e.invested()) {
                    inv++;
                    if (e.ret() < 0) lose++;
                    if (e.closed()) gb.add(1 - e.endNav() / e.peakNav());
                } else {
                    never++;
                }
            }
            double[] gs = gb.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            Row r = run.rows.get(k + 1);
            b.append(String.format(Locale.ROOT, "| %s | %d（%.0f%%） | %d | %d | %d | %d | %s / %s | %s |%n", x.name(), days, 100.0 * days / total,
                    eps.size(), inv, lose, never, gs.length == 0 ? "—" : String.format(Locale.ROOT, "%.1f%%", 100 * gs[gs.length / 2]),
                    gs.length == 0 ? "—" : String.format(Locale.ROOT, "%.1f%%", 100 * gs[gs.length - 1]), year(run, r.real().nav(), 2025)));
        }
        b.append(String.format(Locale.ROOT, "| （参照）无开关 | %d（100%%） | | | | | | %s |%n", total, year(run, run.rows.get(0).real().nav(), 2025)));
        return b.toString();
    }

    static String year(Run run, double[] nav, int y) {
        Panel p = run.p;
        int a = Math.max(run.from, p.day(LocalDate.of(y, 1, 1)) - 1), c = Math.min(run.to, p.day(LocalDate.of(y, 12, 31)));
        if (a >= c) return "—";
        return pc(nav[c - run.from] / nav[a - run.from] - 1);
    }

    static String episodeTable(GRun g) {
        Run run = g.run;
        Panel p = run.p;
        StringBuilder b = new StringBuilder();
        for (Gate x : g.gates) {
            b.append("\n#### ").append(run.label).append(" · ").append(x.name()).append(" 每次打开\n\n");
            b.append("| 打开（收盘） | 关闭卖出（收盘） | 天数 | 建仓 | 这次净值变化 | 这次 U（2,000U 本金） | 关闭时比这次最高净值回吐 |\n|---|---|---:|---|---:|---:|---:|\n");
            double n0 = x.book().nav()[0];
            for (Episode e : episodes(run.from, run.to, x.on(), x.book())) {
                b.append(String.format(Locale.ROOT, "| %s | %s | %d | %s | %s | %s | %s |%n", p.date(e.start()), e.closed() ? p.date(e.end()).toString() : "仍打开",
                        e.end() - e.start(), e.invested() ? "是" : "否", pc(e.ret()), u(LitStudy.START_U * (e.endNav() - e.startNav()) / n0),
                        e.invested() && e.closed() ? String.format(Locale.ROOT, "%.1f%%（%s U）", 100 * (1 - e.endNav() / e.peakNav()),
                                u(-LitStudy.START_U * (e.peakNav() - e.endNav()) / n0)) : "—"));
            }
        }
        return b.toString();
    }

    /** Season block: summary rows, trades per gate, top-10 gainers with caught / missed reasons, off-sale giveback. */
    static String season(GRun g, LocalDate sa, LocalDate se, String title) {
        Run run = g.run;
        Panel p = run.p;
        int a = p.day(sa), e = p.day(se);
        StringBuilder b = new StringBuilder("\n### ").append(title).append("：").append(sa).append(" ～ ").append(se).append("（").append(run.label).append("）\n\n");
        if (a - 1 < run.from || e > run.to) return b.append("不在回测区间内。\n").toString();
        double btcU = LitStudy.segU(run, run.bench.get("BTC 持有"), sa, se);
        double ewU = LitStudy.segU(run, run.bench.get("前 40 等权持有（每周再平衡）"), sa, se);
        b.append(String.format(Locale.ROOT, "同期 BTC %s，前 40 等权 %s。%n%n", pc(btcU / LitStudy.START_U), pc(ewU / LitStudy.START_U)));
        b.append("| 方案 | 这一段 2,000U → U | 10,000U → U | 段前一天仓位 | 比行情启动晚几天上车 | 结束后 60 天变化 U（2,000U） | 吐回本段利润 |\n|---|---:|---:|---:|---|---:|---:|\n");
        List<String> names = new ArrayList<>();
        List<double[]> navs = new ArrayList<>(), expos = new ArrayList<>();
        names.add("无开关（参照）");
        navs.add(g.ref.nav());
        expos.add(g.ref.expo());
        for (Gate x : g.gates) {
            names.add(x.name());
            navs.add(x.book().nav());
            expos.add(x.book().expo());
        }
        for (int k = 0; k < names.size(); k++) {
            double[] nav = navs.get(k), ex = expos.get(k);
            double su = LitStudy.segU(run, nav, sa, se);
            String lag;
            if (ex[a - 1 - run.from] > 0) lag = "段前已持仓（0 天）";
            else {
                int l = -1;
                for (int d = a; d <= e; d++) if (ex[d - run.from] > 0) {
                    l = d - a;
                    break;
                }
                lag = l < 0 ? "整段没上车" : l + " 天";
            }
            String give = "—", share = "—";
            if (e + GIVEBACK_DAYS <= run.to) {
                double ch = LitStudy.START_U * (nav[e + GIVEBACK_DAYS - run.from] - nav[e - run.from]) / nav[a - 1 - run.from];
                give = u(ch);
                if (su > 0 && ch < 0) share = String.format(Locale.ROOT, "%.0f%%", -100 * ch / su);
            }
            b.append(String.format(Locale.ROOT, "| %s | %s | %s | %.0f%% | %s | %s | %s |%n", names.get(k), u(su), u(5 * su), 100 * ex[a - 1 - run.from], lag, give, share));
        }
        // gainers among coins trading before the start and still trading at the end
        List<double[]> gain = new ArrayList<>();
        for (int i = 0; i < p.syms.length; i++) {
            if (!p.alt[i] || p.first[i] < 0 || p.first[i] > a - 1 || p.stale[i][a - 1] > 3 || p.stale[i][e] > 3) continue;
            gain.add(new double[]{i, p.cpx[i][e] / p.cpx[i][a - 1] - 1});
        }
        gain.sort((x, y) -> Double.compare(y[1], x[1]));
        for (Gate x : g.gates) {
            b.append("\n**").append(x.name()).append("**：开关在本段打开的日子 ");
            int on = 0;
            for (int d = a; d <= e; d++) if (x.on()[d]) on++;
            b.append(on).append(" / ").append(e - a + 1).append(" 天。\n\n");
            List<Trade> ts = new ArrayList<>();
            for (Trade t : x.book().trades()) if (t.buy() <= e && (t.sell() < 0 || t.sell() >= a)) ts.add(t);
            if (ts.isEmpty()) b.append("本段没有持仓。\n");
            else {
                b.append("| 币 | 买入（收盘） | 卖出（收盘） | 持有天数 | 价格涨跌（扣费前） | 这笔 U（2,000U 本金，扣费后） |\n|---|---|---|---:|---:|---:|\n");
                for (Trade t : ts) {
                    b.append(String.format(Locale.ROOT, "| %s | %s | %s | %d | %s | %s |%n", sym(p, t.sym()), p.date(t.buy()),
                            t.sell() < 0 ? "未卖（按 " + p.date(run.to) + " 计）" : p.date(t.sell()).toString(),
                            (t.sell() < 0 ? run.to : t.sell()) - t.buy(), pc(t.priceRet()), u(t.pnlU())));
                }
            }
            b.append("\n本段涨幅前 10（段前一天收盘 → 段末收盘，段前已上线的币）：\n\n| 名次 | 币 | 涨幅 | 抓到了吗 | 没抓到的原因 / 抓到的这几笔 U |\n|---:|---|---:|---|---|\n");
            for (int k = 0; k < Math.min(10, gain.size()); k++) {
                int i = (int) gain.get(k)[0];
                double sumU = 0;
                boolean caught = false;
                for (Trade t : ts) if (t.sym() == i) {
                    caught = true;
                    sumU += t.pnlU();
                }
                b.append(String.format(Locale.ROOT, "| %d | %s | %s | %s | %s |%n", k + 1, sym(p, i), pc(gain.get(k)[1]), caught ? "抓到" : "漏了",
                        caught ? u(sumU) + " U" : why(p, x, i, a, e)));
            }
            // off-sale giveback for spells overlapping the season
            List<Episode> eps = new ArrayList<>();
            for (Episode ep : episodes(run.from, run.to, x.on(), x.book())) if (ep.start() <= e && ep.end() >= a && ep.invested()) eps.add(ep);
            if (!eps.isEmpty()) {
                b.append("\n开关关闭卖出那一刻（和本段有重叠的每次打开）：\n\n| 打开 | 关闭卖出 | 这次最高净值日 | 卖出时比最高净值回吐 | 回吐 U（2,000U 本金） | 这次总共赚 U |\n|---|---|---|---:|---:|---:|\n");
                double n0 = x.book().nav()[0];
                for (Episode ep : eps) {
                    int pk = ep.start();
                    for (int d = ep.start(); d <= ep.end(); d++) if (x.book().nav()[d - run.from] >= x.book().nav()[pk - run.from]) pk = d;
                    b.append(String.format(Locale.ROOT, "| %s | %s | %s | %.1f%% | %s | %s |%n", p.date(ep.start()), ep.closed() ? p.date(ep.end()).toString() : "仍打开",
                            p.date(pk), 100 * (1 - ep.endNav() / ep.peakNav()), u(-LitStudy.START_U * (ep.peakNav() - ep.endNav()) / n0),
                            u(LitStudy.START_U * (ep.endNav() - ep.startNav()) / n0)));
                }
            }
        }
        return b.toString();
    }

    /** Why the gated rotation never held coin i during [a, e]. */
    static String why(Panel p, Gate x, int i, int a, int e) {
        int rebal = 0, young = 0, notTraded = 0, outPool = 0, noData = 0, ranked = 0, bestVol = Integer.MAX_VALUE, bestScore = Integer.MAX_VALUE;
        for (int d = a; d <= e; d++) {
            Choice c = x.choices().get(d);
            if (c == null || c.k() == 0) continue;
            rebal++;
            boolean inPool = false;
            for (int q : p.pool(d, LitStudy.POOL)) inPool |= q == i;
            if (!inPool) {
                if (!p.oldEnough(i, d)) young++;
                else if (!p.valid(i, d)) notTraded++;
                else {
                    outPool++;
                    bestVol = Math.min(bestVol, volRank(p, i, d));
                }
                continue;
            }
            int pos = -1;
            for (int j = 0; j < c.cand().length; j++) if (c.cand()[j] == i) pos = j;
            if (pos < 0) {
                noData++;
                continue;
            }
            int r = 1;
            for (double s : c.score()) if (s > c.score()[pos]) r++;
            ranked++;
            bestScore = Math.min(bestScore, r);
        }
        if (rebal == 0) return "本段开关打开时没有遇到周一调仓（或开关一直关）";
        List<String> s = new ArrayList<>();
        if (young > 0) s.add(young + " 次上线不满 90 天");
        if (outPool > 0) s.add(outPool + " 次成交额不在前 40（最好第 " + bestVol + " 名）");
        if (notTraded > 0) s.add(notTraded + " 次当天无成交");
        if (noData > 0) s.add(noData + " 次 21 日数据不全");
        if (ranked > 0) s.add(ranked + " 次在前 40 但得分没进前 5（最好第 " + bestScore + " 名）");
        return "本段 " + rebal + " 次调仓：" + String.join("，", s);
    }

    /** Rank of coin i by median 30-day quote volume among eligible alts on day d (same rule as the pool). */
    static int volRank(Panel p, int i, int d) {
        double mi = med30(p, i, d);
        if (Double.isNaN(mi)) return -1;
        int r = 1;
        for (int j = 0; j < p.syms.length; j++) {
            if (j == i || !p.alt[j] || !p.valid(j, d) || !p.oldEnough(j, d)) continue;
            double m = med30(p, j, d);
            if (m > mi) r++;
        }
        return r;
    }

    static double med30(Panel p, int i, int d) {
        if (d < 29) return Double.NaN;
        double[] w = new double[30];
        for (int j = 0; j < 30; j++) w[j] = p.qv[i][d - j];
        Arrays.sort(w);
        return (w[14] + w[15]) / 2;
    }

    static String current(GRun g, S1Day[] s1, Panel s1Panel, String s1Label) {
        Panel p = g.run.p;
        int last = p.days - 1;
        while (last > 0 && !p.valid(p.btc, last)) last--;
        StringBuilder b = new StringBuilder("\n### 当前状态：").append(g.run.label).append("（最新收盘 ").append(p.date(last)).append("）\n\n");
        if (s1 != null) {
            int l1 = s1Panel.days - 1;
            while (l1 > 0 && !s1Panel.valid(s1Panel.btc, l1)) l1--;
            S1Day x = s1[l1];
            AltSeasonRules.Metrics m = x.m();
            b.append(String.format(Locale.ROOT, "- S1（%s，%s 收盘）：**%s**。池 %d 个币；50 日线上方 %.0f%%（进入要 ≥ 70%%）；山寨 30 日中位 %+.1f%%，比 BTC %+.1f 点，连续跑赢 %d 天（要 ≥ 14）；"
                            + "ETH/BTC 比 50 日线 %+.1f%%（要 > 0）；当日行情近似 %s（BTC 当日 %+.1f%%，上涨币占比 %.0f%%）。退出条件：宽度 < 40%% 或山寨比 BTC 少 10 点以上。%n",
                    s1Label, s1Panel.date(l1), x.on() ? "开" : "关", m.pool(), m.breadth50(), m.alt30(), m.altVsBtc30(), x.streak(), m.ethBtcVs50(),
                    x.regimeOk() ? "≥ CONSTRUCTIVE" : "< CONSTRUCTIVE", x.btc1d(), 100 * x.breadth24()));
        }
        S2Day y = g.s2[last];
        b.append(String.format(Locale.ROOT, "- S2（%s 前 40 篮子）：**%s**。篮子 / BTC 比 50 日均线 %+.1f%%，篮子比自身 50 日均线 %+.1f%%（两个都 > 0 才开）。%n",
                g.run.label, y.on() ? "开" : "关", y.ratioVsMa(), y.idxVsMa()));
        // rotation as of this close
        int[] pool = p.pool(last, LitStudy.POOL);
        List<double[]> c = new ArrayList<>();
        for (int i : pool) {
            double r = p.ret(i, last, 21), v = p.vol(i, last, 21);
            if (Double.isNaN(r) || Double.isNaN(v)) continue;
            c.add(new double[]{i, r / v, r, v});
        }
        c.sort((a1, b1) -> Double.compare(b1[1], a1[1]));
        int k = Math.min(5, c.size());
        double[] w = new double[k];
        for (int j = 0; j < k; j++) w[j] = 1 / c.get(j)[3];
        w = LitStudy.capWeights(w, LitStudy.CAP);
        b.append("\n轮动规则按这一天收盘会选出的前 5 个（池内 ").append(c.size()).append(" 个可打分；只是信息，不是交易建议；规则本身只在周日收盘调仓，开关关时不持仓）：\n\n");
        b.append("| 名次 | 币 | 得分（21 日收益 ÷ 21 日波动） | 21 日收益 | 日波动 | 权重 |\n|---:|---|---:|---:|---:|---:|\n");
        for (int j = 0; j < k; j++) {
            double[] x = c.get(j);
            b.append(String.format(Locale.ROOT, "| %d | %s | %.2f | %s | %.1f%% | %.0f%% |%n", j + 1, sym(p, (int) x[0]), x[1], pc(x[2]), 100 * x[3], 100 * w[j]));
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ loading

    static Panel binance(Path dir, YearMonth from, YearMonth to, LocalDate nowLast, StrategyParams params, int threads) throws Exception {
        Archive a = new Archive(dir, "1d");
        Set<String> excluded = new HashSet<>();
        List<String> symbols = new ArrayList<>();
        for (String s : a.usdtSymbols()) if (!com.trendpilot.universe.UniverseFilter.excludedOffline(params.universe(), s)) symbols.add(s);
        else excluded.add(s);
        System.out.println("Binance USDT 交易对（含已下架）" + symbols.size() + " 个");
        boolean extra = nowLast.isAfter(to.atEndOfMonth());
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> fs = new ArrayList<>();
            for (String s : symbols) {
                fs.add(pool.submit(() -> {
                    boolean alive = false;
                    for (String k : SeasonStudy.list(a, dir, "data/spot/monthly/klines/" + s + "/1d/")) {
                        YearMonth m = YearMonth.parse(k.substring(k.length() - 11, k.length() - 4));
                        if (!m.isBefore(from) && !m.isAfter(to)) a.fetch(k);
                        if (m.equals(to)) alive = true;
                    }
                    // daily packages after the last full month (only for pairs still trading in it) for the current-state section
                    if (extra && alive) for (LocalDate d = nowLast.withDayOfMonth(1); !d.isAfter(nowLast); d = d.plusDays(1)) a.fetch(a.dailyPath(s, d));
                    return null;
                }));
            }
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (Exception e) {
                    System.out.println("日线下载失败（该币缺数据 = 不参与）：" + e.getMessage());
                }
            }
        }
        LocalDate start = from.atDay(1);
        LocalDate end = extra ? nowLast : to.atEndOfMonth();
        int days = (int) (end.toEpochDay() - start.toEpochDay()) + 1;
        long startMs = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        Map<String, double[][]> m = new ConcurrentHashMap<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> fs = new ArrayList<>();
            for (String s : symbols) {
                fs.add(pool.submit(() -> {
                    Series x = a.load(s, startMs, days, from, to, extra ? nowLast : null);
                    if (x != null) {
                        double[] c = new double[days], v = new double[days];
                        for (int d = 0; d < days; d++) {
                            c[d] = x.has(d) ? x.close[d] : Double.NaN;
                            v[d] = x.has(d) ? x.quoteVolume[d] : 0;
                        }
                        m.put(s, new double[][]{c, v});
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
        }
        return LitStudy.panel(start, m, excluded);
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        YearMonth binFrom = YearMonth.parse(args[2]);
        YearMonth to = YearMonth.parse(args[3]);
        LocalDate binTrade = LocalDate.parse(args[4]);
        YearMonth bybFrom = YearMonth.parse(args[5]);
        LocalDate bybTrade = LocalDate.parse(args[6]);
        int threads = Integer.parseInt(args[7]);
        LocalDate nowLast = args.length > 8 && !args[8].equals("auto") ? LocalDate.parse(args[8]) : LocalDate.now(ZoneOffset.UTC).minusDays(1);
        Files.createDirectories(outDir);
        StrategyParams params = BacktestMain.loadParams();
        LocalDate last = to.atEndOfMonth();
        int trials = PRIOR_TRIALS + 2;

        Panel bin = binance(dataDir.resolve("binance-1d"), binFrom, to, nowLast, params, threads);
        S1Day[] s1bin = s1(bin, params.risk());
        boolean[] s1onBin = new boolean[bin.days];
        for (int d = 0; d < bin.days; d++) s1onBin[d] = s1bin[d].on();

        YearMonth bybTo = nowLast.isAfter(last) ? YearMonth.from(nowLast) : to;
        Panel byb = LitStudy.bybit(dataDir, bybFrom, bybTo, new HashSet<>(), params, threads);
        boolean[] s1onByb = new boolean[byb.days];
        for (int d = 0; d < byb.days; d++) {
            int k = bin.day(byb.date(d));
            s1onByb[d] = k >= 0 && k < bin.days && s1onBin[k];
        }
        GRun gb = study(byb, "Bybit（主结果）", bybTrade, last, s1onByb, null, threads);
        GRun gn = study(bin, "Binance（旁证，含已下架币）", binTrade, last, s1onBin, s1bin, threads);

        StringBuilder md = new StringBuilder("# 山寨季开关轮动回测结果（AltGateStudy 自动生成）\n\n试验数：本次 2 组（S1、S2；无开关的轮动 21 日只作参照，上一轮已计），台账累计 ")
                .append(trials).append(" 组（Bonferroni 按 ").append(trials).append(" 计）。随机组合每组 ").append(LitStudy.RANDOMS).append(" 个。\n");
        md.append("\n## 开关与季节明细\n");
        for (GRun g : List.of(gb, gn)) md.append(gateSummary(g));
        for (LocalDate[] s : LitStudy.SEASONS) md.append(season(gb, s[0], s[1], "山寨季"));
        md.append(season(gn, LocalDate.of(2021, 1, 4), LocalDate.of(2021, 5, 15), "旁证 2021 年第一波"));
        md.append(season(gn, LocalDate.of(2021, 7, 20), LocalDate.of(2021, 11, 10), "旁证 2021 年第二波"));
        for (LocalDate[] s : LitStudy.SEASONS) md.append(season(gn, s[0], s[1], "旁证 山寨季"));
        md.append("\n## 当前状态（只是信息，不是交易建议）\n");
        md.append(current(gb, null, null, null));
        md.append(current(gn, s1bin, bin, "Binance 前 150"));
        md.append("\n## 每次打开明细\n");
        md.append(episodeTable(gb)).append(episodeTable(gn));
        md.append("\n## 总表、样本外、六种行情、按年（LitStudy 同一套报告）\n");
        md.append(LitStudy.report(List.of(gb.run, gn.run), trials));
        Files.writeString(outDir.resolve("report.md"), md.toString());
        Files.writeString(outDir.resolve("summary.csv"), LitStudy.csv(List.of(gb.run, gn.run), trials));
        for (GRun g : List.of(gb, gn)) {
            Run run = g.run;
            StringBuilder e = new StringBuilder("date,s1,s2,ref,g1,g2\n");
            for (int d = run.from; d <= run.to; d++) {
                e.append(run.p.date(d)).append(',').append(g.gates.get(0).on()[d] ? 1 : 0).append(',').append(g.gates.get(1).on()[d] ? 1 : 0);
                for (Row r : run.rows) e.append(String.format(Locale.ROOT, ",%.5f", r.real().nav()[d - run.from]));
                e.append('\n');
            }
            Files.writeString(outDir.resolve("gates-" + (run.label.startsWith("Bybit") ? "bybit" : "binance") + ".csv"), e.toString());
        }
        System.out.println(md);
    }
}
