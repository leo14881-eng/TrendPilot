package com.trendpilot.config;

import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全部策略参数，取值见 application.yml 的 trendpilot.strategy。
 * 每条决策都会记录 version，参数只能人工修改。
 */
@ConfigurationProperties(prefix = "trendpilot.strategy")
public record StrategyParams(
        String version,
        Universe universe,
        Early early,
        Review review,
        Capital capital,
        Add add,
        Exit exit,
        Risk risk,
        double feeRate) {

    public record Universe(String quoteAsset, Set<String> excludedBaseAssets, Set<String> tokenizedStockBaseAssets,
                           Set<String> blocklist, double maxMissingRatio) {
    }

    /** EARLY：六个条件至少命中 minHits 个。收益单位均为百分点。 */
    public record Early(int minHits, double rel1h, double rel4h, double accel, double volumeAccel,
                        double compression, double momentumRel1h) {
    }

    public record Review(double minScore, double minRel1h, double minRel4h, double maxSignalAgeMinutes,
                         double maxSpreadBps, double depthBandPct, double minDepthUsdt, double maxSlippageBps,
                         double minRr, double chaseThresholdPct, double chaseMinRr, double hot24hPct,
                         double hotRel1hMultiplier, double riskDropPct, double upsideAtrMultiple,
                         double minUpsidePct, double maxUpsidePct, int reentryCooldownMinutes,
                         int reentryMinEarlyObservations) {
    }

    public record Capital(double hardCap, double normalPool, double strategicReserve, double tradeSize,
                          int maxTranchesPerCoin, List<RegimeLimit> regimeLimits) {

        public double deployPct(String regime) {
            return regimeLimits.stream().filter(r -> r.regime().equals(regime)).findFirst()
                    .map(RegimeLimit::pct).orElse(0.6);
        }
    }

    public record RegimeLimit(String regime, double pct) {
    }

    /** 加仓：最低降价幅度 = max(minDropPct, atrMultiple × 1h 平均波幅) + 往返成本；第三笔再乘 thirdTrancheMultiplier。 */
    public record Add(double minDropPct, double atrMultiple, double thirdTrancheMultiplier, int minSignalGapMinutes) {
    }

    public record Exit(double protectArmPct, double protectMinFloorPct, double protectGivebackPct, double protectLockRatio,
                       double reviewTargetPct, int decayMinWeak, int decayConsecutive,
                       double hardMaxSpreadBps, double hardMaxSlippageBps, double hardMinBidDepthUsdt) {
    }

    public record Risk(double btcShock1hPct, double riskOffBtc24hPct, double riskOffBtc4hPct,
                       double riskOffBreadth, double constructiveBreadth, double strongBreadth,
                       double strongBtc24hPct, int lossExitsFreeze, int lossExitsHalfBudget,
                       int lossWindowHours, double liquidityStressSpreadBps, double stableDepegPct,
                       int recoveryObservations, int recoveryHalfBudgetObservations,
                       double underwaterLossPct) {
    }
}
