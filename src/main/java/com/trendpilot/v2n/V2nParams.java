package com.trendpilot.v2n;

/**
 * 新版 V2 规则（docs/V2交易规则重设计.md）的全部参数。数字是初值，回测按事先登记的网格校准；
 * 开关用于消融（去掉一个部件看它是否真的有用）。
 *
 * @param riskUnit       R：每笔最多亏的钱（U）
 * @param stopAtr        止损距离 = stopAtr × 1h ATR%，限制在 [minStopPct, maxStopPct]
 * @param useDailyAtr    止损距离改用日线 ATR（止损调查 3.1 的对照）
 * @param fixedSize      &gt;0 时每笔固定金额（消融：不按风险定仓位）
 * @param altWeakPct     山寨 24h 中位数 ≤ 此值不买
 * @param altSemiPct     山寨 24h 中位数 ≤ 此值每笔风险减半
 * @param stopMode       L1 触发方式：REALTIME 实时价跌破、CONFIRM 15m 收盘跌破、HOUR 1h 收盘跌破、OFF 不设价格止损
 * @param timeStopHours  L2：持有满这么久、从未到过 +1R、仍亏损就卖；0 关闭
 * @param decayMode      L3 论点失效：RAISE 止损上移到 −0.5R（7.7）；SELL 直接卖；PROFIT_ONLY 赚钱才卖（现行）；NONE 不处理
 * @param exitMode       NEW：+1R 保本、部分止盈、ATR 跟踪；B0：沿用现行利润保护
 * @param addMode        WINNERS 只加赚钱的仓位；DROPS 现行跌了才加；NONE 不加
 * @param systemMode     BTC 急跌/山寨熔断时：TIGHTEN 未保本仓止损上移到 −0.5R；NONE 不动；SELL 全部卖出
 */
public record V2nParams(
        String id,
        double riskUnit,
        double stopAtr,
        double minStopPct,
        double maxStopPct,
        boolean useDailyAtr,
        double minSize,
        double maxSize,
        double fixedSize,
        boolean altGate,
        double altWeakPct,
        double altSemiPct,
        double altWeakTrend,
        double altSemiTrend,
        boolean trendGate,
        boolean extensionGate,
        double extensionMax,
        double extensionResume,
        int extensionWaitHours,
        String stopMode,
        int timeStopHours,
        String decayMode,
        String exitMode,
        double breakevenR,
        double partialR,
        double partialFraction,
        double trailAtr,
        double tightenR,
        double tightTrailAtr,
        String addMode,
        double addFraction,
        int maxAdds,
        double maxPositionCost,
        double addMaxLossR,
        double portfolioRiskR,
        double freezeLossR,
        int freezeHours,
        String systemMode,
        int stopCooldownHours,
        int stopBanCount,
        int stopBanDays) {

    /** 设计文档里的初值。 */
    public static V2nParams base() {
        return new V2nParams("N", 80, 3, 4, 12, false, 500, 2000, 0,
                true, -3, -1.5, 0.30, 0.45,
                true, true, 2.5, 1.5, 3,
                "CONFIRM", 24, "RAISE",
                "NEW", 1, 2, 1.0 / 3, 3, 5, 2,
                "WINNERS", 0.5, 2, 3000, 0.5,
                8, 4, 24, "TIGHTEN", 4, 2, 7);
    }

    public V2nParams with(String newId, java.util.function.UnaryOperator<Builder> f) {
        return f.apply(new Builder(this)).id(newId).build();
    }

    /** 逐项修改参数（回测网格和消融用）。 */
    public static final class Builder {
        private Object[] v;
        private static final java.lang.reflect.RecordComponent[] C = V2nParams.class.getRecordComponents();

        Builder(V2nParams p) {
            v = new Object[C.length];
            try {
                for (int i = 0; i < C.length; i++) v[i] = C[i].getAccessor().invoke(p);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        public Builder set(String name, Object value) {
            for (int i = 0; i < C.length; i++) {
                if (C[i].getName().equals(name)) {
                    v[i] = value;
                    return this;
                }
            }
            throw new IllegalArgumentException("没有这个参数：" + name);
        }

        Builder id(String id) {
            return set("id", id);
        }

        V2nParams build() {
            try {
                Class<?>[] types = java.util.Arrays.stream(C).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
                return V2nParams.class.getDeclaredConstructor(types).newInstance(v);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
