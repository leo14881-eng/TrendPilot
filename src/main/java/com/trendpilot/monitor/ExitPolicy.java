package com.trendpilot.monitor;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.market.ExecutionSimulator;
import com.trendpilot.market.ExecutionSimulator.Fill;
import com.trendpilot.market.OrderBook;
import com.trendpilot.market.SymbolInfo;
import com.trendpilot.portfolio.Position;
import com.trendpilot.signal.EarlySignal;

/**
 * 五分钟持仓管理的退出顺序：
 * <ol>
 * <li>硬失效（下架/停止交易/黑名单/严重流动性异常）：允许亏损退出。黑名单立即执行；
 *     "不在交易"要持续 30 分钟（维护、临时暂停、接口异常不触发）；
 *     流动性异常要在两个独立的 Monitor 轮次（相隔至少 4 分钟、不超过 15 分钟）都成立才执行，
 *     避免盘口瞬间被抽空就亏损卖出</li>
 * <li>持久利润保护：可退出净利跌破底线但仍 &gt; 0 时卖出；跳空后净利 ≤ 0 只记录，不亏损卖出</li>
 * <li>普通论点失效：至少两项弱条件且连续三个有效观察代次（每出现一根新的小时 K 线才算一次，
 *     5 分钟轮次里同一份 K 线不重复计数）；有净利退出，亏损进入 LOSS_RECOVERY 观察</li>
 * <li>原始收益 ≥ 8% 复核：相对强度和加速度仍好就持有，否则退出</li>
 * <li>其他情况继续持有</li>
 * </ol>
 * 没有固定百分比止损，也没有持有时间上限。所有净利都按全数量盘口模拟成交计算。
 */
@Component
public class ExitPolicy {

    private static final MathContext MC = MathContext.DECIMAL64;

    static final java.time.Duration STRIKE_MIN_GAP = java.time.Duration.ofMinutes(4);
    static final java.time.Duration STRIKE_MAX_GAP = java.time.Duration.ofMinutes(15);
    /** "不在交易"要持续这么久才按下架卖出（维护、临时暂停、接口异常不会触发卖出）。 */
    static final java.time.Duration NOT_TRADING_CONFIRM = java.time.Duration.ofMinutes(30);
    private final java.util.Map<String, java.time.Instant> notTradingSince = new java.util.concurrent.ConcurrentHashMap<>();

    private final StrategyParams params;
    /** 每个持仓第一次观察到流动性异常的时间（进程内；重启后重新计数，偏保守）。 */
    private final java.util.Map<String, java.time.Instant> liquidityStrike = new java.util.concurrent.ConcurrentHashMap<>();

    public ExitPolicy(StrategyParams params) {
        this.params = params;
    }

    public ExitDecision evaluate(Position pos, SymbolInfo info, OrderBook book, EarlySignal signal) {
        return evaluate(pos, info, book, signal, true);
    }

    /** @param newObservation 本轮信号是否基于一根新的小时 K 线；否则走弱计数保持不变 */
    public ExitDecision evaluate(Position pos, SymbolInfo info, OrderBook book, EarlySignal signal,
                                 boolean newObservation) {
        StrategyParams.Exit e = params.exit();
        Fill fill = ExecutionSimulator.sell(book, pos.qty(), params.feeRate());
        BigDecimal net = fill.netProceeds().subtract(pos.costBasis());
        double rawRet = book.mid().divide(pos.avgEntryPrice(), MC).doubleValue() * 100 - 100;
        List<String> notes = new ArrayList<>();

        // 1. 硬失效
        String hard = null;
        boolean anomaly = false;
        if (info == null || !info.trading()) {
            // 交易所维护（Binance BREAK/HALT）、接口返回异常都会显示"不在交易"：持续 30 分钟以上才按下架处理
            if (notTradingConfirmed(pos, book.fetchedAt(), notes)) {
                return new ExitDecision(pos.symbol(), true, "HARD_INVALID_NOT_TRADING", pos, notes, net, rawRet, fill);
            }
            return new ExitDecision(pos.symbol(), false, "HOLD", pos, notes, net, rawRet, fill);
        }
        notTradingSince.remove(key(pos));
        if (params.universe().blocklist().contains(pos.symbol())) hard = "HARD_INVALID_IDENTITY";
        else if (book.spreadBps() > e.hardMaxSpreadBps() || !fill.complete()
                || fill.slippageBps() > e.hardMaxSlippageBps()
                || book.bidDepthWithin(params.review().depthBandPct()) < e.hardMinBidDepthUsdt()) {
            anomaly = true;
            hard = liquidityConfirmed(pos, book.fetchedAt(), notes) ? "HARD_INVALID_LIQUIDITY" : null;
        } else {
            liquidityStrike.remove(key(pos));
        }
        if (hard != null) {
            return new ExitDecision(pos.symbol(), true, hard, pos, notes, net, rawRet, fill);
        }
        if (anomaly) {
            // 盘口异常（价差过大、不够卖完、滑点过大、深度不足）但还没确认：这份盘口算出的净利不可信，
            // 不用它更新利润保护、最高净利或论点计数，等下一轮正常盘口
            notes.add("ABNORMAL_BOOK_HOLD");
            return new ExitDecision(pos.symbol(), false, "HOLD", pos, notes, net, rawRet, fill);
        }

        // 2. 持久利润保护
        BigDecimal principal = pos.costBasis();
        Position p = pos;
        if (!p.protectArmed() && rawRet >= e.protectArmPct() && net.signum() > 0) {
            p = p.withProtection(true, BigDecimal.ZERO, BigDecimal.ZERO);
            notes.add("PROTECT_ARM");
        }
        if (p.protectArmed()) {
            BigDecimal peak = p.peakNet().max(net);
            BigDecimal floor = protectionFloor(principal, peak, p.protectFloor(), e);
            p = p.withProtection(true, floor, peak);
            if (net.signum() > 0 && net.compareTo(floor) < 0) {
                return new ExitDecision(pos.symbol(), true, "PROFIT_PROTECTION", p, notes, net, rawRet, fill);
            }
            if (net.signum() <= 0) {
                notes.add("PROTECTION_GAPPED");
            }
        }

        // 3. 普通论点失效
        int weak = 0;
        if (signal != null) {
            if (signal.rel1h() < 0) weak++;
            if (signal.rel4h() < 0) weak++;
            if (signal.accel() < 0) weak++;
            if (signal.ret15m() < 0) weak++;
        }
        boolean validObservation = signal != null && newObservation;
        int streak = !validObservation ? p.weakStreak() : (weak >= e.decayMinWeak() ? p.weakStreak() + 1 : 0);
        // 信号恢复且不再亏损时解除 LOSS_RECOVERY
        boolean recovery = p.lossRecovery() && !(streak == 0 && net.signum() >= 0);
        p = p.withHealth(streak, recovery);
        if (streak >= e.decayConsecutive()) {
            if (net.signum() > 0) {
                return new ExitDecision(pos.symbol(), true, "THESIS_DECAY", p, notes, net, rawRet, fill);
            }
            if (!recovery) {
                p = p.withHealth(streak, true);
                notes.add("LOSS_RECOVERY");
            }
        }

        // 4. 8% 复核
        if (rawRet >= e.reviewTargetPct() && signal != null) {
            boolean stillStrong = signal.rel1h() > 0 && signal.accel() > 0;
            if (!stillStrong) {
                return new ExitDecision(pos.symbol(), true, "TARGET_REVIEW_EXIT", p, notes, net, rawRet, fill);
            }
        }

        // 5. 继续持有
        return new ExitDecision(pos.symbol(), false, "HOLD", p, notes, net, rawRet, fill);
    }

    /**
     * 保护底线 = max(本金 × 最低比例, 最高净利 − 本金 × 回吐比例, 最高净利 × 锁定比例)，只上调。
     * FastWatch 用同一公式按实时最高净利估算底线。
     */
    /** 拿不到全数量盘口时的保守折价：按最优买价再低 1% 模拟卖出（覆盖没看到的深度和滑点）。 */
    public static final double DEGRADED_HAIRCUT = 0.01;

    /**
     * 降级判断：盘口或交易状态拿不到，但有最优买价（WebSocket 或批量 bookTicker）。只做利润保护，
     * 不做论点失效、硬失效这些需要完整数据的判断；最高净利不用降级价格更新。
     * 按最优买价 × (1 − 1%) 再扣手续费算净利，跌破保护底线且仍有净利就卖，宁可早卖不错过止盈。
     */
    public ExitDecision evaluateDegraded(Position pos, double bestBid, java.time.Instant at) {
        StrategyParams.Exit e = params.exit();
        OrderBook ref = OrderBook.reference(pos.symbol(), bestBid * (1 - DEGRADED_HAIRCUT), at);
        Fill fill = ExecutionSimulator.sell(ref, pos.qty(), params.feeRate());
        BigDecimal net = fill.netProceeds().subtract(pos.costBasis());
        double rawRet = BigDecimal.valueOf(bestBid).divide(pos.avgEntryPrice(), MC).doubleValue() * 100 - 100;
        List<String> notes = new ArrayList<>();
        Position p = pos;
        if (!p.protectArmed() && rawRet >= e.protectArmPct() && net.signum() > 0) {
            p = p.withProtection(true, BigDecimal.ZERO, BigDecimal.ZERO);
            notes.add("PROTECT_ARM");
        }
        if (p.protectArmed()) {
            BigDecimal floor = protectionFloor(p.costBasis(), p.peakNet(), p.protectFloor(), e);
            p = p.withProtection(true, floor, p.peakNet());
            if (net.signum() > 0 && net.compareTo(floor) < 0) {
                notes.add("DEGRADED_QUOTE");
                return new ExitDecision(pos.symbol(), true, "PROFIT_PROTECTION", p, notes, net, rawRet, fill);
            }
        }
        return new ExitDecision(pos.symbol(), false, "HOLD", p, notes, net, rawRet, fill);
    }

    public static BigDecimal protectionFloor(BigDecimal principal, BigDecimal peak, BigDecimal current,
                                             StrategyParams.Exit e) {
        BigDecimal minFloor = pct(principal, e.protectMinFloorPct());
        BigDecimal giveback = peak.subtract(pct(principal, e.protectGivebackPct()));
        BigDecimal lock = peak.multiply(BigDecimal.valueOf(e.protectLockRatio()));
        return current.max(minFloor).max(giveback).max(lock);
    }

    private static BigDecimal pct(BigDecimal v, double pct) {
        return v.multiply(BigDecimal.valueOf(pct / 100), MC);
    }

    private boolean liquidityConfirmed(Position pos, java.time.Instant now, List<String> notes) {
        java.time.Instant first = liquidityStrike.get(key(pos));
        if (first != null && now.isBefore(first.plus(STRIKE_MAX_GAP))) {
            if (!now.isBefore(first.plus(STRIKE_MIN_GAP))) {
                liquidityStrike.remove(key(pos));
                return true;
            }
            return false; // 同一轮或快速确认：不算第二次
        }
        liquidityStrike.put(key(pos), now);
        notes.add("LIQUIDITY_STRIKE_1");
        return false;
    }

    private boolean notTradingConfirmed(Position pos, java.time.Instant now, List<String> notes) {
        java.time.Instant first = notTradingSince.putIfAbsent(key(pos), now);
        if (first == null) {
            notes.add("NOT_TRADING_PENDING");
            return false;
        }
        if (!now.isBefore(first.plus(NOT_TRADING_CONFIRM))) {
            notTradingSince.remove(key(pos));
            return true;
        }
        notes.add("NOT_TRADING_PENDING");
        return false;
    }

    private static String key(Position p) {
        return p.venue() + ":" + p.symbol() + ":" + p.openedAt() + ":" + p.costBasis();
    }
}
