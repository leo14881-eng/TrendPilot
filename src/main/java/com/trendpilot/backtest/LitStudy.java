package com.trendpilot.backtest;

import java.nio.file.Files;
import java.time.DayOfWeek;
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
import java.util.stream.IntStream;

import com.trendpilot.config.StrategyParams;

/**
 * Literature alt strategies on daily bars (research only, 2026-11-05; never traded live):
 * <ol>
 * <li>weekly risk-adjusted momentum rotation (Liu, Tsyvinski &amp; Wu): score = L-day return / L-day volatility, top 5,
 * inverse-volatility weights capped at 25%, weekly; L in {14, 21, 28}, with and without a BTC 200-day-MA cash filter;</li>
 * <li>multi-lookback Donchian trend following (Zarattini, Pagani &amp; Barbon 2025): top 20, close above the highest close of
 * the lookback, trailing stop at the mid of the highest / lowest close (ratchets up only), 25% annual volatility target per
 * position, total exposure at most 100%; lookbacks 20 / 60 / 120 / 250 and an equal-sleeve ensemble;</li>
 * <li>CTREND-lite (Fieberg et al. 2024): mean cross-sectional z of close / SMA(5..200) plus z of 20d / 100d volume, buy top
 * quintile equal weight, hold 1 or 2 weeks (overlapping sleeves);</li>
 * <li>weekly reversal (control): buy the 5 worst 7-day returns, hold 7 days.</li>
 * </ol>
 * Common: USDT spot, no stablecoins / leveraged / tokenized stocks, listed at least 90 days, point-in-time top 40 by median
 * 30-day quote volume, long only, 0.1% fee + 0.05% slippage per side, decisions at the daily close (Sunday close = Monday
 * 00:00 UTC for weekly rules) and filled at that close. Every rule has 1,000 random twins (same days, counts, pool, weighting,
 * costs and holding periods; Donchian twins replay each real trade's size and holding time on a random pool coin).
 * <pre>
 * java -cp ... com.trendpilot.backtest.LitStudy dataDir outDir mode binFromMonth toMonth binTradeFrom bybFromMonth bybTradeFrom threads
 *   mode = both | binance | bybit; Binance daily packages go to dataDir/binance-1d, Bybit 15m archive (BybitArchive) is dataDir
 * </pre>
 */
public final class LitStudy {

    static final double FEE = 0.001;
    static final double SLIP = 0.0005;
    static final double COST = FEE + SLIP;
    static final double CAP = 0.25;
    static final int POOL = 40;
    static final int DON_POOL = 20;
    static final int MIN_AGE = 90;
    static final int RANDOMS = 1000;
    static final double START_U = 2000;
    static final double TRAIN_SHARE = 0.6;
    /** Rule-family trials in docs/策略对比记录/README.md before this study. */
    static final int PRIOR_TRIALS = 377;
    static final int DELIST_DAYS = 7;

    // ------------------------------------------------------------------ data

    /** Daily close / quote volume panel on a common day grid (day k = startDate + k, close = price at next 00:00 UTC). */
    static final class Panel {
        final LocalDate startDate;
        final int days;
        final String[] syms;
        final double[][] cpx; // carried close (last valid close at or before d), NaN before listing
        final double[][] qv; // quote volume, 0 when no data
        final int[][] stale; // days since last valid close
        final int[] first;
        final boolean[] alt;
        final int btc;
        final int[][] pool; // point-in-time top POOL by median 30d quote volume

        Panel(LocalDate startDate, String[] syms, double[][] close, double[][] qvol, Set<String> excluded) {
            this.startDate = startDate;
            this.syms = syms;
            this.days = close.length == 0 ? 0 : close[0].length;
            int n = syms.length;
            cpx = new double[n][days];
            qv = new double[n][days];
            stale = new int[n][days];
            first = new int[n];
            alt = new boolean[n];
            int b = -1;
            Set<String> all = new HashSet<>(Arrays.asList(syms));
            for (int i = 0; i < n; i++) {
                if (syms[i].equals(MarketData.BTC)) b = i;
                alt[i] = !syms[i].equals(MarketData.BTC) && !excluded.contains(syms[i]) && !excludedBase(syms[i], all);
                double last = Double.NaN;
                int since = 1_000_000;
                first[i] = -1;
                for (int d = 0; d < days; d++) {
                    double c = close[i][d];
                    if (c > 0) {
                        last = c;
                        since = 0;
                        if (first[i] < 0) first[i] = d;
                    } else {
                        since++;
                    }
                    cpx[i][d] = last;
                    stale[i][d] = since;
                    double v = qvol[i][d];
                    qv[i][d] = v > 0 && c > 0 ? v : 0;
                }
            }
            btc = b;
            pool = new int[days][];
            for (int d = 0; d < days; d++) pool[d] = rankPool(d);
        }

        boolean valid(int i, int d) {
            return d >= 0 && d < days && stale[i][d] == 0;
        }

        /** Listed at least 90 days; coins already trading in the first month of the data are treated as old. */
        boolean oldEnough(int i, int d) {
            return first[i] >= 0 && (d - first[i] >= MIN_AGE || first[i] <= 31);
        }

        private int[] rankPool(int d) {
            List<double[]> c = new ArrayList<>();
            double[] w = new double[30];
            for (int i = 0; i < syms.length; i++) {
                if (!alt[i] || !valid(i, d) || !oldEnough(i, d) || d < 29) continue;
                for (int j = 0; j < 30; j++) w[j] = qv[i][d - j];
                double[] s = w.clone();
                Arrays.sort(s);
                double med = (s[14] + s[15]) / 2;
                if (med > 0) c.add(new double[]{i, med});
            }
            c.sort((x, y) -> Double.compare(y[1], x[1]));
            int k = Math.min(POOL, c.size());
            int[] out = new int[k];
            for (int j = 0; j < k; j++) out[j] = (int) c.get(j)[0];
            return out;
        }

        int[] pool(int d, int n) {
            return pool[d].length <= n ? pool[d] : Arrays.copyOf(pool[d], n);
        }

        boolean sunday(int d) {
            return startDate.plusDays(d).getDayOfWeek() == DayOfWeek.SUNDAY;
        }

        LocalDate date(int d) {
            return startDate.plusDays(d);
        }

        int day(LocalDate x) {
            return (int) (x.toEpochDay() - startDate.toEpochDay());
        }

        /** L-day simple return; NaN unless both ends are recent closes. */
        double ret(int i, int d, int l) {
            if (d - l < 0 || !valid(i, d) || stale[i][d - l] > 3 || first[i] < 0 || first[i] > d - l) return Double.NaN;
            return cpx[i][d] / cpx[i][d - l] - 1;
        }

        /** Std of daily log returns over the last l days (needs 80% real closes). */
        double vol(int i, int d, int l) {
            if (d - l < 0 || first[i] < 0 || first[i] > d - l) return Double.NaN;
            double s = 0, s2 = 0;
            int k = 0;
            for (int j = d - l + 1; j <= d; j++) {
                if (stale[i][j] != 0 || Double.isNaN(cpx[i][j - 1])) continue;
                double r = Math.log(cpx[i][j] / cpx[i][j - 1]);
                s += r;
                s2 += r * r;
                k++;
            }
            if (k < Math.max(5, (int) Math.ceil(0.8 * l))) return Double.NaN;
            double m = s / k;
            double v = Math.sqrt(Math.max(0, s2 / k - m * m) * k / Math.max(1, k - 1));
            return v > 0 ? v : Double.NaN;
        }

        double sma(int i, int d, int l) {
            if (d - l + 1 < 0 || first[i] < 0 || first[i] > d - l + 1) return Double.NaN;
            double s = 0;
            int k = 0;
            for (int j = d - l + 1; j <= d; j++) {
                s += cpx[i][j];
                if (stale[i][j] == 0) k++;
            }
            return k < 0.8 * l ? Double.NaN : s / l;
        }

        double meanQv(int i, int d, int l) {
            if (d - l + 1 < 0 || first[i] < 0 || first[i] > d - l + 1) return Double.NaN;
            double s = 0;
            for (int j = d - l + 1; j <= d; j++) s += qv[i][j];
            return s / l;
        }

        /** Highest / lowest carried close over days [a, b]; NaN unless 90% of them are real closes and listed before a. */
        double[] range(int i, int a, int b) {
            if (a < 0 || first[i] < 0 || first[i] > a) return null;
            double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
            int k = 0;
            for (int j = a; j <= b; j++) {
                hi = Math.max(hi, cpx[i][j]);
                lo = Math.min(lo, cpx[i][j]);
                if (stale[i][j] == 0) k++;
            }
            return k < 0.9 * (b - a + 1) ? null : new double[]{hi, lo};
        }
    }

    static boolean excludedBase(String sym, Set<String> all) {
        String base = sym.substring(0, sym.length() - 4);
        if (base.contains("USD") || Set.of("UST", "USTC", "DAI", "EURC", "SUSD", "GUSD", "USDJ").contains(base)) return true;
        return StockRulesStudy.excludedDerivative(base) || SeasonStudy.leveraged(sym, all);
    }

    // ------------------------------------------------------------------ weekly book

    /** One weekly decision: the scorable candidates, the real pick count and how weights are set. */
    record Choice(int[] cand, double[] score, double[] vol, int k, boolean invVol) {
        int[] top() {
            Integer[] o = new Integer[cand.length];
            for (int j = 0; j < o.length; j++) o[j] = j;
            Arrays.sort(o, (a, b) -> Double.compare(score[b], score[a]));
            int[] out = new int[Math.min(k, o.length)];
            for (int j = 0; j < out.length; j++) out[j] = o[j];
            return out;
        }

        int[] random(SplittableRandom r) {
            int[] idx = IntStream.range(0, cand.length).toArray();
            int m = Math.min(k, idx.length);
            for (int j = 0; j < m; j++) {
                int t = j + r.nextInt(idx.length - j);
                int x = idx[j];
                idx[j] = idx[t];
                idx[t] = x;
            }
            return Arrays.copyOf(idx, m);
        }

        double[] weights(int[] picks) {
            double[] w = new double[picks.length];
            if (picks.length == 0) return w;
            if (!invVol) {
                Arrays.fill(w, 1.0 / picks.length);
                return w;
            }
            for (int j = 0; j < picks.length; j++) w[j] = 1 / vol[picks[j]];
            return capWeights(w, CAP);
        }
    }

    /** Normalise to 1 and cap each weight, redistributing the excess; if every weight is capped the rest stays in cash. */
    static double[] capWeights(double[] raw, double cap) {
        int n = raw.length;
        double[] w = new double[n];
        boolean[] fixed = new boolean[n];
        double left = 1;
        for (int it = 0; it <= n; it++) {
            double s = 0;
            for (int j = 0; j < n; j++) if (!fixed[j]) s += raw[j];
            if (s <= 0) break;
            boolean changed = false;
            for (int j = 0; j < n; j++) {
                if (fixed[j]) continue;
                w[j] = left * raw[j] / s;
            }
            for (int j = 0; j < n; j++) {
                if (!fixed[j] && w[j] > cap + 1e-12) {
                    w[j] = cap;
                    fixed[j] = true;
                    changed = true;
                }
            }
            if (!changed) break;
            left = 1;
            for (int j = 0; j < n; j++) if (fixed[j]) left -= w[j];
            if (left <= 1e-12) {
                for (int j = 0; j < n; j++) if (!fixed[j]) w[j] = 0;
                break;
            }
        }
        return w;
    }

    record Curve(double[] nav, int opens, double turnover, double avgHold, double[] expo, int[] opensDay) {
        Curve(double[] nav, int opens, double turnover, double avgHold) {
            this(nav, opens, turnover, avgHold, null, null);
        }
    }

    /**
     * Weekly rebalanced book with {@code hold} overlapping sleeves (sleeve j trades on weeks where week % hold == j).
     * nav[k] = value at the close of day from + k, after that day's trades and costs; start value 1.
     */
    static Curve runWeekly(Panel p, int from, int to, int hold, Map<Integer, Choice> choices, SplittableRandom rnd) {
        int n = p.syms.length;
        double[][] units = new double[hold][n];
        int[][] since = new int[hold][n];
        double[] cash = new double[hold];
        Arrays.fill(cash, 1.0 / hold);
        double[] nav = new double[to - from + 1];
        double[] expo = new double[nav.length];
        int[] opensDay = new int[nav.length];
        int opens = 0;
        double turnover = 0, holdDays = 0;
        int closes = 0;
        for (int d = from; d <= to; d++) {
            for (int s = 0; s < hold; s++) {
                for (int i = 0; i < n; i++) {
                    if (units[s][i] > 0 && p.stale[i][d] >= DELIST_DAYS) {
                        cash[s] += units[s][i] * p.cpx[i][d] * 0.995 * (1 - COST);
                        units[s][i] = 0;
                        holdDays += d - since[s][i];
                        closes++;
                    }
                }
            }
            Choice c = choices.get(d);
            if (c != null && d < to) {
                int s = ((d - from) / 7) % hold;
                int[] picks = rnd == null ? c.top() : c.random(rnd);
                double[] w = c.weights(picks);
                double value = cash[s];
                for (int i = 0; i < n; i++) if (units[s][i] > 0) value += units[s][i] * p.cpx[i][d];
                double[] tv = new double[n];
                for (int j = 0; j < picks.length; j++) tv[c.cand()[picks[j]]] += w[j] * value;
                double traded = 0;
                for (int i = 0; i < n; i++) traded += Math.abs(tv[i] - (units[s][i] > 0 ? units[s][i] * p.cpx[i][d] : 0));
                double cost = traded * COST;
                double scale = value > 0 ? (value - cost) / value : 0;
                double invested = 0;
                for (int i = 0; i < n; i++) {
                    double v = tv[i] * scale;
                    if (v > 0 && units[s][i] == 0) {
                        opens++;
                        opensDay[d - from]++;
                        since[s][i] = d;
                    }
                    if (v == 0 && units[s][i] > 0) {
                        holdDays += d - since[s][i];
                        closes++;
                    }
                    units[s][i] = v > 0 ? v / p.cpx[i][d] : 0;
                    invested += v;
                }
                cash[s] = value - cost - invested;
                turnover += traded;
            }
            double v = 0, inv = 0;
            for (int s = 0; s < hold; s++) {
                v += cash[s];
                for (int i = 0; i < n; i++) if (units[s][i] > 0) inv += units[s][i] * p.cpx[i][d];
            }
            v += inv;
            nav[d - from] = v;
            expo[d - from] = v > 0 ? inv / v : 0;
        }
        return new Curve(nav, opens, turnover, closes == 0 ? Double.NaN : holdDays / closes, expo, opensDay);
    }

    // ------------------------------------------------------------------ strategy choices

    static Map<Integer, Choice> rotation(Panel p, int from, int to, int l, boolean btcFilter) {
        Map<Integer, Choice> m = new TreeMap<>();
        for (int d = from; d < to; d++) {
            if (!p.sunday(d)) continue;
            int[] pool = p.pool(d, POOL);
            List<Integer> ci = new ArrayList<>();
            List<Double> sc = new ArrayList<>(), vo = new ArrayList<>();
            for (int i : pool) {
                double r = p.ret(i, d, l), v = p.vol(i, d, l);
                if (Double.isNaN(r) || Double.isNaN(v)) continue;
                ci.add(i);
                sc.add(r / v);
                vo.add(v);
            }
            int k = 5;
            if (btcFilter) {
                double ma = p.btc < 0 ? Double.NaN : p.sma(p.btc, d, 200);
                // missing BTC data or average = no new risk (cash)
                if (Double.isNaN(ma) || !p.valid(p.btc, d) || p.cpx[p.btc][d] < ma) k = 0;
            }
            m.put(d, new Choice(ints(ci), dbls(sc), dbls(vo), k, true));
        }
        return m;
    }

    static final int[] MAS = {5, 10, 20, 50, 100, 200};

    static Map<Integer, Choice> ctrend(Panel p, int from, int to) {
        Map<Integer, Choice> m = new TreeMap<>();
        for (int d = from; d < to; d++) {
            if (!p.sunday(d)) continue;
            int[] pool = p.pool(d, POOL);
            double[][] f = new double[MAS.length + 1][pool.length];
            for (int j = 0; j < pool.length; j++) {
                int i = pool[j];
                for (int a = 0; a < MAS.length; a++) {
                    double ma = p.sma(i, d, MAS[a]);
                    f[a][j] = Double.isNaN(ma) ? Double.NaN : p.cpx[i][d] / ma;
                }
                double v20 = p.meanQv(i, d, 20), v100 = p.meanQv(i, d, 100);
                f[MAS.length][j] = v100 > 0 && !Double.isNaN(v20) ? v20 / v100 : Double.NaN;
            }
            double[][] z = new double[f.length][];
            for (int a = 0; a < f.length; a++) z[a] = zscore(f[a]);
            List<Integer> ci = new ArrayList<>();
            List<Double> sc = new ArrayList<>();
            for (int j = 0; j < pool.length; j++) {
                double s = 0;
                int k = 0;
                for (int a = 0; a < MAS.length; a++) {
                    if (Double.isNaN(z[a][j])) continue;
                    s += z[a][j];
                    k++;
                }
                if (k < 4) continue;
                double zv = z[MAS.length][j];
                ci.add(pool[j]);
                sc.add(s / k + (Double.isNaN(zv) ? 0 : zv));
            }
            int k = (int) Math.round(ci.size() / 5.0);
            m.put(d, new Choice(ints(ci), dbls(sc), null, Math.max(k, ci.isEmpty() ? 0 : 1), false));
        }
        return m;
    }

    static Map<Integer, Choice> reversal(Panel p, int from, int to) {
        Map<Integer, Choice> m = new TreeMap<>();
        for (int d = from; d < to; d++) {
            if (!p.sunday(d)) continue;
            List<Integer> ci = new ArrayList<>();
            List<Double> sc = new ArrayList<>();
            for (int i : p.pool(d, POOL)) {
                double r = p.ret(i, d, 7);
                if (Double.isNaN(r)) continue;
                ci.add(i);
                sc.add(-r);
            }
            m.put(d, new Choice(ints(ci), dbls(sc), null, 5, false));
        }
        return m;
    }

    /** Equal weight of the whole pool (benchmark), rebalanced weekly. */
    static Map<Integer, Choice> equalPool(Panel p, int from, int to, int size) {
        Map<Integer, Choice> m = new TreeMap<>();
        for (int d = from; d < to; d++) {
            if (!p.sunday(d)) continue;
            int[] pool = p.pool(d, size);
            m.put(d, new Choice(pool.clone(), new double[pool.length], null, pool.length, false));
        }
        return m;
    }

    static double[] zscore(double[] x) {
        double s = 0, s2 = 0;
        int k = 0;
        for (double v : x) {
            if (Double.isNaN(v)) continue;
            s += v;
            s2 += v * v;
            k++;
        }
        double[] z = new double[x.length];
        Arrays.fill(z, Double.NaN);
        if (k < 3) return z;
        double m = s / k, sd = Math.sqrt(Math.max(0, s2 / k - m * m));
        if (sd <= 0) return z;
        for (int j = 0; j < x.length; j++) if (!Double.isNaN(x[j])) z[j] = Math.max(-3, Math.min(3, (x[j] - m) / sd));
        return z;
    }

    // ------------------------------------------------------------------ Donchian

    record DTrade(int sleeve, int sym, int entry, int exit, double frac) {
    }

    record DResult(Curve path, List<DTrade> trades) {
    }

    static final int VOL_DAYS = 30;
    static final double VOL_TARGET = 0.25;

    static DResult runDonchian(Panel p, int from, int to, int[] lookbacks) {
        int n = p.syms.length, sl = lookbacks.length;
        double[][] units = new double[sl][n], stop = new double[sl][n], fracs = new double[sl][n];
        int[][] entry = new int[sl][n];
        double[] cash = new double[sl];
        Arrays.fill(cash, 1.0 / sl);
        double[] nav = new double[to - from + 1];
        List<DTrade> trades = new ArrayList<>();
        double turnover = 0;
        double[] expo = new double[nav.length];
        int[] opensDay = new int[nav.length];
        for (int d = from; d <= to; d++) {
            for (int s = 0; s < sl; s++) {
                int lb = lookbacks[s];
                for (int i = 0; i < n; i++) {
                    if (units[s][i] <= 0) continue;
                    boolean delist = p.stale[i][d] >= DELIST_DAYS;
                    boolean hit = p.valid(i, d) && p.cpx[i][d] < stop[s][i];
                    if (delist || hit) {
                        double v = units[s][i] * p.cpx[i][d] * (delist ? 0.995 : 1);
                        cash[s] += v * (1 - COST);
                        turnover += v;
                        units[s][i] = 0;
                        trades.add(new DTrade(s, i, entry[s][i], d, fracs[s][i]));
                    } else if (p.valid(i, d)) {
                        double[] r = p.range(i, d - lb + 1, d);
                        if (r != null) stop[s][i] = Math.max(stop[s][i], (r[0] + r[1]) / 2);
                    }
                }
                if (d >= to) continue;
                double sv = cash[s];
                for (int i = 0; i < n; i++) if (units[s][i] > 0) sv += units[s][i] * p.cpx[i][d];
                List<double[]> cands = new ArrayList<>();
                for (int i : p.pool(d, DON_POOL)) {
                    if (units[s][i] > 0 || !p.valid(i, d)) continue;
                    double[] prior = p.range(i, d - lb, d - 1);
                    if (prior == null || !(p.cpx[i][d] > prior[0])) continue;
                    double v = p.vol(i, d, VOL_DAYS);
                    if (Double.isNaN(v)) continue;
                    cands.add(new double[]{i, p.cpx[i][d] / prior[0], v * Math.sqrt(365)});
                }
                cands.sort((a, b) -> Double.compare(b[1], a[1]));
                for (double[] c : cands) {
                    int i = (int) c[0];
                    double v = Math.min(VOL_TARGET / c[2] * sv, cash[s] / (1 + COST));
                    if (v < 0.005 * sv) continue;
                    cash[s] -= v * (1 + COST);
                    turnover += v;
                    units[s][i] = v / p.cpx[i][d];
                    double[] r = p.range(i, d - lb + 1, d);
                    stop[s][i] = r == null ? 0 : (r[0] + r[1]) / 2;
                    entry[s][i] = d;
                    fracs[s][i] = v / sv;
                    opensDay[d - from]++;
                }
            }
            nav[d - from] = navOf(p, d, cash, units);
            double c = 0;
            for (double x : cash) c += x;
            expo[d - from] = nav[d - from] > 0 ? 1 - c / nav[d - from] : 0;
        }
        for (int s = 0; s < sl; s++) for (int i = 0; i < n; i++) if (units[s][i] > 0) trades.add(new DTrade(s, i, entry[s][i], -1, fracs[s][i]));
        Curve pa = path(nav, trades, turnover);
        return new DResult(new Curve(nav, pa.opens(), turnover, pa.avgHold(), expo, opensDay), trades);
    }

    /** Random twin: each real trade's day, sleeve, size (share of sleeve value) and holding time on a random pool coin. */
    static Curve randomDonchian(Panel p, int from, int to, int sleeves, List<DTrade> real, SplittableRandom rnd) {
        int n = p.syms.length;
        Map<Integer, List<DTrade>> byEntry = new TreeMap<>();
        for (DTrade t : real) byEntry.computeIfAbsent(t.entry(), k -> new ArrayList<>()).add(t);
        double[][] units = new double[sleeves][n];
        int[][] exit = new int[sleeves][n];
        double[] cash = new double[sleeves];
        Arrays.fill(cash, 1.0 / sleeves);
        double[] nav = new double[to - from + 1];
        List<DTrade> done = new ArrayList<>();
        int[][] entry = new int[sleeves][n];
        for (int d = from; d <= to; d++) {
            for (int s = 0; s < sleeves; s++) {
                for (int i = 0; i < n; i++) {
                    if (units[s][i] <= 0) continue;
                    boolean delist = p.stale[i][d] >= DELIST_DAYS;
                    if (delist || exit[s][i] == d) {
                        cash[s] += units[s][i] * p.cpx[i][d] * (delist ? 0.995 : 1) * (1 - COST);
                        units[s][i] = 0;
                        done.add(new DTrade(s, i, entry[s][i], d, 0));
                    }
                }
            }
            List<DTrade> today = byEntry.get(d);
            if (today != null && d < to) {
                for (DTrade t : today) {
                    int s = t.sleeve();
                    double sv = cash[s];
                    for (int i = 0; i < n; i++) if (units[s][i] > 0) sv += units[s][i] * p.cpx[i][d];
                    List<Integer> c = new ArrayList<>();
                    for (int i : p.pool(d, DON_POOL)) if (units[s][i] <= 0 && p.valid(i, d)) c.add(i);
                    if (c.isEmpty()) continue;
                    int i = c.get(rnd.nextInt(c.size()));
                    double v = Math.min(t.frac() * sv, cash[s] / (1 + COST));
                    if (v <= 0) continue;
                    cash[s] -= v * (1 + COST);
                    units[s][i] = v / p.cpx[i][d];
                    exit[s][i] = t.exit();
                    entry[s][i] = d;
                }
            }
            nav[d - from] = navOf(p, d, cash, units);
        }
        return path(nav, done, Double.NaN);
    }

    private static Curve path(double[] nav, List<DTrade> trades, double turnover) {
        double h = 0;
        int k = 0;
        for (DTrade t : trades) {
            if (t.exit() < 0) continue;
            h += t.exit() - t.entry();
            k++;
        }
        return new Curve(nav, trades.size(), turnover, k == 0 ? Double.NaN : h / k);
    }

    private static double navOf(Panel p, int d, double[] cash, double[][] units) {
        double v = 0;
        for (int s = 0; s < cash.length; s++) {
            v += cash[s];
            for (int i = 0; i < units[s].length; i++) if (units[s][i] > 0) v += units[s][i] * p.cpx[i][d];
        }
        return v;
    }

    static double[] btcHold(Panel p, int from, int to) {
        double[] nav = new double[to - from + 1];
        double u = (1 - COST) / p.cpx[p.btc][from];
        for (int d = from; d <= to; d++) nav[d - from] = u * p.cpx[p.btc][d];
        return nav;
    }

    // ------------------------------------------------------------------ statistics

    /** Weekly returns between consecutive Sundays (index into nav = day - from). */
    static double[] weekly(Panel p, int from, int a, int b, double[] nav) {
        List<Double> r = new ArrayList<>();
        int prev = -1;
        for (int d = a; d <= b; d++) {
            if (!p.sunday(d)) continue;
            if (prev >= 0) r.add(nav[d - from] / nav[prev - from] - 1);
            prev = d;
        }
        return r.stream().mapToDouble(Double::doubleValue).toArray();
    }

    static double mean(double[] x) {
        double s = 0;
        for (double v : x) s += v;
        return x.length == 0 ? Double.NaN : s / x.length;
    }

    static double sd(double[] x) {
        double m = mean(x), s = 0;
        for (double v : x) s += (v - m) * (v - m);
        return x.length < 2 ? Double.NaN : Math.sqrt(s / (x.length - 1));
    }

    /** Circular block bootstrap 95% interval of the mean (block = 4 weeks). */
    static double[] blockCi(double[] x, int block, int reps, long seed) {
        int n = x.length;
        if (n < block * 2) return new double[]{Double.NaN, Double.NaN};
        SplittableRandom r = new SplittableRandom(seed);
        double[] m = new double[reps];
        for (int k = 0; k < reps; k++) {
            double s = 0;
            int c = 0;
            while (c < n) {
                int st = r.nextInt(n);
                for (int j = 0; j < block && c < n; j++, c++) s += x[(st + j) % n];
            }
            m[k] = s / n;
        }
        Arrays.sort(m);
        return new double[]{m[(int) (0.025 * reps)], m[(int) (0.975 * reps) - 1]};
    }

    static double maxDd(double[] nav, int a, int b) {
        double peak = nav[a], dd = 0;
        for (int k = a; k <= b; k++) {
            peak = Math.max(peak, nav[k]);
            dd = Math.max(dd, 1 - nav[k] / peak);
        }
        return dd;
    }

    /** Standard normal upper tail. */
    static double upper(double z) {
        return 0.5 * erfc(z / Math.sqrt(2));
    }

    static double erfc(double x) {
        double t = 1 / (1 + 0.5 * Math.abs(x));
        double y = t * Math.exp(-x * x - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418 + t * (-0.18628806
                + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? y : 2 - y;
    }

    static double percentile(double[] sorted, double q) {
        if (sorted.length == 0) return Double.NaN;
        return sorted[Math.min(sorted.length - 1, Math.max(0, (int) Math.floor(q * sorted.length)))];
    }

    // ------------------------------------------------------------------ regimes (daily approximation of Regimes)

    /** Daily six-regime label: crash day = pool median 1-day return ≤ −8%; rebound = the next 6 days; else Regimes order. */
    static int[] regimes(Panel p) {
        int[] out = new int[p.days];
        int lastCrash = -1000;
        for (int d = 8; d < p.days; d++) {
            int[] pool = p.pool(d, POOL);
            final int dd = d;
            double a1 = median(pool, i -> p.ret(i, dd, 1)), a7 = median(pool, i -> p.ret(i, dd, 7));
            double b7 = p.btc < 0 ? Double.NaN : p.ret(p.btc, d, 7);
            if (Double.isNaN(a1) || Double.isNaN(a7) || Double.isNaN(b7)) continue;
            a1 *= 100;
            a7 *= 100;
            b7 *= 100;
            if (a1 <= -8) lastCrash = d;
            int since = d - lastCrash;
            int r;
            double excess = a7 - b7;
            if (since < 1) r = 5;
            else if (since < 7) r = 6;
            else if (a1 <= -3) r = 4;
            else if (excess >= 5) r = 2;
            else if (b7 >= 5 && a7 > 0) r = 1;
            else if (Math.abs(b7) <= 3 && Math.abs(a7) <= 5) r = 3;
            else if (excess >= 2.5) r = 2;
            else if (b7 >= 2.5) r = 1;
            else if (a1 <= -1.5 || a7 <= -5) r = 4;
            else r = 3;
            out[d] = r;
        }
        return out;
    }

    private static double median(int[] pool, IntFunction<Double> f) {
        double[] v = Arrays.stream(pool).mapToDouble(i -> f.apply(i)).filter(x -> !Double.isNaN(x)).sorted().toArray();
        if (v.length < 5) return Double.NaN;
        return v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
    }

    // ------------------------------------------------------------------ study

    record Spec(String family, String name, String key) {
    }

    record Row(Spec spec, Curve real, double[] randMeans, double[] randFullU, double[] randValMeans, double[] randAvgWeekly,
               double meanW, double[] ci, double[] exCi, double pct, double valPct, double z, double p, double fullU, double valU,
               double trainMeanW, double dd, double valMeanW, int weeks, double[][] randSeg) {
    }

    /** Three alt rallies the user cares about (start, end inclusive). */
    static final LocalDate[][] SEASONS = {
            {LocalDate.of(2024, 2, 15), LocalDate.of(2024, 4, 15)},
            {LocalDate.of(2024, 10, 15), LocalDate.of(2024, 12, 31)},
            {LocalDate.of(2025, 4, 15), LocalDate.of(2025, 6, 15)}};
    static final int GIVEBACK_DAYS = 60;

    /** Segment U on START_U from the close before start to the close of end; NaN when outside the run. */
    static double segU(Run run, double[] nav, LocalDate a, LocalDate b) {
        int i = run.p.day(a) - 1 - run.from, j = run.p.day(b) - run.from;
        if (i < 0 || j >= nav.length) return Double.NaN;
        return START_U * (nav[j] / nav[i] - 1);
    }

    static final class Run {
        final Panel p;
        final String label;
        final int from, to, split;
        final int[] regime;
        final List<Row> rows = new ArrayList<>();
        final Map<String, double[]> bench = new LinkedHashMap<>();

        Run(Panel p, String label, LocalDate tradeFrom, LocalDate last) {
            this.p = p;
            this.label = label;
            int f = Math.max(0, p.day(tradeFrom));
            while (!p.sunday(f)) f++;
            from = f;
            to = Math.min(p.days - 1, p.day(last));
            int s = from + (int) Math.round(TRAIN_SHARE * (to - from));
            while (!p.sunday(s)) s++;
            split = s;
            regime = regimes(p);
        }
    }

    static List<Spec> specs() {
        List<Spec> s = new ArrayList<>();
        for (int l : new int[]{21, 14, 28}) s.add(new Spec("轮动", "相对强弱 " + l + " 日", "rot" + l));
        for (int l : new int[]{21, 14, 28}) s.add(new Spec("轮动", "相对强弱 " + l + " 日 + BTC 200 日线过滤", "rot" + l + "f"));
        s.add(new Spec("Donchian", "Donchian 组合（20/60/120/250 各 1/4）", "donE"));
        for (int l : new int[]{20, 60, 120, 250}) s.add(new Spec("Donchian", "Donchian " + l + " 日", "don" + l));
        s.add(new Spec("CTREND", "CTREND-lite 持有 1 周", "ct1"));
        s.add(new Spec("CTREND", "CTREND-lite 持有 2 周", "ct2"));
        s.add(new Spec("反转", "周反转（7 日跌最多 5 个）", "rev"));
        return s;
    }

    static void study(Run run, int threads) throws Exception {
        Panel p = run.p;
        int from = run.from, to = run.to;
        run.bench.put("BTC 持有", btcHold(p, from, to));
        run.bench.put("前 40 等权持有（每周再平衡）", runWeekly(p, from, to, 1, equalPool(p, from, to, POOL), null).nav());
        run.bench.put("前 20 等权持有（每周再平衡）", runWeekly(p, from, to, 1, equalPool(p, from, to, DON_POOL), null).nav());
        List<Spec> specs = specs();
        try (ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads))) {
            List<Future<Row>> fs = new ArrayList<>();
            long seed = 20261105;
            for (Spec s : specs) {
                long sd = seed++;
                fs.add(pool.submit(() -> evaluate(run, s, sd)));
            }
            for (Future<Row> f : fs) {
                Row r = f.get();
                run.rows.add(r);
                System.out.printf(Locale.ROOT, "%s %s: fullU %.0f valU %.0f pct %.3f%n", run.label, r.spec().name(), r.fullU(), r.valU(), r.pct());
            }
        }
    }

    static Row evaluate(Run run, Spec s, long seed) {
        Panel p = run.p;
        int from = run.from, to = run.to;
        Curve real;
        IntFunction<Curve> twin;
        String k = s.key();
        if (k.startsWith("don")) {
            int[] lbs = k.equals("donE") ? new int[]{20, 60, 120, 250} : new int[]{Integer.parseInt(k.substring(3))};
            DResult dr = runDonchian(p, from, to, lbs);
            real = dr.path();
            twin = j -> randomDonchian(p, from, to, lbs.length, dr.trades(), new SplittableRandom(seed * 7919 + j));
        } else {
            Map<Integer, Choice> ch;
            int hold = 1;
            if (k.startsWith("rot")) {
                boolean f = k.endsWith("f");
                int l = Integer.parseInt(k.substring(3, f ? k.length() - 1 : k.length()));
                ch = rotation(p, from, to, l, f);
            } else if (k.startsWith("ct")) {
                ch = ctrend(p, from, to);
                hold = Integer.parseInt(k.substring(2));
            } else {
                ch = reversal(p, from, to);
            }
            Map<Integer, Choice> c = ch;
            int h = hold;
            real = runWeekly(p, from, to, hold, c, null);
            twin = j -> runWeekly(p, from, to, h, c, new SplittableRandom(seed * 7919 + j));
        }
        double[] w = weekly(p, from, from, to, real.nav());
        double[] wv = weekly(p, from, run.split, to, real.nav());
        double[] wt = weekly(p, from, from, run.split, real.nav());
        double[] rm = new double[RANDOMS], rf = new double[RANDOMS], rv = new double[RANDOMS];
        double[][] rs = new double[SEASONS.length][RANDOMS];
        double[] avg = new double[w.length];
        for (int j = 0; j < RANDOMS; j++) {
            Curve t = twin.apply(j);
            double[] tw = weekly(p, from, from, to, t.nav());
            for (int q = 0; q < avg.length; q++) avg[q] += tw[q] / RANDOMS;
            rm[j] = mean(tw);
            rv[j] = mean(weekly(p, from, run.split, to, t.nav()));
            rf[j] = START_U * (t.nav()[to - from] / t.nav()[0] - 1);
            for (int q = 0; q < SEASONS.length; q++) rs[q][j] = segU(run, t.nav(), SEASONS[q][0], SEASONS[q][1]);
        }
        for (double[] x : rs) Arrays.sort(x);
        double m = mean(w);
        double pct = Arrays.stream(rm).filter(x -> x < m).count() / (double) RANDOMS;
        double mv = mean(wv);
        double valPct = Arrays.stream(rv).filter(x -> x < mv).count() / (double) RANDOMS;
        double z = (m - mean(rm)) / sd(rm);
        double[] ex = new double[w.length];
        for (int q = 0; q < w.length; q++) ex[q] = w[q] - avg[q];
        double[] nav = real.nav();
        double fullU = START_U * (nav[to - from] / nav[0] - 1);
        double valU = START_U * (nav[to - from] / nav[run.split - from] - 1);
        Arrays.sort(rm);
        Arrays.sort(rf);
        return new Row(s, real, rm, rf, rv, avg, m, blockCi(w, 4, 5000, seed), blockCi(ex, 4, 5000, seed + 1), pct, valPct, z, upper(z),
                fullU, valU, mean(wt), maxDd(nav, 0, to - from), mv, w.length, rs);
    }

    // ------------------------------------------------------------------ report

    static String pc(double x) {
        return Double.isNaN(x) ? "—" : String.format(Locale.ROOT, "%+.2f%%", 100 * x);
    }

    static String u(double x) {
        return Double.isNaN(x) ? "—" : String.format(Locale.ROOT, "%+,.0f", x);
    }

    static boolean passes(Run run, Row r) {
        double benchDd = maxDd(benchFor(run, r), 0, run.to - run.from);
        return r.pct() > 0.95 && r.fullU() > 0 && r.dd() <= benchDd * 1.1 && r.valU() > 0;
    }

    static double[] benchFor(Run run, Row r) {
        return run.bench.get(r.spec().family().equals("Donchian") ? "前 20 等权持有（每周再平衡）" : "前 40 等权持有（每周再平衡）");
    }

    static String report(List<Run> runs, int trials) {
        StringBuilder b = new StringBuilder();
        for (Run run : runs) {
            Panel p = run.p;
            int from = run.from, to = run.to;
            b.append("\n## ").append(run.label).append("：").append(p.date(from)).append(" ～ ").append(p.date(to))
                    .append("（验证期 ").append(p.date(run.split)).append(" 起，交易对 ").append(p.syms.length).append(" 个）\n\n");
            b.append("### 总表（2,000U 起始，扣费后）\n\n");
            b.append("| 策略 | 全期 U | 净值最大回撤 | 验证期 U | 周均收益 [95% 区间] | 随机分位（全期 / 验证期） | 随机 95 分位周均 | 比随机多的周均 [95% 区间] | z / Bonferroni p | 笔数 / 平均持有天 | 通过 |\n");
            b.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
            for (Row r : run.rows) {
                double bonf = Math.min(1, r.p() * trials);
                b.append(String.format(Locale.ROOT, "| %s | %s | %.1f%% | %s | %s [%s, %s] | %.1f%% / %.1f%% | %s | %s [%s, %s] | %.2f / %s | %d / %.1f | %s |%n",
                        r.spec().name(), u(r.fullU()), 100 * r.dd(), u(r.valU()), pc(r.meanW()), pc(r.ci()[0]), pc(r.ci()[1]),
                        100 * r.pct(), 100 * r.valPct(), pc(percentile(r.randMeans(), 0.95)),
                        pc(mean(diff(weekly(p, from, from, to, r.real().nav()), r.randAvgWeekly()))), pc(r.exCi()[0]), pc(r.exCi()[1]),
                        r.z(), bonf < 0.001 ? String.format(Locale.ROOT, "%.1e", bonf) : String.format(Locale.ROOT, "%.3f", bonf),
                        r.real().opens(), r.real().avgHold(), passes(run, r) ? "**通过**" : "不通过"));
            }
            b.append("\n基准：\n\n| 基准 | 全期 U | 净值最大回撤 | 验证期 U | 周均收益 [95% 区间] |\n|---|---:|---:|---:|---:|\n");
            for (Map.Entry<String, double[]> e : run.bench.entrySet()) {
                double[] nav = e.getValue();
                double[] w = weekly(p, from, from, to, nav);
                double[] ci = blockCi(w, 4, 5000, 1);
                b.append(String.format(Locale.ROOT, "| %s | %s | %.1f%% | %s | %s [%s, %s] |%n", e.getKey(), u(START_U * (nav[to - from] / nav[0] - 1)),
                        100 * maxDd(nav, 0, to - from), u(START_U * (nav[to - from] / nav[run.split - from] - 1)), pc(mean(w)), pc(ci[0]), pc(ci[1])));
            }
            b.append("\n随机组合（每个策略 1,000 个）的全期 U：\n\n| 策略 | 随机中位数 | 随机 5 分位 | 随机 95 分位 |\n|---|---:|---:|---:|\n");
            for (Row r : run.rows) {
                b.append(String.format(Locale.ROOT, "| %s | %s | %s | %s |%n", r.spec().name(), u(percentile(r.randFullU(), 0.5)),
                        u(percentile(r.randFullU(), 0.05)), u(percentile(r.randFullU(), 0.95))));
            }
            // OOS selection
            b.append("\n### 样本外：前 60% 选参数，后 40% 验证\n\n| 族 | 训练期选中（训练期周均最高） | 训练期周均 | 验证期 U | 验证期周均 | 验证期随机分位 |\n|---|---|---:|---:|---:|---:|\n");
            Map<String, Row> best = new LinkedHashMap<>();
            for (Row r : run.rows) best.merge(r.spec().family(), r, (a, c) -> c.trainMeanW() > a.trainMeanW() ? c : a);
            for (Row r : best.values()) {
                b.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %.1f%% |%n", r.spec().family(), r.spec().name(), pc(r.trainMeanW()),
                        u(r.valU()), pc(r.valMeanW()), 100 * r.valPct()));
            }
            b.append(seasons(run));
            // regimes
            b.append("\n### 六种行情（全期权益变化 U，2,000U 起始；天数）\n\n| 策略 |");
            for (int g = 1; g <= 6; g++) b.append(' ').append(Regimes.NAMES[g]).append(" |");
            b.append("\n|---|");
            for (int g = 1; g <= 6; g++) b.append("---:|");
            b.append("\n| 天数 |");
            for (int g = 1; g <= 6; g++) {
                int c = 0;
                for (int d = from + 1; d <= to; d++) if (run.regime[d] == g) c++;
                b.append(' ').append(c).append(" |");
            }
            b.append('\n');
            List<String> names = new ArrayList<>();
            List<double[]> navs = new ArrayList<>();
            for (Row r : run.rows) {
                names.add(r.spec().name());
                navs.add(r.real().nav());
            }
            run.bench.forEach((k, v) -> {
                names.add(k);
                navs.add(v);
            });
            for (int j = 0; j < names.size(); j++) {
                double[] nav = navs.get(j);
                double[] g = new double[7];
                for (int d = from + 1; d <= to; d++) g[run.regime[d]] += START_U * (nav[d - from] - nav[d - 1 - from]) / nav[0];
                b.append("| ").append(names.get(j)).append(" |");
                for (int q = 1; q <= 6; q++) b.append(' ').append(u(g[q])).append(" |");
                b.append('\n');
            }
            // years
            b.append("\n### 按年（当年收益率 / 当年净值最大回撤）\n\n| 策略 |");
            List<int[]> years = new ArrayList<>();
            for (int y = p.date(from).getYear(); y <= p.date(to).getYear(); y++) {
                int a = Math.max(from, p.day(LocalDate.of(y, 1, 1)) - 1), c = Math.min(to, p.day(LocalDate.of(y, 12, 31)));
                years.add(new int[]{y, a, c});
                b.append(' ').append(y).append(" |");
            }
            b.append("\n|---|");
            years.forEach(y -> b.append("---:|"));
            b.append('\n');
            for (int j = 0; j < names.size(); j++) {
                double[] nav = navs.get(j);
                b.append("| ").append(names.get(j)).append(" |");
                for (int[] y : years) {
                    b.append(String.format(Locale.ROOT, " %s / %.0f%% |", pc(nav[y[2] - from] / nav[y[1] - from] - 1), 100 * maxDd(nav, y[1] - from, y[2] - from)));
                }
                b.append('\n');
            }
        }
        return b.toString();
    }

    static String seasons(Run run) {
        Panel p = run.p;
        StringBuilder b = new StringBuilder("\n### 三段山寨季（2,000U 起始；括号里是 10,000U 起始，按同一收益率线性换算、未加大资金的冲击成本）\n\n");
        for (LocalDate[] s : SEASONS) {
            int a = p.day(s[0]), e = p.day(s[1]);
            if (a - 1 < run.from || e > run.to) {
                b.append("- ").append(s[0]).append(" ～ ").append(s[1]).append("：不在本数据回测区间内\n\n");
                continue;
            }
            double btcU = segU(run, run.bench.get("BTC 持有"), s[0], s[1]);
            double ew40 = segU(run, run.bench.get("前 40 等权持有（每周再平衡）"), s[0], s[1]);
            double ew20 = segU(run, run.bench.get("前 20 等权持有（每周再平衡）"), s[0], s[1]);
            b.append(String.format(Locale.ROOT, "**%s ～ %s**：同期 BTC %s（%s U），前 40 等权持有 %s（%s U），前 20 等权持有 %s（%s U）。%n%n",
                    s[0], s[1], pc(btcU / START_U), u(btcU), pc(ew40 / START_U), u(ew40), pc(ew20 / START_U), u(ew20)));
            b.append("| 策略 | 这一段 U（10,000U） | 收益率 | 随机中位数 U | 开始前一天仓位 | 本段首次新买入在第几天 | 结束后 60 天变化 U | 60 天内最深回吐 U |\n");
            b.append("|---|---:|---:|---:|---:|---:|---:|---:|\n");
            int q = Arrays.asList(SEASONS).indexOf(s);
            for (Row r : run.rows) {
                double[] nav = r.real().nav();
                double su = segU(run, nav, s[0], s[1]);
                int lag = -1;
                for (int d = a; d <= e; d++) if (r.real().opensDay()[d - run.from] > 0) {
                    lag = d - a;
                    break;
                }
                double held = r.real().expo()[a - 1 - run.from];
                String give = "—", worst = "—";
                int g = e + GIVEBACK_DAYS;
                if (g <= run.to) {
                    double base = nav[a - 1 - run.from];
                    double ch = START_U * (nav[g - run.from] - nav[e - run.from]) / base;
                    double lo = Double.POSITIVE_INFINITY;
                    for (int d = e; d <= g; d++) lo = Math.min(lo, nav[d - run.from]);
                    double w = START_U * (lo - nav[e - run.from]) / base;
                    give = u(ch) + (su > 0 && ch < 0 ? String.format(Locale.ROOT, "（吐回本段利润 %.0f%%）", -100 * ch / su) : "");
                    worst = u(w);
                }
                b.append(String.format(Locale.ROOT, "| %s | %s（%s） | %s | %s | %.0f%% | %s | %s | %s |%n", r.spec().name(), u(su), u(su * 5),
                        pc(su / START_U), u(percentile(r.randSeg()[q], 0.5)), 100 * held, lag < 0 ? "这段没新买" : lag + " 天", give, worst));
            }
            b.append('\n');
        }
        return b.toString();
    }

    static double[] diff(double[] a, double[] b) {
        double[] o = new double[a.length];
        for (int j = 0; j < a.length; j++) o[j] = a[j] - b[j];
        return o;
    }

    static String csv(List<Run> runs, int trials) {
        StringBuilder b = new StringBuilder("data,strategy,fullU,maxDD,valU,meanWeekly,ciLo,ciHi,randPct,valRandPct,rand95,z,pOne,bonferroni,opens,avgHold,pass\n");
        for (Run run : runs) {
            for (Row r : run.rows) {
                b.append(String.format(Locale.ROOT, "%s,%s,%.0f,%.4f,%.0f,%.5f,%.5f,%.5f,%.3f,%.3f,%.5f,%.2f,%.2e,%.2e,%d,%.1f,%s%n", run.label,
                        r.spec().key(), r.fullU(), r.dd(), r.valU(), r.meanW(), r.ci()[0], r.ci()[1], r.pct(), r.valPct(),
                        percentile(r.randMeans(), 0.95), r.z(), r.p(), Math.min(1, r.p() * trials), r.real().opens(), r.real().avgHold(),
                        passes(run, r)));
            }
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ loading

    static Panel binance(java.nio.file.Path dir, YearMonth from, YearMonth to, Set<String> excluded, StrategyParams params, int threads) throws Exception {
        Archive a = new Archive(dir, "1d");
        List<String> symbols = new ArrayList<>();
        for (String s : a.usdtSymbols()) if (!com.trendpilot.universe.UniverseFilter.excludedOffline(params.universe(), s)) symbols.add(s);
        else excluded.add(s);
        System.out.println("Binance USDT 交易对（含已下架）" + symbols.size() + " 个");
        // one bucket listing per symbol, then only the monthly packages that exist (fewer requests than probing every month)
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> fs = new ArrayList<>();
            for (String s : symbols) {
                fs.add(pool.submit(() -> {
                    for (String k : SeasonStudy.list(a, dir, "data/spot/monthly/klines/" + s + "/1d/")) {
                        YearMonth m = YearMonth.parse(k.substring(k.length() - 11, k.length() - 4));
                        if (!m.isBefore(from) && !m.isAfter(to)) a.fetch(k);
                    }
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
        int days = (int) (to.atEndOfMonth().toEpochDay() - start.toEpochDay()) + 1;
        long startMs = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        Map<String, double[][]> m = new ConcurrentHashMap<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> fs = new ArrayList<>();
            for (String s : symbols) {
                fs.add(pool.submit(() -> {
                    Series x = a.load(s, startMs, days, from, to, null);
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
        return panel(start, m, excluded);
    }

    static Panel bybit(java.nio.file.Path dir, YearMonth from, YearMonth to, Set<String> excluded, StrategyParams params, int threads) throws Exception {
        Archive a = new Archive(dir);
        List<String> symbols = new ArrayList<>();
        for (String s : Files.readAllLines(dir.resolve("symbols.txt"))) {
            if (s.isBlank()) continue;
            if (com.trendpilot.universe.UniverseFilter.excludedOffline(params.universe(), s)) excluded.add(s);
            else symbols.add(s);
        }
        System.out.println("Bybit USDT 交易对（有数据）" + symbols.size() + " 个");
        LocalDate start = from.atDay(1);
        int days = (int) (to.atEndOfMonth().toEpochDay() - start.toEpochDay()) + 1;
        long startMs = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        Map<String, double[][]> m = new ConcurrentHashMap<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> fs = new ArrayList<>();
            for (String s : symbols) {
                fs.add(pool.submit(() -> {
                    Series x = a.load(s, startMs, days * 96, from, to, null);
                    if (x != null) m.put(s, toDaily(x, days));
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
        }
        return panel(start, m, excluded);
    }

    /** 15m → daily close (last 15m close of the UTC day) and summed quote volume; days without trades stay NaN. */
    static double[][] toDaily(Series x, int days) {
        double[] c = new double[days], v = new double[days];
        Arrays.fill(c, Double.NaN);
        for (int d = 0; d < days; d++) {
            for (int i = d * 96; i < (d + 1) * 96 && i < x.size(); i++) {
                if (!x.has(i)) continue;
                c[d] = x.close[i];
                v[d] += x.quoteVolume[i];
            }
        }
        return new double[][]{c, v};
    }

    static Panel panel(LocalDate start, Map<String, double[][]> m, Set<String> excluded) {
        String[] syms = m.keySet().stream().sorted().toArray(String[]::new);
        double[][] c = new double[syms.length][], v = new double[syms.length][];
        for (int i = 0; i < syms.length; i++) {
            c[i] = m.get(syms[i])[0];
            v[i] = m.get(syms[i])[1];
        }
        return new Panel(start, syms, c, v, excluded);
    }

    public static void main(String[] args) throws Exception {
        java.nio.file.Path dataDir = java.nio.file.Path.of(args[0]);
        java.nio.file.Path outDir = java.nio.file.Path.of(args[1]);
        String mode = args[2];
        YearMonth binFrom = YearMonth.parse(args[3]);
        YearMonth to = YearMonth.parse(args[4]);
        LocalDate binTrade = LocalDate.parse(args[5]);
        YearMonth bybFrom = YearMonth.parse(args[6]);
        LocalDate bybTrade = LocalDate.parse(args[7]);
        int threads = Integer.parseInt(args[8]);
        Files.createDirectories(outDir);
        StrategyParams params = BacktestMain.loadParams();
        LocalDate last = to.atEndOfMonth();
        List<Run> runs = new ArrayList<>();
        int trials = PRIOR_TRIALS + specs().size();
        if (!mode.equals("binance")) {
            Panel p = bybit(dataDir, bybFrom, to, new HashSet<>(), params, threads);
            Run r = new Run(p, "Bybit（主结果）", bybTrade, last);
            study(r, threads);
            runs.add(r);
        }
        if (!mode.equals("bybit")) {
            Panel p = binance(dataDir.resolve("binance-1d"), binFrom, to, new HashSet<>(), params, threads);
            Run r = new Run(p, "Binance（旁证，含已下架币）", binTrade, last);
            study(r, threads);
            runs.add(r);
        }
        String md = "# 文献策略回测结果（LitStudy 自动生成）\n\n试验数：本次 " + specs().size() + " 组，台账累计 " + trials
                + " 组（Bonferroni 按 " + trials + " 计）。随机组合每个策略 " + RANDOMS + " 个。\n" + report(runs, trials);
        Files.writeString(outDir.resolve("report.md"), md);
        Files.writeString(outDir.resolve("summary.csv"), csv(runs, trials));
        for (Run run : runs) {
            StringBuilder e = new StringBuilder("date");
            for (Row r : run.rows) e.append(',').append(r.spec().key());
            for (String k : run.bench.keySet()) e.append(',').append(k.replace(',', ' '));
            e.append('\n');
            for (int d = run.from; d <= run.to; d++) {
                e.append(run.p.date(d));
                for (Row r : run.rows) e.append(String.format(Locale.ROOT, ",%.5f", r.real().nav()[d - run.from]));
                for (double[] v : run.bench.values()) e.append(String.format(Locale.ROOT, ",%.5f", v[d - run.from]));
                e.append('\n');
            }
            Files.writeString(outDir.resolve("nav-" + (run.label.startsWith("Bybit") ? "bybit" : "binance") + ".csv"), e.toString());
        }
        System.out.println(md);
        System.out.println("=== summary.csv ===");
        System.out.println(csv(runs, trials));
    }

    static int[] ints(List<Integer> l) {
        return l.stream().mapToInt(Integer::intValue).toArray();
    }

    static double[] dbls(List<Double> l) {
        return l.stream().mapToDouble(Double::doubleValue).toArray();
    }

}
