package com.trendpilot.research;

import java.util.List;

import com.trendpilot.market.Venue;

/**
 * 一个标的的研究结论。gaps 是资料缺口（记录但不作为拒绝理由），labels 是研究标签（如 LOW_FLOAT、24h 涨幅分类）。
 * capitalReady 永远为 false：长期研究不直接给买单，战术买入另走 Capital Review。
 */
public record ResearchRecord(Venue venue, String symbol, String coinId, Identity identity, double marketCap, double fdv,
                             double circulatingRatio, double tvl, double fees24h, double revenue24h,
                             List<String> labels, List<String> gaps) {

    public boolean capitalReady() {
        return false;
    }

    public String verdict() {
        if (identity == Identity.AMBIGUOUS) return "IDENTITY_PROBLEM";
        return gaps.isEmpty() ? "USER_REVIEW_REQUIRED" : "RESEARCH_GAPS";
    }

    /** 研究资料还没加载时的占位结论。 */
    public static ResearchRecord unavailable(Venue venue, String symbol) {
        return new ResearchRecord(venue, symbol, null, Identity.DATA_UNAVAILABLE, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, List.of(), List.of("IDENTITY_DATA_UNAVAILABLE"));
    }
}
