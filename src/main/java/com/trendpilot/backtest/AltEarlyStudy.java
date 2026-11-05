package com.trendpilot.backtest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trendpilot.config.StrategyParams;

/**
 * Alt-season early entry study (research only, 2026-11-05; never traded live). Pre-registered in
 * docs/策略对比记录/2026-11-05-山寨季提前埋伏.md before any result was seen:
 * <ul>
 * <li>five leading indicators with fixed parameters (ETH/BTC turn, alt breadth repair, alt basket / BTC 60-day high,
 * stablecoin supply +3% in 30 days, BTC sideways 2-4 weeks after a 90-day high);</li>
 * <li>alt seasons found objectively by a 20% zigzag on alt basket / BTC;</li>
 * <li>one tiered plan: 1/3 in when any two indicators are on, full when S2 confirms, out on a 20% drawdown, S2 off,
 * or 45 days without S2; coin choice = 21-day risk-adjusted rotation (top 5 of 40, inverse volatility, 25% cap);</li>
 * <li>controls: S2 only, equal-weight top 40, BTC, and 1,000 random placements of the same holding episodes.</li>
 * </ul>
 * <pre>
 * java -cp ... com.trendpilot.backtest.AltEarlyStudy dataDir outDir binFrom binTo lastDay binEval bybFrom bybTo bybEval threads
 *   lastDay = auto (yesterday UTC) | yyyy-MM-dd | none; Binance daily packages in dataDir/binance-1d, Bybit 15m archive = dataDir
 * </pre>
 */
public final class AltEarlyStudy {

    static final double COST = LitStudy.COST;
    static final double START = 10_000;
    static final int RANDOMS = 1000;
    static final int EVENT_DAYS = 14;
    static final int DEBOUNCE = 7;
    static final int LEAD_WINDOW = 60;
    static final int FALSE_DAYS = 60;
    static final int TIMEOUT = 45;
    static final double DD_EXIT = 0.20;
    static final double TIER1 = 1.0 / 3;
    static final int MIN_POOL = 20;
    static final int ROT_L = 21;
    static final int ROT_K = 5;
    /** Rule-family trials in docs/策略对比记录/README.md before this study. */
    static final int PRIOR_TRIALS = 391;
    static final int THIS_TRIALS = 7;
    static final String[] IND = {"a ETH/BTC 转强", "b 山寨广度修复", "c 篮子/BTC 60 日新高", "d 稳定币 30 日 > 3%", "e BTC 新高后横盘"};

    // ------------------------------------------------------------------ signals

    static final class Signals {
        final double[] alt, r, ethbtc, breadth, stable30, btcFromHigh;
        final int[] sinceHigh;
        final boolean[][] on = new boolean[5][];
        final boolean[][] fire = new boolean[5][];
        final int[] count;
        final boolean[] s2;

        Signals(int days) {
            alt = nan(days);
            r = nan(days);
            ethbtc = nan(days);
            breadth = nan(days);
            stable30 = nan(days);
            btcFromHigh = nan(days);
            sinceHigh = new int[days];
            Arrays.fill(sinceHigh, -1);
            for (int k = 0; k < 5; k++) {
                on[k] = new boolean[days];
                fire[k] = new boolean[days];
            }
            count = new int[days];
            s2 = new boolean[days];
        }
    }

    static double[] nan(int n) {
        double[] x = new double[n];
        Arrays.fill(x, Double.NaN);
        return x;
    }

    /** Simple mean of x[d-l+1..d]; NaN if any value is missing. */
    static double sma(double[] x, int d, int l) {
        if (d - l + 1 < 0 || d >= x.length) return Double.NaN;
        double s = 0;
        for (int j = d - l + 1; j <= d; j++) {
            if (Double.isNaN(x[j])) return Double.NaN;
            s += x[j];
        }
        return s / l;
    }

    /** @param stable total stablecoin supply by epoch day (value stamped at 00:00 UTC of that day) */
    static Signals signals(LitStudy.Panel p, Map<Long, Double> stable) {
        int days = p.days;
        Signals s = new Signals(days);
        int btc = p.btc, eth = Arrays.asList(p.syms).indexOf("ETHUSDT");
        // alt basket: hold yesterday's pool equal weight
        for (int d = 1; d < days; d++) {
            int[] mem = p.pool[d - 1];
            if (mem.length < MIN_POOL) continue; // basket undefined = no signal
            double base = Double.isNaN(s.alt[d - 1]) ? 1 : s.alt[d - 1];
            double sum = 0;
            for (int i : mem) sum += p.cpx[i][d] / p.cpx[i][d - 1] - 1;
            s.alt[d] = base * (1 + sum / mem.length);
        }
        for (int d = 0; d < days; d++) {
            boolean b = btc >= 0 && p.valid(btc, d);
            if (b && !Double.isNaN(s.alt[d])) s.r[d] = s.alt[d] / p.cpx[btc][d];
            if (b && eth >= 0 && p.valid(eth, d)) s.ethbtc[d] = p.cpx[eth][d] / p.cpx[btc][d];
            int[] mem = p.pool[d];
            int n = 0, up = 0;
            for (int i : mem) {
                double m = p.sma(i, d, 50);
                if (Double.isNaN(m) || !p.valid(i, d)) continue;
                n++;
                if (p.cpx[i][d] > m) up++;
            }
            if (n >= MIN_POOL) s.breadth[d] = (double) up / n;
            long e = p.date(d).toEpochDay();
            Double v0 = stable.get(e), v30 = stable.get(e - 30);
            if (v0 != null && v30 != null && v30 > 0) s.stable30[d] = v0 / v30 - 1;
        }
        // BTC 90-day closing high
        int lastHigh = -1;
        for (int d = 0; d < days; d++) {
            if (btc < 0 || !p.valid(btc, d)) continue;
            double[] rg = p.range(btc, d - 89, d);
            if (rg != null && p.cpx[btc][d] >= rg[0]) lastHigh = d;
            if (lastHigh >= 0) {
                s.sinceHigh[d] = d - lastHigh;
                s.btcFromHigh[d] = p.cpx[btc][d] / p.cpx[btc][lastHigh] - 1;
            }
        }
        for (int d = 0; d < days; d++) {
            // a: ETH/BTC above its 50-day average and the average rising over 10 days
            double m = sma(s.ethbtc, d, 50), m10 = sma(s.ethbtc, d - 10, 50);
            s.on[0][d] = !Double.isNaN(m) && !Double.isNaN(m10) && s.ethbtc[d] > m && m > m10;
            // b: breadth crosses above 60% after having been below 30% in the last 60 days
            if (d >= 60 && !Double.isNaN(s.breadth[d]) && s.breadth[d] >= 0.6 && !Double.isNaN(s.breadth[d - 1]) && s.breadth[d - 1] < 0.6) {
                double lo = Double.POSITIVE_INFINITY;
                for (int j = d - 60; j < d; j++) if (!Double.isNaN(s.breadth[j])) lo = Math.min(lo, s.breadth[j]);
                s.fire[1][d] = lo < 0.3;
            }
            // c: alt basket / BTC at a 60-day closing high
            if (d >= 59 && !Double.isNaN(s.r[d])) {
                boolean ok = true;
                for (int j = d - 59; j < d && ok; j++) ok = !Double.isNaN(s.r[j]) && s.r[d] >= s.r[j];
                s.fire[2][d] = ok;
            }
            // d: stablecoin supply up more than 3% in 30 days
            s.on[3][d] = !Double.isNaN(s.stable30[d]) && s.stable30[d] > 0.03;
            // e: last BTC 90-day high 14-28 days ago and BTC never below 90% of it since
            int h = s.sinceHigh[d];
            if (btc >= 0 && p.valid(btc, d) && h >= 14 && h <= 28) {
                double hi = p.cpx[btc][d - h], lo = Double.POSITIVE_INFINITY;
                for (int j = d - h + 1; j <= d; j++) lo = Math.min(lo, p.cpx[btc][j]);
                s.on[4][d] = lo >= 0.9 * hi;
            }
        }
        for (int k : new int[]{1, 2}) {
            for (int d = 0; d < days; d++) {
                boolean a = false;
                for (int j = Math.max(0, d - EVENT_DAYS + 1); j <= d && !a; j++) a = s.fire[k][j];
                s.on[k][d] = a;
            }
        }
        for (int d = 0; d < days; d++) {
            int c = 0;
            for (int k = 0; k < 5; k++) if (s.on[k][d]) c++;
            s.count[d] = c;
            double mr = sma(s.r, d, 50), ma = sma(s.alt, d, 50);
            s.s2[d] = !Double.isNaN(mr) && !Double.isNaN(ma) && s.r[d] > mr && s.alt[d] > ma;
        }
        return s;
    }

    /** Off-to-on transitions after at least DEBOUNCE off days, within [from, to]. */
    static List<Integer> triggers(boolean[] on, int from, int to) {
        List<Integer> t = new ArrayList<>();
        for (int d = Math.max(from, DEBOUNCE); d <= to; d++) {
            if (!on[d]) continue;
            boolean quiet = true;
            for (int j = d - DEBOUNCE; j < d && quiet; j++) quiet = !on[j];
            if (quiet) t.add(d);
        }
        return t;
    }

    // ------------------------------------------------------------------ seasons

    record Season(int trough, int peak, boolean ongoing, double rGain, double altRunUp) {
    }

    /** 20% zigzag on R; up-legs with R +30%, ALT up in USD and at least 14 days long. */
    static List<Season> seasons(double[] r, double[] alt, int from, int to) {
        List<int[]> legs = new ArrayList<>();
        int dir = 0, lo = -1, hi = -1, trough = -1;
        for (int d = from; d <= to; d++) {
            double v = r[d];
            if (Double.isNaN(v)) continue;
            if (lo < 0) {
                lo = hi = d;
                continue;
            }
            if (dir == 0) {
                if (v > r[hi]) hi = d;
                if (v < r[lo]) lo = d;
                if (v >= r[lo] * 1.2) {
                    dir = 1;
                    trough = lo;
                    hi = d;
                } else if (v <= r[hi] * 0.8) {
                    dir = -1;
                    lo = d;
                }
            } else if (dir == 1) {
                if (v > r[hi]) hi = d;
                if (v <= r[hi] * 0.8) {
                    legs.add(new int[]{trough, hi, 0});
                    dir = -1;
                    lo = d;
                }
            } else {
                if (v < r[lo]) lo = d;
                if (v >= r[lo] * 1.2) {
                    dir = 1;
                    trough = lo;
                    hi = d;
                }
            }
        }
        if (dir == 1) legs.add(new int[]{trough, hi, 1});
        List<Season> out = new ArrayList<>();
        for (int[] g : legs) {
            double gain = r[g[1]] / r[g[0]] - 1;
            if (gain < 0.3 || !(alt[g[1]] > alt[g[0]]) || g[1] - g[0] < 14) continue;
            double run = 0, min = Double.POSITIVE_INFINITY;
            for (int d = g[0]; d <= g[1]; d++) {
                if (Double.isNaN(alt[d])) continue;
                min = Math.min(min, alt[d]);
                run = Math.max(run, alt[d] / min - 1);
            }
            out.add(new Season(g[0], g[1], g[2] == 1, gain, run));
        }
        return out;
    }

    // ------------------------------------------------------------------ book

    record Pick(int[] syms, double[] w) {
    }

    /** 21-day risk-adjusted rotation for day d: top 5 of the pool, inverse-volatility weights, 25% cap (sum may be < 1). */
    static Pick pick(LitStudy.Panel p, int d) {
        List<Integer> ci = new ArrayList<>();
        List<Double> sc = new ArrayList<>(), vo = new ArrayList<>();
        for (int i : p.pool(d, LitStudy.POOL)) {
            double r = p.ret(i, d, ROT_L), v = p.vol(i, d, ROT_L);
            if (Double.isNaN(r) || Double.isNaN(v)) continue;
            ci.add(i);
            sc.add(r / v);
            vo.add(v);
        }
        LitStudy.Choice c = new LitStudy.Choice(LitStudy.ints(ci), LitStudy.dbls(sc), LitStudy.dbls(vo), ROT_K, true);
        int[] top = c.top();
        double[] w = c.weights(top);
        int[] syms = new int[top.length];
        for (int j = 0; j < top.length; j++) syms[j] = c.cand()[top[j]];
        return new Pick(syms, w);
    }

    enum Mode { COMBO, S2ONLY, PLAN }

    record Ep(int entry, int exit, int fullAt, String reason, double ret, double peakRet, double giveback, Set<String> coins) {
    }

    record Result(double[] nav, double[] expo, List<Ep> eps) {
    }

    /**
     * Daily book from day from to day to (nav[k] = value at close of from + k after trades, start 1). PLAN follows
     * {@code plan[d]} (target exposure 0, 1/3 or 1) without any exit rule.
     */
    static Result run(LitStudy.Panel p, Signals s, Pick[] picks, int from, int to, Mode mode, double[] plan) {
        int n = p.syms.length;
        double[] units = new double[n];
        double cash = 1;
        double[] nav = new double[to - from + 1], expo = new double[nav.length];
        List<Ep> eps = new ArrayList<>();
        double tier = 0;
        int entry = -1, fullAt = -1;
        double navStart = 0, peak = 0;
        boolean needS2Off = false, needDrop = false;
        Set<String> coins = new HashSet<>();
        for (int d = from; d <= to; d++) {
            for (int i = 0; i < n; i++) {
                if (units[i] > 0 && p.stale[i][d] >= LitStudy.DELIST_DAYS) {
                    cash += units[i] * p.cpx[i][d] * 0.995 * (1 - COST);
                    units[i] = 0;
                }
            }
            double v = cash;
            for (int i = 0; i < n; i++) if (units[i] > 0) v += units[i] * p.cpx[i][d];
            double target = tier;
            String reason = null;
            if (mode == Mode.PLAN) {
                target = plan[d];
                if (tier > 0 && target == 0) reason = "到期";
            } else {
                if (tier > 0) {
                    peak = Math.max(peak, v);
                    if (v <= (1 - DD_EXIT) * peak) {
                        target = 0;
                        reason = "回撤 20%";
                        needS2Off = true;
                    } else if (tier == 1 && !s.s2[d]) {
                        target = 0;
                        reason = "S2 关闭";
                    } else if (tier < 1 && s.s2[d]) {
                        target = 1;
                    } else if (tier < 1 && d - entry >= TIMEOUT) {
                        target = 0;
                        reason = "45 天未确认";
                        needDrop = true;
                    }
                } else {
                    if (!s.s2[d]) needS2Off = false;
                    if (s.count[d] < 2) needDrop = false;
                    if (mode == Mode.COMBO && !needS2Off && !needDrop && s.count[d] >= 2) target = s.s2[d] ? 1 : TIER1;
                    if (mode == Mode.S2ONLY && !needS2Off && s.s2[d]) target = 1;
                }
            }
            if (tier == 0 && target > 0) {
                entry = d;
                navStart = v;
                peak = v;
                fullAt = -1;
                coins = new HashSet<>();
            }
            if (target == 1 && fullAt < 0) fullAt = d;
            boolean trade = target != tier || (target > 0 && p.sunday(d));
            if (trade) {
                double[] tv = new double[n];
                if (target > 0) {
                    Pick pk = picks[d];
                    for (int j = 0; j < pk.syms().length; j++) tv[pk.syms()[j]] += pk.w()[j] * target * v;
                }
                double traded = 0;
                for (int i = 0; i < n; i++) traded += Math.abs(tv[i] - (units[i] > 0 ? units[i] * p.cpx[i][d] : 0));
                double cost = traded * COST;
                double scale = v > 0 ? (v - cost) / v : 0;
                double inv = 0;
                for (int i = 0; i < n; i++) {
                    double x = tv[i] * scale;
                    units[i] = x > 0 ? x / p.cpx[i][d] : 0;
                    if (x > 0) coins.add(p.syms[i].substring(0, p.syms[i].length() - 4));
                    inv += x;
                }
                cash = v - cost - inv;
                v -= cost;
            }
            if (tier > 0 && target == 0) {
                eps.add(new Ep(entry, d, fullAt, reason, v / navStart - 1, peak / navStart - 1, 1 - v / peak, coins));
            }
            tier = target;
            double inv = 0;
            for (int i = 0; i < n; i++) if (units[i] > 0) inv += units[i] * p.cpx[i][d];
            nav[d - from] = cash + inv;
            expo[d - from] = nav[d - from] > 0 ? inv / nav[d - from] : 0;
            if (tier > 0) peak = Math.max(peak, nav[d - from]);
        }
        if (tier > 0) {
            double v = nav[to - from];
            eps.add(new Ep(entry, -1, fullAt, "未结束", v / navStart - 1, peak / navStart - 1, 1 - v / peak, coins));
        }
        return new Result(nav, expo, eps);
    }

    /** Random non-overlapping placement of the real episodes (same length and same day of going full). */
    static double[] randomPlan(int days, int from, int to, List<Ep> eps, SplittableRandom rnd) {
        double[] plan = new double[days];
        boolean[] used = new boolean[days];
        Integer[] order = new Integer[eps.size()];
        for (int j = 0; j < order.length; j++) order[j] = j;
        for (int j = order.length - 1; j > 0; j--) {
            int t = rnd.nextInt(j + 1);
            Integer x = order[j];
            order[j] = order[t];
            order[t] = x;
        }
        for (int j : order) {
            Ep e = eps.get(j);
            int end = e.exit() < 0 ? to : e.exit();
            int len = end - e.entry();
            int full = e.fullAt() < 0 ? -1 : e.fullAt() - e.entry();
            if (len <= 0 || len + 1 > to - from) continue;
            for (int tries = 0; tries < 10_000; tries++) {
                int st = from + rnd.nextInt(to - from - len);
                boolean free = true;
                for (int d = Math.max(from, st - 1); d <= Math.min(to, st + len + 1) && free; d++) free = !used[d];
                if (!free) continue;
                for (int d = st; d < st + len; d++) {
                    plan[d] = full >= 0 && d - st >= full ? 1 : TIER1;
                    used[d] = true;
                }
                used[st + len] = true;
                break;
            }
        }
        return plan;
    }

    // ------------------------------------------------------------------ study

    record Rand(double[] finalU, double[] meanEp) {
    }

    static final class Data {
        final LitStudy.Panel p;
        final String label;
        final int from, to;
        final Signals s;
        final Pick[] picks;
        final List<Season> seasons;
        Result combo, s2only;
        double[] ew40, btc;
        Rand randCombo, randS2;

        Data(LitStudy.Panel p, String label, LocalDate evalFrom, LocalDate last, Map<Long, Double> stable) {
            this.p = p;
            this.label = label;
            from = Math.max(1, p.day(evalFrom));
            to = Math.min(p.days - 1, p.day(last));
            s = signals(p, stable);
            picks = new Pick[p.days];
            for (int d = from; d <= to; d++) picks[d] = pick(p, d);
            seasons = AltEarlyStudy.seasons(s.r, s.alt, from, to);
        }
    }

    static void study(Data x, int threads) throws Exception {
        x.combo = run(x.p, x.s, x.picks, x.from, x.to, Mode.COMBO, null);
        x.s2only = run(x.p, x.s, x.picks, x.from, x.to, Mode.S2ONLY, null);
        x.ew40 = LitStudy.runWeekly(x.p, x.from, x.to, 1, LitStudy.equalPool(x.p, x.from, x.to, LitStudy.POOL), null).nav();
        x.btc = LitStudy.btcHold(x.p, x.from, x.to);
        x.randCombo = random(x, x.combo.eps(), threads, 11);
        x.randS2 = random(x, x.s2only.eps(), threads, 22);
    }

    static Rand random(Data x, List<Ep> eps, int threads, long seed) throws Exception {
        double[] fin = new double[RANDOMS], mean = new double[RANDOMS];
        try (ExecutorService ex = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> fs = new ArrayList<>();
            for (int k = 0; k < RANDOMS; k++) {
                final int kk = k;
                fs.add(ex.submit(() -> {
                    SplittableRandom r = new SplittableRandom(seed * 1_000_003L + kk);
                    double[] plan = randomPlan(x.p.days, x.from, x.to, eps, r);
                    Result res = run(x.p, x.s, x.picks, x.from, x.to, Mode.PLAN, plan);
                    fin[kk] = START * (res.nav()[res.nav().length - 1] - 1);
                    double s = 0;
                    for (Ep e : res.eps()) s += e.ret();
                    mean[kk] = res.eps().isEmpty() ? 0 : s / res.eps().size();
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
        }
        Arrays.sort(fin);
        Arrays.sort(mean);
        return new Rand(fin, mean);
    }

    // ------------------------------------------------------------------ report

    static String pc(double x) {
        return Double.isNaN(x) ? "—" : String.format(Locale.ROOT, "%+.1f%%", 100 * x);
    }

    static String u(double x) {
        return Double.isNaN(x) ? "—" : String.format(Locale.ROOT, "%+,.0f", x);
    }

    static String days(int x) {
        return x == Integer.MIN_VALUE ? "没触发" : (x <= 0 ? "早 " + (-x) + " 天" : "晚 " + x + " 天");
    }

    static double below(double[] sorted, double v) {
        int k = 0;
        for (double y : sorted) if (y < v) k++;
        return (double) k / sorted.length;
    }

    static double[] bootMean(List<Ep> eps, long seed) {
        int n = eps.size();
        if (n < 2) return new double[]{Double.NaN, Double.NaN};
        SplittableRandom r = new SplittableRandom(seed);
        double[] m = new double[5000];
        for (int k = 0; k < m.length; k++) {
            double s = 0;
            for (int j = 0; j < n; j++) s += eps.get(r.nextInt(n)).ret();
            m[k] = s / n;
        }
        Arrays.sort(m);
        return new double[]{m[124], m[4874]};
    }

    /** Exact (Clopper-Pearson) 95% interval for k of n. */
    static double[] binomCi(int k, int n) {
        if (n == 0) return new double[]{Double.NaN, Double.NaN};
        double lo = k == 0 ? 0 : bisect(k, n, 0.025, true), hi = k == n ? 1 : bisect(k, n, 0.025, false);
        return new double[]{lo, hi};
    }

    private static double bisect(int k, int n, double a, boolean lower) {
        double l = 0, h = 1;
        for (int it = 0; it < 60; it++) {
            double m = (l + h) / 2;
            double tail = lower ? upperTail(k, n, m) : lowerTail(k, n, m);
            if (lower == (tail < a)) l = m;
            else h = m;
        }
        return (l + h) / 2;
    }

    private static double lowerTail(int k, int n, double q) { // P(X <= k)
        double s = 0;
        for (int j = 0; j <= k; j++) s += pmf(j, n, q);
        return s;
    }

    private static double upperTail(int k, int n, double q) { // P(X >= k)
        double s = 0;
        for (int j = k; j <= n; j++) s += pmf(j, n, q);
        return s;
    }

    private static double pmf(int j, int n, double q) {
        double c = 0;
        for (int t = 1; t <= j; t++) c += Math.log(n - j + t) - Math.log(t);
        return Math.exp(c + j * Math.log(Math.max(q, 1e-300)) + (n - j) * Math.log(Math.max(1 - q, 1e-300)));
    }

    static int seasonOf(Data x, int day) {
        for (int k = 0; k < x.seasons.size(); k++) {
            Season s = x.seasons.get(k);
            if (day >= s.trough() - LEAD_WINDOW && day <= s.peak()) return k;
        }
        return -1;
    }

    static String report(Data x) {
        LitStudy.Panel p = x.p;
        Signals s = x.s;
        StringBuilder b = new StringBuilder();
        b.append("\n## ").append(x.label).append("：").append(p.date(x.from)).append(" ～ ").append(p.date(x.to)).append("（交易对 ")
                .append(p.syms.length).append(" 个）\n\n");
        b.append("### 数据认定的山寨季（R = 山寨等权篮子 / BTC，20% zigzag，R 涨 ≥ 30%）\n\n");
        b.append("| # | 启动日（R 低点） | R 高点 | 天数 | R 涨幅 | 篮子美元最大涨幅 | 同期 BTC |\n|---:|---|---|---:|---:|---:|---:|\n");
        for (int k = 0; k < x.seasons.size(); k++) {
            Season q = x.seasons.get(k);
            double bt = p.cpx[p.btc][q.peak()] / p.cpx[p.btc][q.trough()] - 1;
            b.append(String.format(Locale.ROOT, "| %d | %s | %s%s | %d | %s | %s | %s |%n", k + 1, p.date(q.trough()), p.date(q.peak()),
                    q.ongoing() ? "（未确认）" : "", q.peak() - q.trough(), pc(q.rGain()), pc(q.altRunUp()), pc(bt)));
        }
        // indicator lead / lag and false alarms
        b.append("\n### 领先指标：每段比启动日早 / 晚多少天，误报次数\n\n");
        b.append("窗口：启动日前 60 天到这段 R 高点之间的第一次触发。误报 = 触发后 60 天 R 不高于触发当天。\n\n| 指标 |");
        for (int k = 0; k < x.seasons.size(); k++) b.append(" 第 ").append(k + 1).append(" 段 |");
        b.append(" 早于启动或晚 ≤ 7 天的段数 | 全期触发 | 误报 | 误报占比 [95% 区间] |\n|---|");
        for (int k = 0; k <= x.seasons.size() + 3; k++) b.append("---:|");
        b.append('\n');
        boolean[] anyTwo = new boolean[p.days];
        for (int d = 0; d < p.days; d++) anyTwo[d] = s.count[d] >= 2;
        List<String> names = new ArrayList<>(Arrays.asList(IND));
        names.add("任意 2 个同时亮（第一档条件）");
        names.add("S2 确认开关");
        for (int k = 0; k < names.size(); k++) {
            boolean[] on = k < 5 ? s.on[k] : k == 5 ? anyTwo : s.s2;
            List<Integer> tr = triggers(on, x.from, x.to);
            b.append("| ").append(names.get(k)).append(" |");
            int good = 0;
            for (Season q : x.seasons) {
                int first = Integer.MIN_VALUE;
                for (int t : tr) if (t >= q.trough() - LEAD_WINDOW && t <= q.peak()) {
                    first = t - q.trough();
                    break;
                }
                if (first != Integer.MIN_VALUE && first <= 7) good++;
                b.append(' ').append(days(first)).append(on[q.trough()] ? "（启动日已亮）" : "").append(" |");
            }
            int n = 0, bad = 0;
            for (int t : tr) {
                if (t + FALSE_DAYS > x.to || Double.isNaN(s.r[t]) || Double.isNaN(s.r[t + FALSE_DAYS])) continue;
                n++;
                if (s.r[t + FALSE_DAYS] <= s.r[t]) bad++;
            }
            double[] ci = binomCi(bad, n);
            b.append(String.format(Locale.ROOT, " %d / %d | %d | %d | %s [%.0f%%, %.0f%%] |%n", good, x.seasons.size(), tr.size(), bad,
                    n == 0 ? "—" : String.format(Locale.ROOT, "%.0f%%", 100.0 * bad / n), 100 * ci[0], 100 * ci[1]));
        }
        // whole period
        b.append("\n### 全期（10,000U 起始，扣费后）\n\n| 方案 | 全期 U | 最大回撤 | 持仓次数 | 平均持有天数 | 每次持仓平均收益 [95% 区间] | 随机进场：全期 U 中位数 / 策略所在分位 | 随机：每次平均收益中位 [2.5%, 97.5%] |\n|---|---:|---:|---:|---:|---:|---:|---:|\n");
        b.append(row(x, "埋伏组合（第一档 + S2 加满）", x.combo, x.randCombo, 1));
        b.append(row(x, "只用 S2 进场", x.s2only, x.randS2, 2));
        b.append(String.format(Locale.ROOT, "| 前 40 等权持有（每周再平衡） | %s | %.1f%% | | | | | |%n", u(START * (last(x.ew40) - 1)), 100 * LitStudy.maxDd(x.ew40, 0, x.ew40.length - 1)));
        b.append(String.format(Locale.ROOT, "| BTC 持有 | %s | %.1f%% | | | | | |%n", u(START * (last(x.btc) - 1)), 100 * LitStudy.maxDd(x.btc, 0, x.btc.length - 1)));
        // yearly
        b.append("\n#### 分年收益\n\n| 年 | 埋伏组合 | 只用 S2 | 前 40 等权 | BTC |\n|---|---:|---:|---:|---:|\n");
        for (int y = p.date(x.from).getYear(); y <= p.date(x.to).getYear(); y++) {
            int a = Math.max(x.from, p.day(LocalDate.of(y, 1, 1))) - x.from, e = Math.min(x.to, p.day(LocalDate.of(y, 12, 31))) - x.from;
            int a0 = Math.max(0, a - 1);
            b.append(String.format(Locale.ROOT, "| %d | %s | %s | %s | %s |%n", y, pc(x.combo.nav()[e] / x.combo.nav()[a0] - 1),
                    pc(x.s2only.nav()[e] / x.s2only.nav()[a0] - 1), pc(x.ew40[e] / x.ew40[a0] - 1), pc(x.btc[e] / x.btc[a0] - 1)));
        }
        // per season / episode
        for (int m = 0; m < 2; m++) {
            Result res = m == 0 ? x.combo : x.s2only;
            b.append(m == 0 ? "\n### 埋伏组合：每次持仓（每次按 10,000U 单独计）\n\n" : "\n### 对照：只用 S2，每次持仓（每次按 10,000U）\n\n");
            b.append("| 属于 | 第一档进场 | 比启动日 | 加满日 | 比启动日 | 离场 | 原因 | 赚 U | 吃到这段篮子最大涨幅的 | 最高时浮盈 | 离场比最高点回吐 | 买过的币 |\n|---|---|---:|---|---:|---|---|---:|---:|---:|---:|---|\n");
            double falseU = 0;
            int falseN = 0;
            for (Ep e : res.eps()) {
                int k = seasonOf(x, e.entry());
                Season q = k < 0 ? null : x.seasons.get(k);
                if (k < 0) {
                    falseU += START * e.ret();
                    falseN++;
                }
                b.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |%n",
                        k < 0 ? "非山寨季" : "第 " + (k + 1) + " 段", p.date(e.entry()), q == null ? "" : days(e.entry() - q.trough()),
                        e.fullAt() < 0 ? "—" : p.date(e.fullAt()).toString(), q == null || e.fullAt() < 0 ? "" : days(e.fullAt() - q.trough()),
                        e.exit() < 0 ? "持有中" : p.date(e.exit()).toString(), e.reason(), u(START * e.ret()),
                        q == null ? "" : String.format(Locale.ROOT, "%.0f%%", 100 * e.ret() / q.altRunUp()), pc(e.peakRet()),
                        String.format(Locale.ROOT, "%.1f%%", 100 * e.giveback()), String.join(" ", new java.util.TreeSet<>(e.coins()))));
            }
            b.append(String.format(Locale.ROOT, "%n非山寨季（误报）持仓 %d 次，合计 %s U（每次按 10,000U 计）。%n", falseN, u(falseU)));
            int miss = 0;
            for (int k = 0; k < x.seasons.size(); k++) {
                boolean hit = false;
                for (Ep e : res.eps()) hit |= seasonOf(x, e.entry()) == k;
                if (!hit) miss++;
            }
            b.append(String.format(Locale.ROOT, "完全没进场的山寨季：%d 段。%n", miss));
        }
        b.append(current(x));
        return b.toString();
    }

    static double last(double[] x) {
        return x[x.length - 1];
    }

    static String row(Data x, String name, Result res, Rand rnd, long seed) {
        double fin = START * (last(res.nav()) - 1);
        double dd = LitStudy.maxDd(res.nav(), 0, res.nav().length - 1);
        double hold = 0, mean = 0;
        for (Ep e : res.eps()) {
            hold += (e.exit() < 0 ? x.to : e.exit()) - e.entry();
            mean += e.ret();
        }
        int n = res.eps().size();
        double[] ci = bootMean(res.eps(), seed);
        return String.format(Locale.ROOT, "| %s | %s | %.1f%% | %d | %.0f | %s [%s, %s] | %s / %.1f%% | %s [%s, %s] |%n", name, u(fin), 100 * dd, n,
                n == 0 ? 0 : hold / n, pc(n == 0 ? Double.NaN : mean / n), pc(ci[0]), pc(ci[1]), u(LitStudy.percentile(rnd.finalU(), 0.5)),
                100 * below(rnd.finalU(), fin), pc(LitStudy.percentile(rnd.meanEp(), 0.5)), pc(LitStudy.percentile(rnd.meanEp(), 0.025)),
                pc(LitStudy.percentile(rnd.meanEp(), 0.975)));
    }

    static String current(Data x) {
        LitStudy.Panel p = x.p;
        Signals s = x.s;
        int d = x.to;
        StringBuilder b = new StringBuilder("\n### 当前状态（最新收盘 " + p.date(d) + "）\n\n| 指标 | 状态 | 数值 |\n|---|---|---|\n");
        double m = sma(s.ethbtc, d, 50), m10 = sma(s.ethbtc, d - 10, 50);
        b.append(String.format(Locale.ROOT, "| %s | %s | ETH/BTC %.5f，50 日均线 %.5f（%s），均线 10 天变化 %s |%n", IND[0], s.on[0][d] ? "亮" : "不亮",
                s.ethbtc[d], m, pc(s.ethbtc[d] / m - 1), pc(m / m10 - 1)));
        double lo = Double.POSITIVE_INFINITY;
        for (int j = d - 60; j < d; j++) if (!Double.isNaN(s.breadth[j])) lo = Math.min(lo, s.breadth[j]);
        int lastB = -1;
        for (int j = d; j >= 0 && lastB < 0; j--) if (s.fire[1][j]) lastB = j;
        b.append(String.format(Locale.ROOT, "| %s | %s | 现在 %.0f%% 在 50 日线上方；过去 60 天最低 %.0f%%；上次触发 %s |%n", IND[1], s.on[1][d] ? "亮" : "不亮",
                100 * s.breadth[d], 100 * lo, lastB < 0 ? "—" : p.date(lastB).toString()));
        double hi = Double.NEGATIVE_INFINITY;
        for (int j = d - 59; j < d; j++) if (!Double.isNaN(s.r[j])) hi = Math.max(hi, s.r[j]);
        int lastC = -1;
        for (int j = d; j >= 0 && lastC < 0; j--) if (s.fire[2][j]) lastC = j;
        b.append(String.format(Locale.ROOT, "| %s | %s | R 比 60 日最高低 %s；上次新高 %s |%n", IND[2], s.on[2][d] ? "亮" : "不亮",
                pc(s.r[d] / hi - 1), lastC < 0 ? "—" : p.date(lastC).toString()));
        b.append(String.format(Locale.ROOT, "| %s | %s | 30 日增速 %s |%n", IND[3], s.on[3][d] ? "亮" : "不亮", pc(s.stable30[d])));
        b.append(String.format(Locale.ROOT, "| %s | %s | 上次 90 日新高在 %d 天前，现价比那时 %s |%n", IND[4], s.on[4][d] ? "亮" : "不亮",
                s.sinceHigh[d], pc(s.btcFromHigh[d])));
        double mr = sma(s.r, d, 50), ma = sma(s.alt, d, 50);
        b.append(String.format(Locale.ROOT, "| S2 确认开关 | %s | R 比 50 日均线 %s，篮子比 50 日均线 %s |%n", s.s2[d] ? "开" : "关", pc(s.r[d] / mr - 1),
                pc(s.alt[d] / ma - 1)));
        b.append(String.format(Locale.ROOT, "%n亮的指标数：%d / 5（第一档需要 ≥ 2）。组合当前仓位：%.0f%%。%n", s.count[d], 100 * x.combo.expo()[d - x.from]));
        b.append("\n最近 30 天每天亮的指标数：");
        for (int j = Math.max(x.from, d - 29); j <= d; j++) b.append(s.count[j]);
        b.append('\n');
        return b.toString();
    }

    static String csv(Data x) {
        StringBuilder e = new StringBuilder("date,alt,r,ethbtc,breadth,stable30,a,b,c,d,e,count,s2,combo,s2only,ew40,btc\n");
        LitStudy.Panel p = x.p;
        Signals s = x.s;
        for (int d = x.from; d <= x.to; d++) {
            e.append(p.date(d)).append(String.format(Locale.ROOT, ",%.6f,%.8f,%.6f,%.3f,%.4f", s.alt[d], s.r[d], s.ethbtc[d], s.breadth[d], s.stable30[d]));
            for (int k = 0; k < 5; k++) e.append(s.on[k][d] ? ",1" : ",0");
            e.append(',').append(s.count[d]).append(s.s2[d] ? ",1" : ",0");
            e.append(String.format(Locale.ROOT, ",%.5f,%.5f,%.5f,%.5f%n", x.combo.nav()[d - x.from], x.s2only.nav()[d - x.from], x.ew40[d - x.from],
                    x.btc[d - x.from]));
        }
        return e.toString();
    }

    // ------------------------------------------------------------------ data

    /** DefiLlama total stablecoin supply (all pegs, USD) by epoch day; one request per UTC day, cached. Empty on failure. */
    static Map<Long, Double> stablecoins(Path dir) {
        Map<Long, Double> out = new TreeMap<>();
        try {
            Path f = dir.resolve("defillama/stablecoincharts-all-" + LocalDate.now(ZoneOffset.UTC) + ".json");
            if (!Files.exists(f)) {
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
                for (int attempt = 1; ; attempt++) {
                    HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("https://stablecoins.llama.fi/stablecoincharts/all"))
                            .timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() == 200) {
                        Files.createDirectories(f.getParent());
                        Files.writeString(f, r.body());
                        break;
                    }
                    if (attempt >= 4) throw new IllegalStateException("DefiLlama HTTP " + r.statusCode());
                    long wait = r.headers().firstValue("Retry-After").map(Long::parseLong).orElse(5L << attempt);
                    Thread.sleep(1000 * Math.min(wait, 120));
                }
            }
            for (JsonNode n : new ObjectMapper().readTree(f.toFile())) {
                long day = Long.parseLong(n.get("date").asText()) / 86_400;
                double s = 0;
                JsonNode t = n.get("totalCirculatingUSD");
                if (t == null) continue;
                for (JsonNode v : t) s += v.asDouble();
                if (s > 0) out.put(day, s);
            }
            System.out.println("稳定币总供应：" + out.size() + " 天，最新 " + (out.isEmpty() ? "—" : LocalDate.ofEpochDay(((TreeMap<Long, Double>) out).lastKey())));
        } catch (Exception e) {
            System.out.println("稳定币数据取不到（指标 d 全期不亮）：" + e.getMessage());
            out.clear();
        }
        return out;
    }

    /** Binance daily bars: monthly packages for [from, to] plus daily packages for the month after to, up to lastDay. */
    static LitStudy.Panel binance(Path dir, YearMonth from, YearMonth to, LocalDate lastDay, StrategyParams params, int threads) throws Exception {
        Set<String> excluded = new HashSet<>();
        if (lastDay == null) return LitStudy.binance(dir, from, to, excluded, params, threads);
        // monthly part (downloads and caches; reuse its symbol list)
        Archive a = new Archive(dir, "1d");
        List<String> symbols = new ArrayList<>();
        for (String s : a.usdtSymbols()) if (!com.trendpilot.universe.UniverseFilter.excludedOffline(params.universe(), s)) symbols.add(s);
        else excluded.add(s);
        LitStudy.binance(dir, from, to, new HashSet<>(), params, threads); // fills the monthly cache
        List<String> live = new ArrayList<>();
        for (String s : symbols) if (Files.exists(dir.resolve(a.monthlyPath(s, to)))) live.add(s);
        System.out.println("当月日度包：" + live.size() + " 个交易对，" + lastDay.withDayOfMonth(1) + " ～ " + lastDay);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> fs = new ArrayList<>();
            for (String s : live) {
                fs.add(pool.submit(() -> {
                    for (LocalDate d = lastDay.withDayOfMonth(1); !d.isAfter(lastDay); d = d.plusDays(1)) {
                        Path none = dir.resolve(a.dailyPath(s, d) + ".none");
                        if (!d.isBefore(lastDay.minusDays(3))) Files.deleteIfExists(none);
                        a.fetch(a.dailyPath(s, d));
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (Exception e) {
                    System.out.println("日度包下载失败（该币缺这几天 = 不参与）：" + e.getMessage());
                }
            }
        }
        LocalDate start = from.atDay(1);
        int days = (int) (lastDay.toEpochDay() - start.toEpochDay()) + 1;
        long startMs = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        Map<String, double[][]> m = new ConcurrentHashMap<>();
        Set<String> liveSet = new HashSet<>(live);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> fs = new ArrayList<>();
            for (String s : symbols) {
                fs.add(pool.submit(() -> {
                    Series x = a.load(s, startMs, days, from, to, liveSet.contains(s) ? lastDay : null);
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
        // last day with a BTC close
        LitStudy.Panel p = LitStudy.panel(start, m, excluded);
        return p;
    }

    public static void main(String[] args) throws Exception {
        Path dataDir = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        YearMonth binFrom = YearMonth.parse(args[2]);
        YearMonth binTo = YearMonth.parse(args[3]);
        LocalDate lastDay = args[4].equals("auto") ? LocalDate.now(ZoneOffset.UTC).minusDays(1) : args[4].equals("none") ? null : LocalDate.parse(args[4]);
        if (lastDay != null && !lastDay.isAfter(binTo.atEndOfMonth())) lastDay = null;
        LocalDate binEval = LocalDate.parse(args[5]);
        YearMonth bybFrom = YearMonth.parse(args[6]);
        YearMonth bybTo = YearMonth.parse(args[7]);
        LocalDate bybEval = LocalDate.parse(args[8]);
        int threads = Integer.parseInt(args[9]);
        Files.createDirectories(outDir);
        StrategyParams params = BacktestMain.loadParams();
        Map<Long, Double> stable = stablecoins(dataDir);
        Map<String, Data> runs = new LinkedHashMap<>();
        LitStudy.Panel bp = binance(dataDir.resolve("binance-1d"), binFrom, binTo, lastDay, params, threads);
        LocalDate binLast = lastDay == null ? binTo.atEndOfMonth() : lastDay;
        while (!bp.valid(bp.btc, bp.day(binLast)) && binLast.isAfter(binTo.atEndOfMonth())) binLast = binLast.minusDays(1);
        runs.put("binance", new Data(bp, "Binance（主数据，含已下架币）", binEval, binLast, stable));
        if (Files.exists(dataDir.resolve("symbols.txt"))) {
            LitStudy.Panel yp = LitStudy.bybit(dataDir, bybFrom, bybTo, new HashSet<>(), params, threads);
            runs.put("bybit", new Data(yp, "Bybit（旁证）", bybEval, bybTo.atEndOfMonth(), stable));
        }
        StringBuilder md = new StringBuilder("# 山寨季提前埋伏结果（AltEarlyStudy 自动生成）\n\n本次登记 " + THIS_TRIALS + " 组（5 个指标 + 2 个交易组合），台账累计 "
                + (PRIOR_TRIALS + THIS_TRIALS) + " 组。随机进场每个方案 " + RANDOMS + " 次。\n");
        for (Map.Entry<String, Data> e : runs.entrySet()) {
            study(e.getValue(), threads);
            md.append(report(e.getValue()));
            Files.writeString(outDir.resolve("daily-" + e.getKey() + ".csv"), csv(e.getValue()));
        }
        Files.writeString(outDir.resolve("report.md"), md.toString());
        System.out.println(md);
    }
}
