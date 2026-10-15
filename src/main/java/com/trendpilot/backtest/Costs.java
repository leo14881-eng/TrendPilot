package com.trendpilot.backtest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.trendpilot.market.OrderBook;

/**
 * 没有历史盘口时的成交成本：按 24h 成交额分档估算 1,000U 的冲击（信号跟踪设计附录 A），
 * 金额不同按平方根缩放；手续费单边 0.1%。深度门槛用 24h 成交额近似：低于 3M U 视为深度不够。
 */
public final class Costs {

    public static final double THIN_VOLUME = 3_000_000;
    /** 深度不够时合成盘口每边的金额（低于审核门槛 30k，高于硬失效门槛 5k）。 */
    static final double THIN_DEPTH = 20_000;

    private Costs() {
    }

    /** 1,000U 的单边冲击（bps）。 */
    public static double impactBps(double quoteVolume24h) {
        if (quoteVolume24h >= 50_000_000) return 5;
        if (quoteVolume24h >= 10_000_000) return 10;
        if (quoteVolume24h >= 2_000_000) return 25;
        return 50;
    }

    /** 金额 notional 的单边冲击（比例）。 */
    public static double impact(double quoteVolume24h, double notional) {
        return impactBps(quoteVolume24h) / 10_000 * Math.sqrt(Math.max(notional, 1) / 1000);
    }

    /**
     * 合成盘口：买卖各一档，价格 = 参考价 ×(1 ∓ 1,000U 冲击)，给线上的 CapitalReviewer / ExitPolicy 原样使用。
     * 成交额低于 3M U 时每边只有 20k U，审核会因深度不足拒绝。
     */
    public static OrderBook book(String symbol, double price, double quoteVolume24h, Instant at) {
        double i = impactBps(quoteVolume24h) / 10_000;
        double depth = quoteVolume24h < THIN_VOLUME ? THIN_DEPTH : 1e12;
        BigDecimal bid = BigDecimal.valueOf(price * (1 - i));
        BigDecimal ask = BigDecimal.valueOf(price * (1 + i));
        BigDecimal qty = BigDecimal.valueOf(depth / price);
        return new OrderBook(symbol, List.of(new OrderBook.Level(bid, qty)), List.of(new OrderBook.Level(ask, qty)), at);
    }
}
