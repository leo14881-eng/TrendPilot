package com.trendpilot.backtest;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import com.trendpilot.config.StrategyParams;

/**
 * "Big wins, small losses" research rules (user 2026-11-02): daily-bar decisions on the point-in-time pool, executed at the
 * next day's open, with every position protected by a hard stop checked on each 15m bar (touch, filled at the stop or the
 * gap open). Three structures, fixed before looking at results:
 * <ul>
 * <li>{@link Kind#ROTATION} (A): weekly cross-sectional momentum vs BTC; top N of the 100 most traded coins, equal weight;
 *     sell when the coin drops out of the top 2N or closes below its 20-day average; 2.5 x daily ATR stop; new positions only
 *     while BTC is above its 100-day average or at least half of the pool is above its 20-day average.</li>
 * <li>{@link Kind#MA_TREND} (B): the BTC moving-average idea on the 25 most traded alts: buy above MA x 1.02, sell below
 *     MA x 0.98, one share per coin (equal or volatility-scaled), 2.5 x ATR hard stop.</li>
 * <li>{@link Kind#BREAKOUT} (C): N-day closing breakout while BTC is above its 100-day average; unit sized so 2 x ATR = 100 U;
 *     one more unit for every 1 x ATR gained (at most 4 units), stop for the whole position = last unit price - 2 x ATR;
 *     exit on a close below the X-day low.</li>
 * </ul>
 * Each simulation runs a random twin in lockstep: for every real buy, a random coin from the same pool that day (not one of
 * the day's signals) is bought with the same amount, adds at the same times, uses the same stop rule on its own ATR and is
 * sold no later than the real position ("same holding period and stop, random coin"). Missing data never trades.
 */
final class ConvexSim extends Sim {

    enum Kind { ROTATION, MA_TREND, BREAKOUT }

    /**
     * @param lookback   ROTATION: return lookback in days
     * @param topN       ROTATION: number of coins held
     * @param maDays     MA_TREND: moving average length
     * @param volSized   MA_TREND: volatility-scaled share instead of equal
     * @param breakout   BREAKOUT: N-day high
     * @param exitLow    BREAKOUT: X-day low exit
     */
    record Rule(String id, Kind kind, int lookback, int topN, int maDays, boolean volSized, int breakout, int exitLow) {

        static Rule rotation(int lookback, int topN) {
            return new Rule("A_rel" + lookback + "d_top" + topN, Kind.ROTATION, lookback, topN, 0, false, 0, 0);
        }

        static Rule maTrend(int ma, boolean vol) {
            return new Rule("B_ma" + ma + (vol ? "_vol" : "_equal"), Kind.MA_TREND, 0, 0, ma, vol, 0, 0);
        }

        static Rule breakout(int n, int x) {
            return new Rule("C_high" + n + "_low" + x, Kind.BREAKOUT, 0, 0, 0, false, n, x);
        }

        String family() {
            return id.substring(0, 1);
        }
    }

    static final int DAY = MarketData.DAY;
    static final int ROTATION_POOL = 100;
    static final int MA_POOL = 25;
    static final int BREAKOUT_POOL = 100;
    static final double STOP_ATR = 2.5;
    static final double MIN_STOP = 0.04;
    static final double MAX_STOP = 0.35;
    static final double MA_BAND = 0.02;
    static final double UNIT_RISK = 100;
    static final double UNIT_CAP = 2500;
    static final int MAX_UNITS = 4;
    static final double MIN_NOTIONAL = 100;

    /** Daily bars built from the 15m grid (day k = 15m bars [96k, 96k + 96)); NaN when the day has no bar. */
    static final class Daily {
        final double[] open, high, low, close, quote;

        Daily(Series s, int days) {
            open = nan(days);
            high = nan(days);
            low = nan(days);
            close = nan(days);
            quote = new double[days];
            for (int k = 0; k < days; k++) {
                double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY, o = Double.NaN, c = Double.NaN, v = 0;
                for (int i = k * DAY; i < (k + 1) * DAY && i < s.size(); i++) {
                    if (!s.has(i)) continue;
                    if (Double.isNaN(o)) o = s.open[i];
                    hi = Math.max(hi, s.high[i]);
                    lo = Math.min(lo, s.low[i]);
                    c = s.close[i];
                    v += s.quoteVolume[i];
                }
                if (Double.isNaN(c)) continue;
                open[k] = o;
                high[k] = hi;
                low[k] = lo;
                close[k] = c;
                quote[k] = v;
            }
        }

        static Map<String, Daily> of(MarketData data) {
            int days = data.size / DAY;
            Map<String, Daily> m = new HashMap<>();
            data.series.forEach((k, v) -> m.put(k, new Daily(v, days)));
            return m;
        }

        private static double[] nan(int n) {
            double[] a = new double[n];
            java.util.Arrays.fill(a, Double.NaN);
            return a;
        }
    }

    /** Mean of x[k - m + 1 .. k]; NaN if any value is missing. */
    static double sma(double[] x, int k, int m) {
        if (k - m + 1 < 0 || k >= x.length) return Double.NaN;
        double s = 0;
        for (int i = k - m + 1; i <= k; i++) {
            if (Double.isNaN(x[i])) return Double.NaN;
            s += x[i];
        }
        return s / m;
    }

    /** 14-day average true range of day k as % of the close; NaN if any of the 15 days is missing. */
    static double atrPct(Daily d, int k) {
        if (k - 14 < 0 || k >= d.close.length) return Double.NaN;
        double s = 0;
        for (int i = k - 13; i <= k; i++) {
            double pc = d.close[i - 1];
            if (Double.isNaN(pc) || Double.isNaN(d.close[i])) return Double.NaN;
            s += Math.max(d.high[i], pc) - Math.min(d.low[i], pc);
        }
        return s / 14 / d.close[k] * 100;
    }

    /** Highest high of the n days before day k (k excluded); NaN if any is missing. */
    static double priorHigh(Daily d, int k, int n) {
        if (k - n < 0) return Double.NaN;
        double m = Double.NEGATIVE_INFINITY;
        for (int i = k - n; i < k; i++) {
            if (Double.isNaN(d.high[i])) return Double.NaN;
            m = Math.max(m, d.high[i]);
        }
        return m;
    }

    /** Lowest low of the n days before day k (k excluded); NaN if any is missing. */
    static double priorLow(Daily d, int k, int n) {
        if (k - n < 0) return Double.NaN;
        double m = Double.POSITIVE_INFINITY;
        for (int i = k - n; i < k; i++) {
            if (Double.isNaN(d.low[i])) return Double.NaN;
            m = Math.min(m, d.low[i]);
        }
        return m;
    }

    static double ret(double[] c, int k, int n) {
        if (k - n < 0 || Double.isNaN(c[k]) || Double.isNaN(c[k - n]) || c[k - n] <= 0) return Double.NaN;
        return c[k] / c[k - n] - 1;
    }

    /** Stop distance (fraction) from an ATR multiple, clamped to 4% .. 35%. */
    static double stopDistance(double atrPct, double mult) {
        return Math.min(MAX_STOP, Math.max(MIN_STOP, atrPct * mult / 100));
    }

    /** BREAKOUT unit size: 2 x ATR loss = 100 U, at most 2,500 U. */
    static double unitNotional(double atrPct) {
        return Math.min(UNIT_CAP, UNIT_RISK / stopDistance(atrPct, 2));
    }

    /** MA_TREND share: equal = capital / 25; volatility-scaled = equal x 5% / ATR%, kept within 200 .. 1,600 U. */
    static double maShare(double capital, double atrPct, boolean vol) {
        double eq = capital / MA_POOL;
        return vol ? Math.max(200, Math.min(1600, eq * 5 / atrPct)) : eq;
    }

    static final class Pos {
        final String sym;
        final int entryQ;
        final double atrPct;
        final double atrAbs;
        double qty, cost, stop, lastAdd, r, maxNet, minNet, peakHigh, avgPrice;
        int units = 1;
        int exitFromQ = Integer.MAX_VALUE;
        String exitWhy;
        Pos twin;
        boolean raised;

        Pos(String sym, int entryQ, double atrPct, double fillPrice) {
            this.sym = sym;
            this.entryQ = entryQ;
            this.atrPct = atrPct;
            this.atrAbs = atrPct / 100 * fillPrice;
            this.peakHigh = fillPrice;
        }
    }

    /** The random twin's book, readable as an ordinary simulation (trades + hourly equity). */
    static final class View extends Sim {
        View(String name, MarketData data, Regimes regimes, StrategyParams params, int fromQ, int toQ) {
            super(name, data, regimes, null, params, fromQ, toQ);
        }

        @Override
        public void run() {
        }

        @Override
        protected void manage(int q) {
        }

        @Override
        protected void discover(int q) {
        }

        @Override
        protected double unrealized(int q) {
            return 0;
        }

        @Override
        protected double deployedCost() {
            return 0;
        }

        @Override
        protected void closeAll(int q) {
        }
    }

    final Rule rule;
    private final Map<String, Daily> daily;
    private final double capital;
    private final double fee;
    private final SplittableRandom rnd;
    private final Map<String, Pos> open = new LinkedHashMap<>();
    private final List<Pos> twins = new ArrayList<>();
    private final Set<String> blocked = new HashSet<>();
    private final View twin;
    private double twinRealized;
    /** Days the new-position filter was open / evaluated. */
    int filterOn, filterDays;

    ConvexSim(Rule rule, MarketData data, Regimes regimes, StrategyParams params, Map<String, Daily> daily, int fromQ, int toQ,
              long seed) {
        super(rule.id(), data, regimes, null, params, fromQ, toQ);
        this.rule = rule;
        this.daily = daily;
        this.capital = params.capital().hardCap();
        this.fee = params.feeRate();
        this.rnd = new SplittableRandom(seed);
        this.twin = new View(rule.id() + " 随机", data, regimes, params, fromQ, toQ);
    }

    View twin() {
        return twin;
    }

    @Override
    public void run() {
        for (int q = fromQ; q < toQ; q++) {
            manage(q);
            if (q % 4 == 3) {
                equity[q / 4 + 1] = realized + unrealized(q);
                deployed[q / 4 + 1] = deployedCost();
                twin.equity[q / 4 + 1] = twinRealized + value(twins, q);
            }
        }
        closeAll(toQ - 1);
        equity[toQ / 4] = realized;
        deployed[toQ / 4] = 0;
        twin.equity[toQ / 4] = twinRealized;
    }

    @Override
    protected void manage(int q) {
        if (q % DAY == 0 && q / DAY >= 1) daily(q);
        for (Pos p : new ArrayList<>(open.values())) bar(p, q, false);
        for (Pos p : new ArrayList<>(twins)) bar(p, q, true);
    }

    @Override
    protected void discover(int q) {
    }

    @Override
    protected double unrealized(int q) {
        return value(open.values(), q);
    }

    @Override
    protected double deployedCost() {
        return open.values().stream().mapToDouble(p -> p.cost).sum();
    }

    @Override
    protected void closeAll(int q) {
        for (Pos p : new ArrayList<>(open.values())) {
            double px = data.get(p.sym).lastClose(q, Integer.MAX_VALUE);
            close(p, q, px, "END", false);
        }
        for (Pos p : new ArrayList<>(twins)) {
            double px = data.get(p.sym).lastClose(q, Integer.MAX_VALUE);
            close(p, q, px, "END", true);
        }
    }

    // ---------------------------------------------------------------- daily decisions (executed at the open of bar q)

    private void daily(int q) {
        int k = q / DAY - 1;
        List<String> pool = pool(q, k, switch (rule.kind()) {
            case ROTATION -> ROTATION_POOL;
            case MA_TREND -> MA_POOL;
            case BREAKOUT -> BREAKOUT_POOL;
        });
        switch (rule.kind()) {
            case ROTATION -> rotation(q, k, pool);
            case MA_TREND -> maTrend(q, k, pool);
            case BREAKOUT -> breakout(q, k, pool);
        }
    }

    /** First n coins of the point-in-time universe (by previous-day turnover) with a close and >= 3M U turnover on day k. */
    List<String> pool(int q, int k, int n) {
        List<String> out = new ArrayList<>();
        for (String s : data.universe(q)) {
            if (out.size() >= n) break;
            Daily d = daily.get(s);
            if (d == null || Double.isNaN(d.close[k]) || d.quote[k] < Costs.THIN_VOLUME) continue;
            out.add(s);
        }
        return out;
    }

    boolean btcAbove100(int k) {
        Daily b = daily.get(MarketData.BTC);
        if (b == null) return false;
        double m = sma(b.close, k, 100);
        return !Double.isNaN(m) && b.close[k] > m;
    }

    /** Share of pool coins closing above their 20-day average (NaN with fewer than 20 measurable coins). */
    double breadth(List<String> pool, int k) {
        int n = 0, up = 0;
        for (String s : pool) {
            Daily d = daily.get(s);
            double m = sma(d.close, k, 20);
            if (Double.isNaN(m)) continue;
            n++;
            if (d.close[k] > m) up++;
        }
        return n < 20 ? Double.NaN : up / (double) n;
    }

    private void rotation(int q, int k, List<String> pool) {
        // daily: close below the 20-day average -> sell at today's open
        for (Pos p : new ArrayList<>(open.values())) {
            Daily d = daily.get(p.sym);
            double m = sma(d.close, k, 20);
            if (!Double.isNaN(m) && d.close[k] < m) exitAtOpen(p, q, "MA20_EXIT");
        }
        if (Instant.ofEpochMilli(data.time(q)).atZone(ZoneOffset.UTC).getDayOfWeek() != DayOfWeek.MONDAY) return;
        Daily btc = daily.get(MarketData.BTC);
        double bRet = btc == null ? Double.NaN : ret(btc.close, k, rule.lookback());
        List<String> ranked = new ArrayList<>();
        Map<String, Double> score = new HashMap<>();
        for (String s : pool) {
            double r = ret(daily.get(s).close, k, rule.lookback());
            if (Double.isNaN(r) || Double.isNaN(bRet)) continue;
            score.put(s, r - bRet);
            ranked.add(s);
        }
        ranked.sort(Comparator.comparingDouble((String s) -> -score.get(s)));
        int keep = 2 * rule.topN();
        for (Pos p : new ArrayList<>(open.values())) {
            int rank = ranked.indexOf(p.sym);
            if (rank < 0 || rank >= keep) exitAtOpen(p, q, "RANK_EXIT");
        }
        boolean filter = btcAbove100(k) || breadth(pool, k) >= 0.5;
        filterDays++;
        if (filter) filterOn++;
        if (!filter) return;
        List<String> signals = new ArrayList<>();
        for (int i = 0; i < Math.min(rule.topN(), ranked.size()); i++) {
            String s = ranked.get(i);
            Daily d = daily.get(s);
            double m = sma(d.close, k, 20);
            if (score.get(s) > 0 && !Double.isNaN(m) && d.close[k] > m) signals.add(s);
        }
        for (String s : signals) {
            if (open.size() >= rule.topN()) break;
            if (open.containsKey(s)) continue;
            double atr = atrPct(daily.get(s), k);
            enter(s, q, k, capital / rule.topN(), atr, STOP_ATR, signals, pool);
        }
    }

    private void maTrend(int q, int k, List<String> pool) {
        for (Pos p : new ArrayList<>(open.values())) {
            Daily d = daily.get(p.sym);
            double m = sma(d.close, k, rule.maDays());
            if (!Double.isNaN(m) && d.close[k] < m * (1 - MA_BAND)) exitAtOpen(p, q, "MA_EXIT");
        }
        for (String s : new ArrayList<>(blocked)) {
            Daily d = daily.get(s);
            double m = d == null ? Double.NaN : sma(d.close, k, rule.maDays());
            if (!Double.isNaN(m) && d.close[k] < m) blocked.remove(s);
        }
        List<String> signals = new ArrayList<>();
        for (String s : pool) {
            Daily d = daily.get(s);
            double m = sma(d.close, k, rule.maDays());
            if (!Double.isNaN(m) && d.close[k] > m * (1 + MA_BAND) && !open.containsKey(s) && !blocked.contains(s)) signals.add(s);
        }
        for (String s : signals) {
            double atr = atrPct(daily.get(s), k);
            if (Double.isNaN(atr) || atr <= 0) continue;
            enter(s, q, k, maShare(capital, atr, rule.volSized()), atr, STOP_ATR, signals, pool);
        }
    }

    private void breakout(int q, int k, List<String> pool) {
        for (Pos p : new ArrayList<>(open.values())) {
            Daily d = daily.get(p.sym);
            double lo = priorLow(d, k, rule.exitLow());
            if (!Double.isNaN(lo) && !Double.isNaN(d.close[k]) && d.close[k] < lo) exitAtOpen(p, q, "LOW_EXIT");
        }
        boolean filter = btcAbove100(k);
        filterDays++;
        if (filter) filterOn++;
        if (!filter) return;
        List<String> signals = new ArrayList<>();
        for (String s : pool) {
            Daily d = daily.get(s);
            double hi = priorHigh(d, k, rule.breakout());
            if (!Double.isNaN(hi) && d.close[k] > hi && !open.containsKey(s)) signals.add(s);
        }
        for (String s : signals) {
            double atr = atrPct(daily.get(s), k);
            if (Double.isNaN(atr) || atr <= 0) continue;
            enter(s, q, k, unitNotional(atr), atr, 2, signals, pool);
        }
    }

    // ---------------------------------------------------------------- fills

    private double vol24h(Series s, int q) {
        return s.volume(q - 1, DAY);
    }

    /** Buy at the open of bar q (skipped when the bar is missing, the ATR is unknown or capital is used up), plus a twin. */
    private void enter(String sym, int q, int k, double want, double atr, double stopMult, List<String> signals, List<String> pool) {
        Series s = data.get(sym);
        if (s == null || !s.has(q) || Double.isNaN(atr) || atr <= 0) return;
        double notional = Math.min(want, capital - deployedCost());
        if (notional < MIN_NOTIONAL) return;
        Pos p = buy(sym, s, q, s.open[q], notional, atr, stopMult);
        open.put(sym, p);
        List<String> quiet = pool.stream().filter(x -> !signals.contains(x) && !open.containsKey(x)).toList();
        for (int tries = 0; tries < 5 && !quiet.isEmpty(); tries++) {
            String r = quiet.get(rnd.nextInt(quiet.size()));
            Series rs = data.get(r);
            double ratr = atrPct(daily.get(r), k);
            if (rs == null || !rs.has(q) || Double.isNaN(ratr) || ratr <= 0) continue;
            Pos t = buy(r, rs, q, rs.open[q], notional, ratr, stopMult);
            p.twin = t;
            twins.add(t);
            break;
        }
    }

    private Pos buy(String sym, Series s, int q, double price, double notional, double atr, double stopMult) {
        double fill = price * (1 + Costs.impact(vol24h(s, q), notional));
        Pos p = new Pos(sym, q, atr, fill);
        p.qty = notional / fill;
        p.cost = notional * (1 + fee);
        p.avgPrice = fill;
        p.lastAdd = fill;
        double dist = stopDistance(atr, stopMult);
        p.stop = fill * (1 - dist);
        p.r = notional * dist;
        return p;
    }

    private void exitAtOpen(Pos p, int q, String why) {
        Series s = data.get(p.sym);
        if (s.has(q)) {
            close(p, q, s.open[q], why, false);
        } else if (p.exitFromQ == Integer.MAX_VALUE) {
            p.exitFromQ = q + 1;
            p.exitWhy = why;
        }
    }

    /** One 15m bar: pending exit at the open, hard stop (touch), pyramid add (BREAKOUT), path tracking. */
    private void bar(Pos p, int q, boolean isTwin) {
        Series s = data.get(p.sym);
        if (!s.has(q)) {
            if (q > s.lastIndex()) {
                close(p, q, s.close[s.lastIndex()] * (1 - 0.005), "DELISTED", isTwin);
            }
            return;
        }
        if (q >= p.exitFromQ) {
            close(p, q, s.open[q], p.exitWhy, isTwin);
            return;
        }
        if (s.low[q] <= p.stop) {
            close(p, q, Math.min(s.open[q], p.stop), p.raised ? "STOP_RAISED" : "STOP_LOSS", isTwin);
            return;
        }
        if (!isTwin && rule.kind() == Kind.BREAKOUT && p.units < MAX_UNITS && s.close[q] >= p.lastAdd + p.atrAbs) {
            double add = Math.min(unitNotional(p.atrPct), capital - deployedCost());
            if (add >= MIN_NOTIONAL) {
                addUnit(p, s, q, add);
                Pos t = p.twin;
                if (t != null && twins.contains(t)) {
                    Series ts = data.get(t.sym);
                    if (ts.has(q)) addUnit(t, ts, q, add);
                }
            }
        }
        p.peakHigh = Math.max(p.peakHigh, s.high[q]);
        double net = p.qty * s.close[q] * (1 - Costs.impact(vol24h(s, q), p.cost)) * (1 - fee) - p.cost;
        p.maxNet = Math.max(p.maxNet, net);
        p.minNet = Math.min(p.minNet, net);
    }

    private void addUnit(Pos p, Series s, int q, double notional) {
        double fill = s.close[q] * (1 + Costs.impact(vol24h(s, q), notional));
        double qty = notional / fill;
        p.avgPrice = (p.avgPrice * p.qty + fill * qty) / (p.qty + qty);
        p.qty += qty;
        p.cost += notional * (1 + fee);
        p.units++;
        p.lastAdd = fill;
        double stop = fill - 2 * p.atrPct / 100 * fill;
        if (stop > p.stop) {
            p.stop = stop;
            p.raised = true;
        }
    }

    private void close(Pos p, int q, double price, String why, boolean isTwin) {
        Series s = data.get(p.sym);
        double proceeds = p.qty * price * (1 - Costs.impact(vol24h(s, Math.max(1, q)), p.cost)) * (1 - fee);
        if (why.equals("DELISTED") || why.equals("END")) proceeds = p.qty * price * (1 - fee);
        double pnl = proceeds - p.cost;
        Trade t = new Trade(p.sym, p.entryQ, q, p.cost, pnl, why, regimes.at(p.entryQ), p.r, p.units, p.maxNet, p.minNet,
                Double.NaN, (p.peakHigh / p.avgPrice - 1) * 100, Double.NaN, p.atrPct, false, false);
        if (isTwin) {
            twins.remove(p);
            twinRealized += pnl;
            twin.trades.add(t);
        } else {
            open.remove(p.sym);
            realized += pnl;
            trades.add(t);
            if (why.startsWith("STOP")) blocked.add(p.sym);
            Pos tw = p.twin;
            if (tw != null && twins.contains(tw) && tw.exitFromQ == Integer.MAX_VALUE) {
                tw.exitFromQ = q + 1;
                tw.exitWhy = "PAIR_EXIT";
            }
        }
    }

    private double value(Iterable<Pos> ps, int q) {
        double v = 0;
        for (Pos p : ps) {
            Series s = data.get(p.sym);
            double px = s.lastClose(q, 3 * DAY);
            if (Double.isNaN(px)) continue;
            v += p.qty * px * (1 - Costs.impact(vol24h(s, q), p.cost)) * (1 - fee) - p.cost;
        }
        return v;
    }
}
