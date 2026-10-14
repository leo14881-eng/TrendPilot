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
 */
public record ReviewInput(String generation, Instant signalAsOf, Instant now, boolean scanComplete,
                          EarlySignal signal, Ticker24h ticker, OrderBook book, RiskState risk,
                          BigDecimal firstSeenPrice, Position position, Instant lastExitAt,
                          ResearchRecord research) {
}
