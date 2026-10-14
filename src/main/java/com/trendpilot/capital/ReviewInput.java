package com.trendpilot.capital;

import java.math.BigDecimal;
import java.time.Instant;

import com.trendpilot.market.OrderBook;
import com.trendpilot.market.Ticker24h;
import com.trendpilot.portfolio.Position;
import com.trendpilot.research.ResearchRecord;
import com.trendpilot.risk.RiskState;
import com.trendpilot.signal.EarlySignal;

/**
 * 一次审核需要的全部输入，来自同一个 generation。
 *
 * @param firstSeenPrice 首次进入 EARLY 时的价格，没有记录时为 null
 * @param position 已有持仓（有则按 ADD 审核），没有为 null
 * @param lastExitAt 最近一次卖出时间，没有为 null
 * @param research 研究结论（身份、供给、价值捕获、缺口）
 * @param earlySinceExit 最近一次卖出之后独立 generation 的 EARLY 观察次数（含本轮）；没有卖出过时不使用
 * @param postExitHit 命中条件里至少有一项完全基于卖出后才开始的 K 线（再入场要求）；没有卖出过时为 true
 */
public record ReviewInput(String generation, Instant signalAsOf, Instant now, boolean scanComplete,
                          EarlySignal signal, Ticker24h ticker, OrderBook book, RiskState risk,
                          BigDecimal firstSeenPrice, Position position, Instant lastExitAt,
                          ResearchRecord research, int earlySinceExit, boolean postExitHit) {

    public ReviewInput(String generation, Instant signalAsOf, Instant now, boolean scanComplete,
                       EarlySignal signal, Ticker24h ticker, OrderBook book, RiskState risk,
                       BigDecimal firstSeenPrice, Position position, Instant lastExitAt,
                       ResearchRecord research, int earlySinceExit) {
        this(generation, signalAsOf, now, scanComplete, signal, ticker, book, risk, firstSeenPrice, position,
                lastExitAt, research, earlySinceExit, true);
    }
}
