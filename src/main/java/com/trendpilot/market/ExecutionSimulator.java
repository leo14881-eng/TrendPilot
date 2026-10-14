package com.trendpilot.market;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 按盘口逐档模拟成交（全数量），用于买入审核和退出估算。只是模拟，不下单。
 */
public final class ExecutionSimulator {

    private static final MathContext MC = MathContext.DECIMAL64;

    private ExecutionSimulator() {
    }

    /**
     * @param complete 盘口深度是否足够吃下全部数量
     * @param slippageBps 成交均价相对中间价的偏离（买入为正、卖出也取正）
     */
    public record Fill(BigDecimal qty, BigDecimal notional, BigDecimal avgPrice, BigDecimal fee,
                       double slippageBps, boolean complete) {

        /** 卖出净到手（成交额 − 手续费）。 */
        public BigDecimal netProceeds() {
            return notional.subtract(fee);
        }

        /** 买入总成本（成交额 + 手续费）。 */
        public BigDecimal totalCost() {
            return notional.add(fee);
        }
    }

    /** 用 notional USDT 吃卖盘。 */
    public static Fill buy(OrderBook book, BigDecimal notional, double feeRate) {
        BigDecimal remaining = notional;
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal spent = BigDecimal.ZERO;
        for (OrderBook.Level l : book.asks()) {
            BigDecimal levelValue = l.price().multiply(l.qty(), MC);
            if (levelValue.compareTo(remaining) >= 0) {
                qty = qty.add(remaining.divide(l.price(), MC));
                spent = spent.add(remaining);
                remaining = BigDecimal.ZERO;
                break;
            }
            qty = qty.add(l.qty());
            spent = spent.add(levelValue);
            remaining = remaining.subtract(levelValue);
        }
        return fill(book, qty, spent, feeRate, remaining.signum() == 0, true);
    }

    /** 卖出 qty，吃买盘。 */
    public static Fill sell(OrderBook book, BigDecimal qty, double feeRate) {
        BigDecimal remaining = qty;
        BigDecimal received = BigDecimal.ZERO;
        for (OrderBook.Level l : book.bids()) {
            BigDecimal take = remaining.min(l.qty());
            received = received.add(take.multiply(l.price(), MC));
            remaining = remaining.subtract(take);
            if (remaining.signum() == 0) {
                break;
            }
        }
        return fill(book, qty.subtract(remaining), received, feeRate, remaining.signum() == 0, false);
    }

    private static Fill fill(OrderBook book, BigDecimal qty, BigDecimal notional, double feeRate, boolean complete,
                             boolean isBuy) {
        if (qty.signum() == 0) {
            return new Fill(qty, notional, BigDecimal.ZERO, BigDecimal.ZERO, Double.POSITIVE_INFINITY, false);
        }
        BigDecimal avg = notional.divide(qty, MC);
        BigDecimal mid = book.mid();
        double slip = avg.subtract(mid).divide(mid, MC).doubleValue() * 10_000 * (isBuy ? 1 : -1);
        BigDecimal fee = notional.multiply(BigDecimal.valueOf(feeRate), MC).setScale(8, RoundingMode.HALF_UP);
        return new Fill(qty, notional, avg, fee, slip, complete);
    }
}
