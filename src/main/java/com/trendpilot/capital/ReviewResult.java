package com.trendpilot.capital;

import java.util.List;

import com.trendpilot.market.ExecutionSimulator.Fill;

/**
 * 审核结论。只有 decision == BUY 才有资格参与资金分配；action 区分新开仓与加仓。
 * gaps 是研究缺口（例如没有解锁数据），记录但不作为拒绝理由。
 */
public record ReviewResult(String symbol, Action action, Decision decision, List<String> reasons,
                           List<String> gaps, double score, double rr, double spreadBps, double slippageBps,
                           double bidDepth, double askDepth, Fill fill) {

    public boolean approved() {
        return decision == Decision.BUY;
    }
}
