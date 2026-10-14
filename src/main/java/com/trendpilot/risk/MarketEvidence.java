package com.trendpilot.risk;

/**
 * 系统风险的市场证据。缺失的值为 NaN，缺失时保守限制新增风险，但不凭空构造冲击或卖出价格。
 *
 * @param medianSpreadBps    成交额前 50 的标的的中位价差
 * @param stableDeviationPct 主要稳定币相对 1 美元的最大偏离（百分比）
 * @param confirmedShocks    亏损窗口内已确认的市场冲击次数（数据完整时观察到的 BTC 1h 急跌）
 */
public record MarketEvidence(double medianSpreadBps, double stableDeviationPct, int confirmedShocks) {
}
