package com.trendpilot.monitor;

import java.time.Duration;
import java.time.Instant;

/**
 * 上涨逻辑失效时的亏损离场（用户 2026-10-17："上涨逻辑失效，可以止损卖出，选相对高点"）。
 * 原规则：论点失效时有净利就卖，亏损只标记 LOSS_RECOVERY 继续持有（会一直套着）。新规则：处于 LOSS_RECOVERY 的持仓
 * 等一个相对高点卖出——
 * <ul>
 * <li>之后某根 1h 收盘回本（可退出净利 ≥ 0）就卖；</li>
 * <li>或出现过上涨的 1h 收盘之后，第一根回落的 1h 收盘卖（反弹的局部高点）；</li>
 * <li>最多等 {@link #MAX_WAIT}，到时按 1h 收盘卖；</li>
 * <li>等待期间信号恢复、LOSS_RECOVERY 解除，就取消等待，继续按原规则持有。</li>
 * </ul>
 * 每根新收盘的 1h K 线只判断一次。线上（MonitorJob / V2D15）和回测（B0Sim）用同一份判断。
 */
public final class DecayBounceExit {

    public static final String REASON = "DECAY_STOP";
    public static final Duration MAX_WAIT = Duration.ofHours(24);

    private DecayBounceExit() {
    }

    /**
     * @param waitSince 开始等待的时间，null = 没在等
     * @param prevClose 上一根 1h 收盘价
     * @param rose      等待期间是否出现过上涨的 1h 收盘
     * @param lastHour  最近一次判断用的 1h K 线开盘时间
     */
    public record State(Instant waitSince, Double prevClose, boolean rose, Long lastHour) {

        public static final State NONE = new State(null, null, false, null);
    }

    public record Step(boolean sell, State next) {
    }

    /**
     * @param lossRecovery 持仓当前是否处于 LOSS_RECOVERY（论点失效但亏损）
     * @param hourClose    最近一根已收盘 1h K 线收盘价
     * @param hourOpen     这根 K 线的开盘时间（毫秒）
     * @param net          当前全数量模拟卖出的可退出净利（U）
     */
    public static Step step(State st, boolean lossRecovery, Instant now, double hourClose, long hourOpen, double net) {
        if (!(hourClose > 0) || (st.lastHour() != null && hourOpen <= st.lastHour())) return new Step(false, st);
        if (!lossRecovery) return new Step(false, new State(null, null, false, hourOpen)); // 信号恢复：取消等待
        if (st.waitSince() == null) return new Step(false, new State(now, hourClose, false, hourOpen));
        if (net >= 0) return new Step(true, st);
        if (st.rose() && hourClose < st.prevClose()) return new Step(true, st);
        if (Duration.between(st.waitSince(), now).compareTo(MAX_WAIT) >= 0) return new Step(true, st);
        return new Step(false, new State(st.waitSince(), hourClose, st.rose() || hourClose > st.prevClose(), hourOpen));
    }
}
