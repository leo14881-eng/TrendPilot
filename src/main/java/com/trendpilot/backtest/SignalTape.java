package com.trendpilot.backtest;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.trendpilot.market.SymbolBars;
import com.trendpilot.signal.EarlySignal;
import com.trendpilot.signal.EarlySignalCalculator;

/**
 * 回测里的 EARLY 信号：直接调用线上的 {@link EarlySignalCalculator}，按（币, 15m 下标）缓存，所有方案共用。
 */
public final class SignalTape {

    private static final EarlySignal NONE = new EarlySignal("", 0, 0, 0, 0, 0, 0, 0, 0, java.util.List.of(), false, 0);

    private final MarketData data;
    private final EarlySignalCalculator calc;
    private final Map<String, Map<Integer, EarlySignal>> cache = new ConcurrentHashMap<>();

    public SignalTape(MarketData data, EarlySignalCalculator calc) {
        this.data = data;
        this.calc = calc;
    }

    /** 第 q 根 15m 收盘时的信号；数据不够返回 null。 */
    public EarlySignal at(String symbol, int q) {
        EarlySignal s = cache.computeIfAbsent(symbol, k -> new ConcurrentHashMap<>()).computeIfAbsent(q, k -> {
            SymbolBars b = data.bars(symbol, q);
            SymbolBars btc = data.bars(MarketData.BTC, q);
            EarlySignal x = b == null || btc == null ? null : calc.compute(b, btc);
            return x == null ? NONE : x;
        });
        return s == NONE ? null : s;
    }
}
