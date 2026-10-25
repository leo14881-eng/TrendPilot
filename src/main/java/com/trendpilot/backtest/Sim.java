package com.trendpilot.backtest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.job.AppState;
import com.trendpilot.research.Identity;
import com.trendpilot.research.ResearchRecord;
import com.trendpilot.market.Venue;

/**
 * 一个方案的逐根回测：每根 15m 先管理持仓，整点后第一根收盘（线上 :17 的 Discovery）再找新买入。
 * 权益（已实现 + 按可退出价格的浮动）每小时记一次，最大回撤按这条曲线算。
 */
public abstract class Sim {

    protected final String name;
    protected final MarketData data;
    protected final Regimes regimes;
    protected final SignalTape tape;
    protected final StrategyParams params;
    protected final int fromQ;
    protected final int toQ;

    protected double realized;
    /** 每隔多少根 15m 做一次买入审核（默认 4 = 每小时，对应线上 :17 的 Discovery）。 */
    protected int discoverEvery = 4;
    protected final List<Trade> trades = new ArrayList<>();
    /** 每个整点的权益（已实现 + 浮动），下标为小时。 */
    protected final double[] equity;
    /** 每个整点占用的本金（持仓成本合计），算资金使用率。 */
    protected final double[] deployed;
    /** 每个币出现 EARLY 的 15m 下标（再入场证据用）。 */
    protected final Map<String, TreeSet<Integer>> earlyLog = new HashMap<>();

    protected Sim(String name, MarketData data, Regimes regimes, SignalTape tape, StrategyParams params, int fromQ, int toQ) {
        this.name = name;
        this.data = data;
        this.regimes = regimes;
        this.tape = tape;
        this.params = params;
        this.fromQ = fromQ;
        this.toQ = toQ;
        this.equity = new double[data.size / 4 + 1];
        this.deployed = new double[data.size / 4 + 1];
    }

    public String name() {
        return name;
    }

    public List<Trade> trades() {
        return trades;
    }

    public double[] equity() {
        return equity;
    }

    public double[] deployed() {
        return deployed;
    }

    public void run() {
        for (int q = fromQ; q < toQ; q++) {
            manage(q);
            if (q % discoverEvery == 0) discover(q);
            if (q % 4 == 3) {
                equity[q / 4 + 1] = realized + unrealized(q);
                deployed[q / 4 + 1] = deployedCost();
            }
        }
        closeAll(toQ - 1);
        equity[toQ / 4] = realized;
        deployed[toQ / 4] = 0;
    }

    /** 管理第 q 根 15m 期间的持仓（开高低收路径）。 */
    protected abstract void manage(int q);

    /** 第 q 根（整点后第一根）收盘时的买入/加仓。 */
    protected abstract void discover(int q);

    protected abstract double unrealized(int q);

    /** 当前持仓占用的本金。 */
    protected abstract double deployedCost();

    /** 回测结束时按最后收盘价全部卖出（记为 END）。 */
    protected abstract void closeAll(int q);

    protected Instant now(int q) {
        return Instant.ofEpochMilli(data.time(q) + Series.Q + 2 * 60_000L);
    }

    protected String gen(int q) {
        return "D-" + q;
    }

    protected static ResearchRecord verified(String symbol) {
        return new ResearchRecord(Venue.BINANCE, symbol, symbol, Identity.VERIFIED, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, List.of(), List.of());
    }

    protected void logEarly(String symbol, int q) {
        earlyLog.computeIfAbsent(symbol, k -> new TreeSet<>()).add(q);
    }

    /** exitQ 之后（不含）到 q（含）之间出现 EARLY 的整点个数。 */
    protected int earlySince(String symbol, int exitQ, int q) {
        TreeSet<Integer> s = earlyLog.get(symbol);
        return s == null ? 0 : s.subSet(exitQ, false, q, true).size();
    }

    /** 日线 ATR（最近 14 天，按 96 根 15m 合成的日 K 线），占价格比例 %。 */
    protected double atrDaily(Series s, int q) {
        double sum = 0;
        int n = 0;
        double prevClose = Double.NaN;
        for (int d = 14; d >= 1; d--) {
            int from = q - d * 96 + 1, to = from + 96;
            if (from < 0) continue;
            double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY, c = Double.NaN;
            for (int i = from; i < to; i++) {
                if (!s.has(i)) continue;
                hi = Math.max(hi, s.high[i]);
                lo = Math.min(lo, s.low[i]);
                c = s.close[i];
            }
            if (Double.isNaN(c)) continue;
            double tr = Double.isNaN(prevClose) ? hi - lo : Math.max(hi, prevClose) - Math.min(lo, prevClose);
            sum += tr;
            n++;
            prevClose = c;
        }
        return n == 0 || !s.has(q) ? Double.NaN : sum / n / s.close[q] * 100;
    }

    /** 卖出后 3 天内最高价是否回到 price 以上。 */
    protected boolean recovers(Series s, int exitQ, double price) {
        for (int i = exitQ + 1; i <= exitQ + 288 && i < s.size(); i++) {
            if (s.has(i) && s.high[i] >= price) return true;
        }
        return false;
    }

    /** 内存版 AppState：让线上的 RiskRecovery 原样在回测里运行。 */
    protected static final class MemoryState extends AppState {
        private final Map<String, String> map = new HashMap<>();

        public MemoryState() {
            super(null);
        }

        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(map.get(key));
        }

        @Override
        public void put(String key, String value) {
            map.put(key, value);
        }
    }

    /** 持仓期间的价格路径记录（最大浮盈浮亏、到高点之前的最大跌幅）。 */
    protected static final class Path {
        final int entryQ;
        final double entryPrice;
        double maxNet = 0, minNet = 0;
        double peakHigh;
        double lowBeforePeak;
        double lowSoFar;

        Path(int entryQ, double entryPrice) {
            this.entryQ = entryQ;
            this.entryPrice = entryPrice;
            peakHigh = entryPrice;
            lowBeforePeak = entryPrice;
            lowSoFar = entryPrice;
        }

        void bar(double high, double low) {
            lowSoFar = Math.min(lowSoFar, low); // 同一根 K 线里低点算在高点之前（保守）
            if (high > peakHigh) {
                peakHigh = high;
                lowBeforePeak = lowSoFar;
            }
        }

        void net(double net) {
            maxNet = Math.max(maxNet, net);
            minNet = Math.min(minNet, net);
        }

        double maeBeforePeak() {
            return (lowBeforePeak / entryPrice - 1) * 100;
        }

        double mfe() {
            return (peakHigh / entryPrice - 1) * 100;
        }
    }
}
