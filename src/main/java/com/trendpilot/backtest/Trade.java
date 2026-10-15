package com.trendpilot.backtest;

/**
 * 一笔完整交易（开仓到全部卖完）。金额 USDT，百分比为百分点。
 *
 * @param r              这笔的风险单位（新规则 = 本笔愿意亏的钱；现行 V2 记为 80U 便于比较）
 * @param maeBeforePeak  到达持仓期间最高价之前的最大跌幅（相对买入均价，≤0）
 * @param atr1hPct       买入时 1h ATR 占价格的比例
 * @param atr1dPct       买入时日线 ATR 占价格的比例
 * @param recoveredAfter 卖出后 3 天内价格是否回到买入成本以上（判断止损误杀）
 */
public record Trade(String symbol, int entryQ, int exitQ, double cost, double pnl, String reason, int regime,
                    double r, int tranches, double maxNet, double minNet, double maeBeforePeak, double mfePct,
                    double atr1hPct, double atr1dPct, boolean recoveredAfter, boolean altGateOpen) {

    public double pct() {
        return pnl / cost * 100;
    }

    public double hours() {
        return (exitQ - entryQ) / 4.0;
    }
}
