package com.trendpilot.capital;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.market.ExecutionSimulator;
import com.trendpilot.market.ExecutionSimulator.Fill;
import com.trendpilot.research.Identity;
import com.trendpilot.signal.EarlySignal;

/**
 * Capital Review：按 SYSTEM_BLOCKED → REJECT → WAIT → BUY 的顺序判定。
 * 规则对应主逻辑第六、七节；RR 来自成本情景模型，不是收益预测，跌幅情景也不是止损线。
 */
@Component
public class CapitalReviewer {

    private final StrategyParams params;

    public CapitalReviewer(StrategyParams params) {
        this.params = params;
    }

    public ReviewResult review(ReviewInput in) {
        return review(in, params.review().minDepthUsdt());
    }

    /** minDepthUsdt：盘口深度门槛（影子对照用放宽后的值重新审核，其余规则完全相同）。 */
    public ReviewResult review(ReviewInput in, double minDepthUsdt) {
        return review(in, minDepthUsdt, params.review().minBuyRel1h());
    }

    /**
     * minBuyRel1h：新开仓要求的 1h 相对 BTC 收益（%），0 = 不用（强信号对照账本 V5 用自己的门槛重新审核，其余规则完全相同）。
     * rel1h 缺失（NaN）时视为不满足，不买入。
     */
    public ReviewResult review(ReviewInput in, double minDepthUsdt, double minBuyRel1h) {
        StrategyParams.Review r = params.review();
        EarlySignal s = in.signal();
        Action action = in.position() == null ? Action.BUY : Action.ADD;
        List<String> gaps = in.research().gaps();

        // 1. 系统层面
        List<String> blocked = new ArrayList<>();
        if (!in.scanComplete()) blocked.add("SCAN_INCOMPLETE");
        if (!in.risk().newRiskAllowed()) blocked.addAll(in.risk().reasons());
        if (in.book() == null || !in.book().usable()) blocked.add("BOOK_MISSING");
        if (in.research().identity() == Identity.DATA_UNAVAILABLE) blocked.add("IDENTITY_DATA_UNAVAILABLE");
        if (!blocked.isEmpty()) {
            return result(in, action, Decision.SYSTEM_BLOCKED, blocked, gaps, Double.NaN, null);
        }

        double spread = in.book().spreadBps();
        double bidDepth = in.book().bidDepthWithin(r.depthBandPct());
        double askDepth = in.book().askDepthWithin(r.depthBandPct());
        Fill fill = ExecutionSimulator.buy(in.book(), BigDecimal.valueOf(params.capital().tradeSize()), params.feeRate());

        // 2. 硬性拒绝
        List<String> reject = new ArrayList<>();
        if (params.universe().blocklist().contains(s.symbol())) reject.add("IDENTITY_BLOCKLISTED");
        if (in.research().identity() == Identity.AMBIGUOUS) reject.add("IDENTITY_AMBIGUOUS");
        if (in.research().identity() == Identity.UNVERIFIED) reject.add("IDENTITY_UNVERIFIED");
        if (!s.early()) reject.add("EARLY_HITS_LT_" + params.early().minHits());
        if (s.score() < r.minScore()) reject.add("SCORE_LT_" + r.minScore());
        if (s.rel1h() < r.minRel1h() && s.rel4h() < r.minRel4h()) reject.add("RELATIVE_STRENGTH_WEAK");
        // 新开仓只买 1h 明显强于 BTC 的（买入端研究 2026-10-23）；0 = 不用，加仓不受影响
        if (action == Action.BUY && minBuyRel1h > 0 && !(s.rel1h() >= minBuyRel1h)) reject.add("BUY_REL1H_LT_" + minBuyRel1h);
        if (age(in.signalAsOf(), in) > r.maxSignalAgeMinutes()) reject.add("SIGNAL_STALE");
        if (age(in.book().fetchedAt(), in) > r.maxSignalAgeMinutes()) reject.add("BOOK_STALE");
        if (spread > r.maxSpreadBps()) reject.add("SPREAD_GT_" + r.maxSpreadBps() + "BPS");
        if (bidDepth < minDepthUsdt || askDepth < minDepthUsdt) reject.add("DEPTH_LT_" + minDepthUsdt);
        if (!fill.complete() || fill.slippageBps() > r.maxSlippageBps()) reject.add("SLIPPAGE_GT_" + r.maxSlippageBps() + "BPS");
        if (in.ticker().priceChangePct() > r.hot24hPct()
                && (s.rel1h() < r.minRel1h() * r.hotRel1hMultiplier() || s.accel() <= 0)) {
            reject.add("HOT_24H_NEEDS_STRONGER_SIGNAL");
        }

        // 3. 成本情景 RR
        double costsPct = 2 * params.feeRate() * 100 + 2 * Math.max(0, fill.slippageBps()) / 100;
        double upside = clamp(s.atr1hPct() * r.upsideAtrMultiple(), r.minUpsidePct(), r.maxUpsidePct()) - costsPct;
        double rr = upside / (r.riskDropPct() + costsPct);

        if (!reject.isEmpty()) {
            return result(in, action, Decision.REJECT, reject, gaps, rr, fill);
        }

        // 4. 等待条件
        List<String> wait = new ArrayList<>();
        // 追涨和加仓都用本次盘口模拟成交均价比较，不用最多旧 17 分钟的小时收盘价
        double current = fill.complete() && fill.avgPrice() != null ? fill.avgPrice().doubleValue() : s.price();
        boolean chasing = in.firstSeenPrice() != null
                && current > in.firstSeenPrice().doubleValue() * (1 + r.chaseThresholdPct() / 100);
        double minRr = chasing ? r.chaseMinRr() : r.minRr();
        if (rr < minRr) wait.add((chasing ? "CHASE_" : "") + "RR_LT_" + minRr);
        if (action == Action.BUY && in.lastExitAt() != null
                && Duration.between(in.lastExitAt(), in.now()).toMinutes() < r.reentryCooldownMinutes()) {
            wait.add("REENTRY_COOLDOWN");
        }
        // 再入场要求卖出后的新鲜、独立证据，不按旧信号买回
        if (action == Action.BUY && in.lastExitAt() != null && in.earlySinceExit() < r.reentryMinEarlyObservations()) {
            wait.add("REENTRY_EVIDENCE_" + in.earlySinceExit() + "_OF_" + r.reentryMinEarlyObservations());
        }
        // 信号本身不能全靠卖出前的涨幅：至少一项命中要完全基于卖出后的 K 线
        if (action == Action.BUY && in.lastExitAt() != null && !in.postExitHit()) {
            wait.add("REENTRY_HITS_BEFORE_EXIT");
        }
        if (action == Action.ADD) {
            wait.addAll(addConditions(in, costsPct, current));
        }
        if (!wait.isEmpty()) {
            return result(in, action, Decision.WAIT, wait, gaps, rr, fill);
        }
        return result(in, action, Decision.BUY, List.of(), gaps, rr, fill);
    }

    /** 加仓不是跌了就补：要重新通过审核、比上一笔低出足够幅度、信号间隔足够、新的 generation。 */
    private List<String> addConditions(ReviewInput in, double costsPct, double current) {
        StrategyParams.Add a = params.add();
        List<String> wait = new ArrayList<>();
        var pos = in.position();
        if (pos.tranches() >= params.capital().maxTranchesPerCoin()) {
            wait.add("MAX_TRANCHES");
            return wait;
        }
        EarlySignal s = in.signal();
        boolean strong = s.early() && s.rel1h() > 0 && s.accel() >= 0 && pos.weakStreak() == 0;
        if (!strong) wait.add("HEALTH_NOT_STRONG");
        double drop = Math.max(a.minDropPct(), a.atrMultiple() * in.signal().atr1hPct()) + costsPct;
        if (pos.tranches() >= 2) drop *= a.thirdTrancheMultiplier();
        double maxPrice = pos.lastTranchePrice().doubleValue() * (1 - drop / 100);
        if (current > maxPrice) wait.add("ADD_DROP_LT_" + Math.round(drop * 100) / 100.0 + "PCT");
        if (Duration.between(pos.lastSignalAt(), in.now()).toMinutes() < a.minSignalGapMinutes()) wait.add("ADD_SIGNAL_GAP");
        if (in.generation().equals(pos.lastGeneration())) wait.add("ADD_SAME_GENERATION");
        if (pos.lossRecovery()) wait.add("LOSS_RECOVERY");
        return wait;
    }

    private static double age(java.time.Instant t, ReviewInput in) {
        return Duration.between(t, in.now()).toSeconds() / 60.0;
    }

    private ReviewResult result(ReviewInput in, Action action, Decision d, List<String> reasons, List<String> gaps,
                                double rr, Fill fill) {
        boolean hasBook = in.book() != null && in.book().usable();
        return new ReviewResult(in.signal().symbol(), action, d, List.copyOf(reasons), gaps, in.signal().score(), rr,
                hasBook ? in.book().spreadBps() : Double.NaN, fill == null ? Double.NaN : fill.slippageBps(),
                hasBook ? in.book().bidDepthWithin(params.review().depthBandPct()) : Double.NaN,
                hasBook ? in.book().askDepthWithin(params.review().depthBandPct()) : Double.NaN, fill);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
