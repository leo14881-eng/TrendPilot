package com.trendpilot.btcdaily;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 计划日可用的市场与宏观数据（只用计划生成时已公布的数据）。null 表示缺失。
 *
 * @param event      当天的宏观事件（"美联储议息" / "CPI 发布"），没有为 null
 * @param fundingPct 昨天平均资金费率（%/8h）
 * @param fearGreed  昨天恐惧贪婪指数
 * @param etfDay     最近已公布的 ETF 交易日
 * @param etfFlowM   该交易日 ETF 净流入（百万美元，流出为负）
 * @param etf3dM     最近 3 个已公布交易日合计
 * @param us10yChg   美国 10 年期利率 5 个观测日变化（百分点）
 * @param dollarChg  美元指数 5 个观测日变化（%）
 * @param oiUsd      BTC 合约持仓量（美元，只记录）
 * @param gaps       缺失的数据源
 */
public record MarketContext(LocalDate day, String event, Double fundingPct, Double fearGreed, LocalDate etfDay,
                            Double etfFlowM, Double etf3dM, Double us10yChg, Double dollarChg, Double oiUsd,
                            List<String> gaps) {

    public static MarketContext empty(LocalDate day) {
        return new MarketContext(day, null, null, null, null, null, null, null, null, null, List.of());
    }

    /** 过滤结论：是否不开新一轮、新一轮金额乘数、原因。 */
    public record Gate(boolean skipNew, double multiplier, List<String> reasons) {

        public static final Gate NONE = new Gate(false, 1, List.of());

        public String text() {
            return String.join("；", reasons);
        }
    }

    public Gate gate(boolean downtrend, BtcDailyProperties.Filters f) {
        List<String> reasons = new ArrayList<>();
        boolean skip = false;
        int halves = 0;
        if (downtrend) {
            skip = true;
            reasons.add("下跌趋势（昨收 < EMA20 < EMA" + f.emaLong() + "，EMA20 向下）：不开新一轮");
        }
        if (event != null) {
            skip = true;
            reasons.add(event + "日：不开新一轮");
        }
        if (etf3dM != null && etf3dM <= -f.etfOutflow3dM()) {
            skip = true;
            reasons.add(String.format("ETF 近 3 个交易日净流出 %.0f 百万美元：不开新一轮", -etf3dM));
        }
        if (etfFlowM != null && etfFlowM <= -f.etfOutflowDayM()) {
            halves++;
            reasons.add(String.format("ETF %s 净流出 %.0f 百万美元：减半", etfDay, -etfFlowM));
        }
        if (fundingPct != null && fundingPct >= f.fundingMaxPct()) {
            halves++;
            reasons.add(String.format("资金费率 %.4f%%（多头拥挤）：减半", fundingPct));
        }
        if (fearGreed != null && fearGreed >= f.fearGreedMax()) {
            halves++;
            reasons.add(String.format("恐惧贪婪指数 %.0f（极度贪婪）：减半", fearGreed));
        }
        if (us10yChg != null && us10yChg >= f.us10yRisePp()) {
            halves++;
            reasons.add(String.format("美国 10 年期利率 5 日上升 %.2f 个百分点：减半", us10yChg));
        }
        if (dollarChg != null && dollarChg >= f.dollarRisePct()) {
            halves++;
            reasons.add(String.format("美元指数 5 日上涨 %.2f%%：减半", dollarChg));
        }
        double m = Math.max(f.minMultiplier(), Math.pow(0.5, halves));
        return new Gate(skip, m, List.copyOf(reasons));
    }
}
