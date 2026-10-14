package com.trendpilot.capital;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.risk.Regime;
import com.trendpilot.risk.RiskState;

/**
 * 资金分配：BUY 与 ADD 一起按评分排序争取预算。
 * 可用额度 = min(资金池, 硬顶 × 市场状态比例) × 亏损恢复系数 − 已占用成本 + min(0, 已实现盈亏) + min(0, 浮动盈亏)。
 * 资金池平时是普通资金池；只有行情 STRONG 时才加上战略储备（用户 2026-10-15 决定）。
 * 浮盈不扩大资金池，已实现亏损和浮亏都会缩小资金池；资金不足就等待，不为腾资金卖仓。
 */
@Component
public class CapitalAllocator {

    private final StrategyParams.Capital p;

    public CapitalAllocator(StrategyParams params) {
        this.p = params.capital();
    }

    public record Allocation(List<ReviewResult> funded, List<ReviewResult> unfunded, double available) {
    }

    public double available(RiskState risk, double deployedCost, double realizedPnl) {
        return available(risk, deployedCost, realizedPnl, 0);
    }

    public double available(RiskState risk, double deployedCost, double realizedPnl, double unrealizedPnl) {
        double pool = risk.regime() == Regime.STRONG ? p.normalPool() + p.strategicReserve() : p.normalPool();
        double limit = Math.min(pool, p.hardCap() * p.deployPct(risk.regime().name())) * risk.budgetFactor();
        return limit - deployedCost + Math.min(0, realizedPnl) + Math.min(0, unrealizedPnl);
    }

    public Allocation allocate(List<ReviewResult> approved, RiskState risk, double deployedCost, double realizedPnl) {
        return allocate(approved, risk, deployedCost, realizedPnl, 0);
    }

    public Allocation allocate(List<ReviewResult> approved, RiskState risk, double deployedCost, double realizedPnl,
                               double unrealizedPnl) {
        double available = available(risk, deployedCost, realizedPnl, unrealizedPnl);
        List<ReviewResult> funded = new ArrayList<>();
        List<ReviewResult> unfunded = new ArrayList<>();
        List<ReviewResult> ranked = approved.stream()
                .sorted(Comparator.comparingDouble(ReviewResult::score).reversed()
                        .thenComparing(Comparator.comparingDouble(ReviewResult::rr).reversed()))
                .toList();
        double left = available;
        for (ReviewResult r : ranked) {
            double cost = r.fill().totalCost().doubleValue();
            if (cost <= left) {
                funded.add(r);
                left -= cost;
            } else {
                unfunded.add(r);
            }
        }
        return new Allocation(funded, unfunded, available);
    }
}
