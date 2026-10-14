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
 * <li>硬失效（下架/停止交易/黑名单/严重流动性异常）：允许亏损退出</li>
 * <li>持久利润保护：可退出净利跌破底线但仍 &gt; 0 时卖出；跳空后净利 ≤ 0 只记录，不亏损卖出</li>
 * <li>普通论点失效：至少两项弱条件且连续三个观察；有净利退出，亏损进入 LOSS_RECOVERY 观察</li>
 * <li>原始收益 ≥ 8% 复核：相对强度和加速度仍好就持有，否则退出</li>
 * <li>其他情况继续持有</li>
 * </ol>
 * 没有固定百分比止损，也没有持有时间上限。所有净利都按全数量盘口模拟成交计算。
 */
@Component
public class ExitPolicy {

    private static final MathContext MC = MathContext.DECIMAL64;

    private final StrategyParams params;

    public ExitPolicy(StrategyParams params) {
        this.params = params;
    }

    public ExitDecision evaluate(Position pos, SymbolInfo info, OrderBook book, EarlySignal signal) {
        StrategyParams.Exit e = params.exit();
        Fill fill = ExecutionSimulator.sell(book, pos.qty(), params.feeRate());
        BigDecimal net = fill.netProceeds().subtract(pos.costBasis());
        double rawRet = book.mid().divide(pos.avgEntryPrice(), MC).doubleValue() * 100 - 100;
        List<String> notes = new ArrayList<>();

        // 1. 硬失效
        String hard = null;
        if (info == null || !info.trading()) hard = "HARD_INVALID_NOT_TRADING";
        else if (params.universe().blocklist().contains(pos.symbol())) hard = "HARD_INVALID_IDENTITY";
        else if (book.spreadBps() > e.hardMaxSpreadBps() || !fill.complete()
                || fill.slippageBps() > e.hardMaxSlippageBps()
                || book.bidDepthWithin(params.review().depthBandPct()) < e.hardMinBidDepthUsdt()) {
            hard = "HARD_INVALID_LIQUIDITY";
        }
        if (hard != null) {
            return new ExitDecision(pos.symbol(), true, hard, pos, notes, net, rawRet, fill);
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
            BigDecimal minFloor = pct(principal, e.protectMinFloorPct());
            BigDecimal giveback = peak.subtract(pct(principal, e.protectGivebackPct()));
            BigDecimal floor = p.protectFloor().max(minFloor.max(giveback)); // 只上调
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
        boolean validObservation = signal != null;
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

    private static BigDecimal pct(BigDecimal v, double pct) {
        return v.multiply(BigDecimal.valueOf(pct / 100), MC);
    }
}
