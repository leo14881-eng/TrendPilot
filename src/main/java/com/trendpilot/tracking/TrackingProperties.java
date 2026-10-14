package com.trendpilot.tracking;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 信号跟踪参数（docs/信号跟踪与退出策略设计.md 附录 B）。
 *
 * @param dedupeHours        同一交易所同一标的，多少小时内的重复 EARLY 不开新样本
 * @param mainWindowHours    主观察窗口
 * @param extWindowHours     延伸窗口
 * @param bigThresholdsPct   "大涨"净收益档位，第一个以外的默认看 bigDefaultPct
 * @param swingDrawdownsPct  确认波段高点的回撤档位，主口径为 swingMainPct
 * @param swingMinGainPct    波段高点最小涨幅（低于此视为噪声）
 * @param pullbackMinPct     记录回撤事件的最小幅度
 * @param maxGapRun          连续缺失 K 线的容忍根数
 * @param maxGapRatio        累计缺失比例的容忍值
 * @param parallelRules      影子并行的候选退出规则编号（如 S1_L15），默认空：离线分析选出候选后由用户手动加入
 */
@ConfigurationProperties(prefix = "trendpilot.tracking")
public record TrackingProperties(boolean enabled, int dedupeHours, int mainWindowHours, int extWindowHours,
                                 List<Double> bigThresholdsPct, double bigDefaultPct,
                                 List<Double> swingDrawdownsPct, double swingMainPct, double swingMinGainPct,
                                 double pullbackMinPct, int maxGapRun, double maxGapRatio,
                                 int showMinSamples, int showMinSymbols, int showMinDays,
                                 int decideMinSamples, int decideMinSymbols, int decideMinWeeks,
                                 List<String> parallelRules) {
}
