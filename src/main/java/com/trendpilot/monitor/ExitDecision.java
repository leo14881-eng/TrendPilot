package com.trendpilot.monitor;

import java.math.BigDecimal;
import java.util.List;

import com.trendpilot.market.ExecutionSimulator.Fill;
import com.trendpilot.portfolio.Position;

/**
 * Monitor 对一个持仓的结论。sell=true 时按 fill 全数量模拟卖出；
 * notes 是需要记账/通知的状态变化（例如 PROTECT_ARM、PROTECTION_GAPPED、LOSS_RECOVERY）。
 */
public record ExitDecision(String symbol, boolean sell, String reason, Position updated, List<String> notes,
                           BigDecimal netPnl, double rawReturnPct, Fill fill) {
}
