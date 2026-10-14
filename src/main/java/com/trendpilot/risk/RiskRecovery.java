package com.trendpilot.risk;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.job.AppState;

/**
 * 风险解除后的分阶段恢复，以及市场冲击的确认记录。
 * <ul>
 * <li>一旦本轮不允许新增风险，进入恢复期；之后需要连续 recoveryObservations 轮独立的正常观察（不同 generation）
 *     才允许新增，再连续 recoveryHalfBudgetObservations 轮只用一半额度，之后完全恢复。</li>
 * <li>数据完整时观察到的 BTC 1h 急跌记为已确认的市场冲击，在亏损窗口内计入亏损冻结。数据缺失不算冲击。
 *     连续轮次（间隔不超过 90 分钟）的急跌合并为一次冲击。</li>
 * </ul>
 */
@Component
public class RiskRecovery {

    static final String RECOVERING = "risk:recovering";
    static final String STREAK = "risk:normalStreak";
    static final String LAST_GEN = "risk:lastGeneration";
    static final String SHOCKS = "risk:shocks";
    static final Duration SAME_EVENT = Duration.ofMinutes(90);

    private final AppState state;
    private final StrategyParams.Risk p;

    public RiskRecovery(AppState state, StrategyParams params) {
        this.state = state;
        this.p = params.risk();
    }

    /** 亏损窗口内已确认的市场冲击次数。 */
    public int confirmedShocks(Instant now) {
        return (int) shockTimes().stream()
                .filter(t -> Duration.between(t, now).toHours() < p.lossWindowHours()).count();
    }

    /** 记录本轮是否确认了市场冲击（只在 BTC 数据完整时调用）。 */
    public void recordShockIfAny(RiskState raw, Instant now) {
        if (!Double.isNaN(raw.btc1hPct()) && raw.btc1hPct() <= p.btcShock1hPct()) {
            List<Instant> times = new ArrayList<>(shockTimes());
            times.removeIf(t -> Duration.between(t, now).toHours() >= p.lossWindowHours());
            // 连续几轮 Discovery 都看到急跌算同一次冲击：上一次在 90 分钟内就只更新时间，不重复计数
            Instant last = times.isEmpty() ? null : times.getLast();
            if (last != null && !now.isBefore(last) && Duration.between(last, now).compareTo(SAME_EVENT) <= 0) {
                times.set(times.size() - 1, now);
            } else if (!times.contains(now)) {
                times.add(now);
            }
            state.put(SHOCKS, times.stream().map(Instant::toString).collect(Collectors.joining(",")));
        }
    }

    /** 在原始结论上叠加恢复期限制。同一 generation 重复调用不重复计数。 */
    public RiskState apply(RiskState raw, String generation) {
        if (!raw.newRiskAllowed()) {
            state.put(RECOVERING, "true");
            state.put(STREAK, "0");
            state.put(LAST_GEN, generation);
            return raw;
        }
        if (!"true".equals(state.get(RECOVERING).orElse("false"))) {
            return raw;
        }
        int streak = Integer.parseInt(state.get(STREAK).orElse("0"));
        if (!generation.equals(state.get(LAST_GEN).orElse(""))) {
            streak++;
            state.put(STREAK, String.valueOf(streak));
            state.put(LAST_GEN, generation);
        }
        List<String> reasons = new ArrayList<>(raw.reasons());
        if (streak < p.recoveryObservations()) {
            reasons.add("RISK_RECOVERY_OBSERVING_" + streak + "_OF_" + p.recoveryObservations());
            return with(raw, false, 0, reasons);
        }
        if (streak < p.recoveryObservations() + p.recoveryHalfBudgetObservations()) {
            reasons.add("RISK_RECOVERY_HALF_BUDGET");
            return with(raw, true, Math.min(0.5, raw.budgetFactor()), reasons);
        }
        state.put(RECOVERING, "false");
        return raw;
    }

    private static RiskState with(RiskState r, boolean allowed, double factor, List<String> reasons) {
        return new RiskState(r.regime(), allowed, factor, r.btc24hPct(), r.btc4hPct(), r.btc1hPct(), r.breadth(),
                r.recentLossExits(), List.copyOf(reasons));
    }

    private List<Instant> shockTimes() {
        String v = state.get(SHOCKS).orElse("");
        if (v.isBlank()) return List.of();
        return Arrays.stream(v.split(",")).map(Instant::parse).toList();
    }
}
