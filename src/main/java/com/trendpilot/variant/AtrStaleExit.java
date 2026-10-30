package com.trendpilot.variant;

import java.time.Duration;
import java.time.Instant;

/**
 * 候选退出 X1（用户 2026-10-17 批准进影子并行）：日线 ATR×1.5 止损 + 3 天仍亏损等反弹高点卖。
 * 与 4 年回测 "对照：ATR×1.5止损+3天等反弹"（docs/策略对比记录/2026-10-17-亏损2%与3天离场.md）同口径：
 * <ul>
 * <li>止损距离 = 买入时日线 ATR14 × 1.5，限制在 6%～25%（拿不到 ATR 时按 15.5%）；按 1h 收盘相对均价判断；−30% 灾难线。</li>
 * <li>3 天离场：持有满 72 小时、利润保护没启动、1h 收盘时仍亏损 → 进入等待；之后某根 1h 收盘回本就卖，
 *     或反弹过后第一根回落的 1h 收盘卖（局部高点），最多再等 24 小时。</li>
 * </ul>
 * 每根新收盘的 1h K 线只判断一次。只用于影子对比，不影响正式买卖。
 */
public final class AtrStaleExit {

    public static final String ID = "X1_ATR1.5_W72";
    static final double K = 1.5;
    static final double MIN_PCT = 6;
    static final double MAX_PCT = 25;
    static final double FLOOR_PCT = 30;
    static final Duration STALE_AFTER = Duration.ofHours(72);
    static final Duration MAX_WAIT = Duration.ofHours(24);
    static final long HOUR_MS = 3_600_000L;

    private AtrStaleExit() {
    }

    /** 买入时的止损距离（%）；ATR 缺失时取上下限中点（同回测）。 */
    public static double distancePct(double atrDailyPct) {
        double d = Double.isNaN(atrDailyPct) ? (MIN_PCT + MAX_PCT) / 2 : K * atrDailyPct;
        return Math.max(MIN_PCT, Math.min(MAX_PCT, d));
    }

    /**
     * 每个变体持仓的状态。
     *
     * @param distPct   止损距离（%），null = 还没算（第一次观察时补上）
     * @param waitSince 进入"等反弹"的时间，null = 没在等
     * @param prevClose 上一根 1h 收盘价（等待期间）
     * @param rose      等待期间是否出现过上涨的 1h 收盘
     * @param lastHour  最近一次判断用的 1h K 线开盘时间
     */
    public record State(Double distPct, Instant waitSince, Double prevClose, boolean rose, Long lastHour) {

        public static State fresh(Double distPct) {
            return new State(distPct, null, null, false, null);
        }
    }

    /** 判断结果：exit 为 null 表示继续持有。 */
    public record Step(String exit, State next) {
    }

    /**
     * @param hourClose 最近一根已收盘 1h K 线的收盘价
     * @param hourOpen  这根 K 线的开盘时间（毫秒）
     * @param net       当前全数量模拟卖出的净盈亏（U）
     */
    public static Step step(State st, Instant openedAt, Instant now, boolean protectArmed, double avgEntry,
                            double hourClose, long hourOpen, double net) {
        if (!(hourClose > 0) || !(avgEntry > 0) || (st.lastHour() != null && hourOpen <= st.lastHour())) {
            return new Step(null, st);
        }
        // 只用开仓之后才收盘的 1h K 线（与回测一致：回测在整点收盘买入，从下一根开始判断）；
        // 开仓前就已收盘的那根反映的是买入前的价格，不能拿来判断止损
        if (hourOpen + HOUR_MS <= openedAt.toEpochMilli()) return new Step(null, st);
        double ret = (hourClose / avgEntry - 1) * 100;
        double dist = st.distPct() == null ? distancePct(Double.NaN) : st.distPct();
        if (ret <= -FLOOR_PCT) return new Step("X1_FLOOR", st);
        if (ret <= -dist) return new Step("X1_STOP", st);
        if (protectArmed) return new Step(null, new State(st.distPct(), null, null, false, hourOpen));
        if (st.waitSince() == null) {
            if (Duration.between(openedAt, now).compareTo(STALE_AFTER) >= 0 && net < 0) {
                return new Step(null, new State(st.distPct(), now, hourClose, false, hourOpen));
            }
            return new Step(null, new State(st.distPct(), null, null, false, hourOpen));
        }
        if (net >= 0) return new Step("X1_STALE_EVEN", st);
        if (st.rose() && hourClose < st.prevClose()) return new Step("X1_STALE_BOUNCE", st);
        boolean rose = st.rose() || hourClose > st.prevClose();
        if (Duration.between(st.waitSince(), now).compareTo(MAX_WAIT) >= 0) return new Step("X1_STALE_TIMEOUT", st);
        return new Step(null, new State(st.distPct(), st.waitSince(), hourClose, rose, hourOpen));
    }

    /**
     * 日线 ATR14（%）：用最近 14 根已收盘日线的真实波幅均值除以 price。日线不足 14 根时返回 NaN。
     *
     * @param daily 日线（升序，只含已收盘的）：每根 {最高, 最低, 收盘}
     */
    public static double atrDailyPct(java.util.List<double[]> daily, double price) {
        if (daily.size() < 14 || !(price > 0)) return Double.NaN;
        java.util.List<double[]> last = daily.subList(daily.size() - 14, daily.size());
        double sum = 0;
        double prev = daily.size() > 14 ? daily.get(daily.size() - 15)[2] : Double.NaN;
        for (double[] k : last) {
            sum += Double.isNaN(prev) ? k[0] - k[1] : Math.max(k[0], prev) - Math.min(k[1], prev);
            prev = k[2];
        }
        return sum / 14 / price * 100;
    }
}
