package com.trendpilot.market;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.List;

/** 盘口快照。bids 从高到低，asks 从低到高。 */
public record OrderBook(String symbol, List<Level> bids, List<Level> asks, Instant fetchedAt) {

    private static final MathContext MC = MathContext.DECIMAL64;

    public record Level(BigDecimal price, BigDecimal qty) {
    }

    /**
     * 参考价"盘口"：买卖价都等于参考价、数量不限。V1 观察账本用它按参考价 + 手续费记账，
     * 不代表真实可成交深度。
     */
    public static OrderBook reference(String symbol, double price, Instant at) {
        Level l = new Level(BigDecimal.valueOf(price), new BigDecimal("1e15"));
        return new OrderBook(symbol, List.of(l), List.of(l), at);
    }

    public boolean usable() {
        return !bids.isEmpty() && !asks.isEmpty();
    }

    /** 可以用来做判断：两边都有挂单，价格和数量为正，买一不高于卖一（不是交叉盘口）。 */
    public boolean sane() {
        return usable() && bestBid().signum() > 0 && bestAsk().compareTo(bestBid()) >= 0
                && bids.getFirst().qty().signum() > 0 && asks.getFirst().qty().signum() > 0;
    }

    public BigDecimal bestBid() {
        return bids.getFirst().price();
    }

    public BigDecimal bestAsk() {
        return asks.getFirst().price();
    }

    public BigDecimal mid() {
        return bestBid().add(bestAsk()).divide(BigDecimal.TWO, MC);
    }

    public double spreadBps() {
        return bestAsk().subtract(bestBid()).divide(mid(), MC).doubleValue() * 10_000;
    }

    /** 中间价上下 bandPct% 范围内的买盘、卖盘金额（USDT）。 */
    public double bidDepthWithin(double bandPct) {
        BigDecimal floor = mid().multiply(BigDecimal.valueOf(1 - bandPct / 100), MC);
        return bids.stream().filter(l -> l.price().compareTo(floor) >= 0)
                .map(l -> l.price().multiply(l.qty(), MC)).reduce(BigDecimal.ZERO, BigDecimal::add).doubleValue();
    }

    public double askDepthWithin(double bandPct) {
        BigDecimal cap = mid().multiply(BigDecimal.valueOf(1 + bandPct / 100), MC);
        return asks.stream().filter(l -> l.price().compareTo(cap) <= 0)
                .map(l -> l.price().multiply(l.qty(), MC)).reduce(BigDecimal.ZERO, BigDecimal::add).doubleValue();
    }
}
