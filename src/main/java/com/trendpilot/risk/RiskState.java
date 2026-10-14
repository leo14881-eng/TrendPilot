package com.trendpilot.risk;

import java.util.List;

/**
 * 系统风险结论。newRiskAllowed=false 时本轮不做新的 BUY/ADD；budgetFactor 用于亏损后分阶段恢复额度。
 */
public record RiskState(Regime regime, boolean newRiskAllowed, double budgetFactor, double btc24hPct,
                        double btc4hPct, double btc1hPct, double breadth, int recentLossExits,
                        List<String> reasons) {
}
