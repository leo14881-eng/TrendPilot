package com.trendpilot.capital;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.market.ExecutionSimulator;
import com.trendpilot.market.ExecutionSimulator.Fill;
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
        StrategyParams.Review r = params.review();
        EarlySignal s = in.signal();
        Action action = in.position() == null ? Action.BUY : Action.ADD;
        List<String> gaps = List.of("UNLOCK_SUPPLY_DATA_NOT_COVERED");

        // 1. 系统层面
        List<String> blocked = new ArrayList<>();
        if (!in.scanComplete()) blocked.add("SCAN_INCOMPLETE");
        if (!in.risk().newRiskAllowed()) blocked.addAll(in.risk().reasons());
        if (in.book() == null || !in.book().usable()) blocked.add("BOOK_MISSING");
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
        if (!s.early()) reject.add("EARLY_HITS_LT_" + params.early().minHits());
        if (s.score() < r.minScore()) reject.add("SCORE_LT_" + r.minScore());
        if (s.rel1h() < r.minRel1h() && s.rel4h() < r.minRel4h()) reject.add("RELATIVE_STRENGTH_WEAK");
        if (age(in.signalAsOf(), in) > r.maxSignalAgeMinutes()) reject.add("SIGNAL_STALE");
        if (age(in.book().fetchedAt(), in) > r.maxSignalAgeMinutes()) reject.add("BOOK_STALE");
        if (spread > r.maxSpreadBps()) reject.add("SPREAD_GT_" + r.maxSpreadBps() + "BPS");
        if (bidDepth < r.minDepthUsdt() || askDepth < r.minDepthUsdt()) reject.add("DEPTH_LT_" + r.minDepthUsdt());
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
        boolean chasing = in.firstSeenPrice() != null
                && s.price() > in.firstSeenPrice().doubleValue() * (1 + r.chaseThresholdPct() / 100);
        double minRr = chasing ? r.chaseMinRr() : r.minRr();
        if (rr < minRr) wait.add((chasing ? "CHASE_" : "") + "RR_LT_" + minRr);
        if (action == Action.BUY && in.lastExitAt() != null
                && Duration.between(in.lastExitAt(), in.now()).toMinutes() < r.reentryCooldownMinutes()) {
            wait.add("REENTRY_COOLDOWN");
        }
        if (action == Action.ADD) {
            wait.addAll(addConditions(in, costsPct));
        }
        if (!wait.isEmpty()) {
            return result(in, action, Decision.WAIT, wait, gaps, rr, fill);
        }
        return result(in, action, Decision.BUY, List.of(), gaps, rr, fill);
    }

    /** 加仓不是跌了就补：要重新通过审核、比上一笔低出足够幅度、信号间隔足够、新的 generation。 */
    private List<String> addConditions(ReviewInput in, double costsPct) {
        StrategyParams.Add a = params.add();
        List<String> wait = new ArrayList<>();
        var pos = in.position();
        if (pos.tranches() >= params.capital().maxTranchesPerCoin()) {
            wait.add("MAX_TRANCHES");
            return wait;
        }
        double drop = Math.max(a.minDropPct(), a.atrMultiple() * in.signal().atr1hPct()) + costsPct;
        if (pos.tranches() >= 2) drop *= a.thirdTrancheMultiplier();
        double maxPrice = pos.lastTranchePrice().doubleValue() * (1 - drop / 100);
        if (in.signal().price() > maxPrice) wait.add("ADD_DROP_LT_" + Math.round(drop * 100) / 100.0 + "PCT");
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
