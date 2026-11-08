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
 * @param fastwatchSince       配置化影子变体接入 FastWatch 的时间（2026-11-02 版本 43b7196 部署后启动，服务器 job_run STARTUP
 *                             2026-11-02T02:32:17Z）。转正 / 30 天评估只从 max(变体上线, 这个时间) 算起，之前的数据单列、不计入
 *                             （2026-11-08 回测与实盘对账：没有 FastWatch 时回测口径不适用）
 * @param reconciled           影子对账结论（按变体名）：转正前用同时段重跑的回测和实盘对比，研究流程确认后才把 ok 置为 true；
 *                             没写 = 没确认，不能转正
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
        @DefaultValue("") String candidatesUrl,
        @DefaultValue(FASTWATCH_SINCE) String fastwatchSince,
        java.util.Map<String, Reconciliation> reconciled) {

    /** 影子变体接入 FastWatch 的上线时间（见 fastwatchSince）。 */
    public static final String FASTWATCH_SINCE = "2026-11-02T02:32:17Z";
    /** 执行分项（实盘 − 同时段回测，每千 U 每笔）低于这个值不能转正，先查执行。 */
    public static final double MIN_EXECUTION_GAP_PER_K = -2.0;

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public OptimizeProperties {
        reconciled = reconciled == null ? java.util.Map.of() : java.util.Map.copyOf(reconciled);
    }

    /** 旧写法（测试用）：FastWatch 起点用默认值，没有对账结论。 */
    public OptimizeProperties(boolean autoPromote, boolean realMoneyAutoPromote, int minDays, int earlyStopDays, double earlyStopDdRatio,
                              int minClosed, double promoteDdRatio, double retireDdRatio, int maxActive, int formalChangeDays,
                              String lastFormalChange, String candidatesUrl) {
        this(autoPromote, realMoneyAutoPromote, minDays, earlyStopDays, earlyStopDdRatio, minClosed, promoteDdRatio, retireDdRatio,
                maxActive, formalChangeDays, lastFormalChange, candidatesUrl, FASTWATCH_SINCE, null);
    }

    public java.time.Instant fastwatchSinceInstant() {
        return java.time.Instant.parse(fastwatchSince == null || fastwatchSince.isBlank() ? FASTWATCH_SINCE : fastwatchSince);
    }

    /** 某个变体的影子对账结论；没写返回"没确认"。 */
    public Reconciliation reconciliation(String variant) {
        // 配置的键大小写可能被改写（[V5] / v5），按名字不分大小写找
        return reconciled.entrySet().stream().filter(e -> e.getKey().replace("[", "").replace("]", "").equalsIgnoreCase(variant))
                .map(java.util.Map.Entry::getValue).findFirst().orElse(Reconciliation.NONE);
    }

    /**
     * 一个变体的影子对账（docs/策略对比记录/2026-11-08-回测与实盘对账.md 的方法，研究入口 ReconStudy）。
     *
     * @param executionReconciled 研究流程确认过"实盘和同时段回测的执行一致"才是 true（默认 false）
     * @param executionGapPerK    执行分项：实盘 − 同时段回测，U / 每千 U 每笔（负数 = 实盘执行更差）；没测过为空
     * @param evidence            对账记录链接
     */
    public record Reconciliation(Boolean executionReconciled, Double executionGapPerK, String evidence) {
        public static final Reconciliation NONE = new Reconciliation(false, null, null);

        public boolean ok() {
            return Boolean.TRUE.equals(executionReconciled);
        }
    }
}
