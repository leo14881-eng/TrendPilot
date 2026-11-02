package com.trendpilot.variant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.trendpilot.market.ExecutionSimulator.Fill;
import com.trendpilot.market.OrderBook;
import com.trendpilot.market.Venue;

/**
 * 配置化影子变体的审核和成交记录（表 variant_decision，周复盘 2026-11-01 R2），写法参考 V2 的 DiscoveryJob.logDecision。
 * 只记录，不参与任何判断；写失败只记 warn，不影响买卖。
 */
final class VariantDecisionLog {

    private static final Logger log = LoggerFactory.getLogger(VariantDecisionLog.class);

    static final String REVIEW = "REVIEW";
    static final String FILL_BUY = "FILL_BUY";
    static final String FILL_SELL = "FILL_SELL";

    private final JdbcClient jdbc;

    VariantDecisionLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 一条记录：审核结论或一次成交。数值缺失时为 NaN / null。 */
    record Row(String book, String generation, Venue venue, String symbol, String kind, String outcome, List<String> reasons,
               double score, double spreadBps, double slippageBps, double bidDepth, double askDepth, OrderBook orderBook,
               Fill fill, double refClose) {
    }

    void write(Row r, Instant now) {
        if (jdbc == null) return;
        try {
            Double expected = r.fill() == null || r.fill().avgPrice() == null || r.fill().avgPrice().signum() <= 0
                    ? null : r.fill().avgPrice().doubleValue();
            Double ref = finite(r.refClose()) && r.refClose() > 0 ? r.refClose() : null;
            Double dev = expected == null || ref == null ? null : (expected / ref - 1) * 10_000;
            OrderBook ob = r.orderBook() != null && r.orderBook().usable() ? r.orderBook() : null;
            jdbc.sql("""
                    INSERT INTO variant_decision(book, generation, venue, symbol, kind, outcome, reasons, score, spread_bps,
                        slippage_bps, bid_depth, ask_depth, best_bid, best_ask, book_at, expected_price, ref_close, dev_bps,
                        notional, created_at)
                    VALUES (:b, :g, :v, :s, :k, :o, :r, :score, :sp, :sl, :bd, :ad, :bb, :ba, :bat, :e, :ref, :dev, :n, :t)
                    """)
                    .param("b", r.book()).param("g", r.generation()).param("v", r.venue() == null ? null : r.venue().name())
                    .param("s", r.symbol()).param("k", r.kind()).param("o", r.outcome())
                    .param("r", r.reasons() == null ? "" : String.join(",", r.reasons()))
                    .param("score", nan(r.score())).param("sp", nan(r.spreadBps())).param("sl", nan(r.slippageBps()))
                    .param("bd", nan(r.bidDepth())).param("ad", nan(r.askDepth()))
                    .param("bb", ob == null ? null : num(ob.bestBid())).param("ba", ob == null ? null : num(ob.bestAsk()))
                    .param("bat", r.orderBook() == null || r.orderBook().fetchedAt() == null ? null : r.orderBook().fetchedAt().toString())
                    .param("e", expected).param("ref", ref).param("dev", dev)
                    .param("n", r.fill() == null || r.fill().notional() == null ? null : r.fill().notional().doubleValue())
                    .param("t", now.toString()).update();
        } catch (RuntimeException e) {
            log.warn("{} {} 审核记录写入失败（不影响买卖）：{}", r.book(), r.symbol(), e.toString());
        }
    }

    private static boolean finite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    private static Double nan(double v) {
        return finite(v) ? v : null;
    }

    private static Double num(BigDecimal v) {
        return v == null || v.signum() <= 0 ? null : v.doubleValue();
    }
}
