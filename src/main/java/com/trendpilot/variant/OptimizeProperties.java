package com.trendpilot.variant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 自动优化（trendpilot.optimize）：变体的自动转正、淘汰，以及每周读取回测候选。安全边界见 {@link AutoPromoter}。
 *
 * @param autoPromote          已批准自动转正（只对影子账本生效），默认开
 * @param realMoneyAutoPromote 以后有真钱账本时，是否允许自动改真钱规则；默认关，必须用户明确同意才能打开
 * @param minDays              变体上线满多少天才评估转正 / 淘汰（用户 2026-10-26："一个版本测试完整一个月"）
 * @param earlyStopDays        提前停止的最早天数：满这么多天、回撤已超过 V2 × earlyStopDdRatio 就停开新仓
 * @param minClosed            转正要求的最少平仓笔数
 * @param promoteDdRatio       转正要求同期最大回撤 ≤ V2 × 这个倍数
 * @param retireDdRatio        满 minDays 后回撤 > V2 × 这个倍数就停开新仓
 * @param maxActive            同时最多几个正在开新仓的变体
 * @param formalChangeDays     正式规则（V2 参数集）最短多少天换一次
 * @param lastFormalChange     上一次正式改动的日期（V2 在 2026-10-23 恢复基准）
 * @param candidatesUrl        每周自动回测的 candidates.json（GitHub 公开 raw 文件）；空 = 不读取
 */
@ConfigurationProperties(prefix = "trendpilot.optimize")
public record OptimizeProperties(
        @DefaultValue("true") boolean autoPromote,
        @DefaultValue("false") boolean realMoneyAutoPromote,
        @DefaultValue("30") int minDays,
        @DefaultValue("14") int earlyStopDays,
        @DefaultValue("1.5") double earlyStopDdRatio,
        @DefaultValue("20") int minClosed,
        @DefaultValue("1.1") double promoteDdRatio,
        @DefaultValue("1.3") double retireDdRatio,
        @DefaultValue("3") int maxActive,
        @DefaultValue("14") int formalChangeDays,
        @DefaultValue("2026-10-23") String lastFormalChange,
        @DefaultValue("") String candidatesUrl) {
}
