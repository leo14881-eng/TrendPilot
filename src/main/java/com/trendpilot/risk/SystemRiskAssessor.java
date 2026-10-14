package com.trendpilot.risk;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.market.Kline;
import com.trendpilot.market.SymbolBars;
import com.trendpilot.market.Ticker24h;

/**
 * 系统风险：BTC 走势 + 市场广度（24h 上涨标的占比）+ 流动性 + 稳定币证据 + 亏损冻结。
 * 亏损冻结同时计入已发生的亏损退出和已确认的市场冲击。
 * 关键数据缺失时保守处理：不允许新增风险，但不凭空制造市场冲击。
 * 风险解除后的分阶段恢复见 {@link RiskRecovery}。
 */
@Component
public class SystemRiskAssessor {

    private final StrategyParams.Risk p;

    public SystemRiskAssessor(StrategyParams params) {
        this.p = params.risk();
    }

    public RiskState assess(SymbolBars btc, Ticker24h btcTicker, List<Ticker24h> universe, int recentLossExits) {
        return assess(btc, btcTicker, universe, recentLossExits, new MarketEvidence(0, 0, 0));
    }

    public RiskState assess(SymbolBars btc, Ticker24h btcTicker, List<Ticker24h> universe, int recentLossExits,
                            MarketEvidence evidence) {
        List<String> reasons = new ArrayList<>();
        if (btc == null || btcTicker == null || universe.isEmpty() || btc.hourly().size() < 4 || btc.quarter().size() < 4) {
            reasons.add("BTC_OR_BREADTH_MISSING");
            return new RiskState(Regime.RISK_OFF, false, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    recentLossExits, reasons);
        }
        List<Kline> h = btc.hourly();
        List<Kline> q = btc.quarter();
        double btc4h = (h.getLast().close() / h.get(h.size() - 4).open() - 1) * 100;
        double btc1h = (q.getLast().close() / q.get(q.size() - 4).open() - 1) * 100;
        double btc24h = btcTicker.priceChangePct();
        double breadth = universe.stream().filter(t -> t.priceChangePct() > 0).count() / (double) universe.size();

        Regime regime;
        if (btc24h <= p.riskOffBtc24hPct() || btc4h <= p.riskOffBtc4hPct() || breadth < p.riskOffBreadth()) {
            regime = Regime.RISK_OFF;
        } else if (btc24h >= p.strongBtc24hPct() && breadth >= p.strongBreadth()) {
            regime = Regime.STRONG;
        } else if (btc24h > 0 && breadth >= p.constructiveBreadth()) {
            regime = Regime.CONSTRUCTIVE;
        } else {
            regime = Regime.NEUTRAL;
        }

        boolean allowed = true;
        double budgetFactor = 1;
        if (btc1h <= p.btcShock1hPct()) {
            allowed = false;
            reasons.add("BTC_SHOCK_1H");
        }
        if (Double.isNaN(evidence.medianSpreadBps())) {
            allowed = false;
            reasons.add("LIQUIDITY_EVIDENCE_MISSING");
        } else if (evidence.medianSpreadBps() > p.liquidityStressSpreadBps()) {
            allowed = false;
            reasons.add("LIQUIDITY_STRESS");
        }
        if (Double.isNaN(evidence.stableDeviationPct())) {
            allowed = false;
            reasons.add("STABLECOIN_EVIDENCE_MISSING");
        } else if (evidence.stableDeviationPct() > p.stableDepegPct()) {
            allowed = false;
            reasons.add("STABLECOIN_DEPEG");
        }
        int losses = recentLossExits + evidence.confirmedShocks();
        if (losses >= p.lossExitsFreeze()) {
            allowed = false;
            reasons.add("LOSS_FREEZE");
        } else if (losses >= p.lossExitsHalfBudget()) {
            budgetFactor = 0.5;
            reasons.add("LOSS_HALF_BUDGET");
        }
        return new RiskState(regime, allowed, allowed ? budgetFactor : 0, btc24h, btc4h, btc1h, breadth,
                recentLossExits, reasons);
    }
}
