package com.trendpilot.variant;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.trendpilot.capital.Action;
import com.trendpilot.capital.CapitalReviewer;
import com.trendpilot.capital.Decision;
import com.trendpilot.capital.ReviewInput;
import com.trendpilot.capital.ReviewResult;
import com.trendpilot.config.StrategyParams;
import com.trendpilot.market.Kline;
import com.trendpilot.monitor.ExitDecision;
import com.trendpilot.portfolio.Position;

/**
 * 参数改动在线上的两处落点，V2（已转正的参数集）和各个影子变体共用：
 * <ul>
 * <li>审核：用改动后的深度、rel1h、每笔金额审核；批准的新开仓再过 H1/H4 开仓过滤（不开仓或按半仓重新审核）。</li>
 * <li>提前离场（H2）：现行规则和山寨止损都不卖时，每根新收盘的 1h K 线判断一次。</li>
 * </ul>
 * 和回测 B0Sim 调用的是同一个 {@link VariantOverrides}。
 */
public final class VariantRules {

    public static final String ENTRY_FILTERED = "VARIANT_ENTRY_FILTER";

    private final Map<String, Long> lastHour = new ConcurrentHashMap<>();

    /** 按参数改动审核一个候选。没有改动时等于 reviewer.review(in)。 */
    public static ReviewResult review(CapitalReviewer reviewer, StrategyParams params, ReviewInput in, VariantOverrides o) {
        if (o.isEmpty()) return reviewer.review(in);
        double depth = o.minDepthUsdtOr(params.review().minDepthUsdt());
        double rel = o.minBuyRel1hOr(params.review().minBuyRel1h());
        double trade = o.tradeUsdtOr(params.capital().tradeSize());
        ReviewResult r = reviewer.review(in, depth, rel, trade);
        if (!r.approved() || r.action() != Action.BUY || !o.hasEntryRules()) return r;
        double m = o.entryMultiplier(in.risk().btc24hPct(), in.ticker().priceChangePct(), in.signal().volumeAccel());
        if (m >= 1) return r;
        if (m <= 0) {
            return new ReviewResult(r.symbol(), r.action(), Decision.REJECT, List.of(ENTRY_FILTERED), r.gaps(), r.score(), r.rr(),
                    r.spreadBps(), r.slippageBps(), r.bidDepth(), r.askDepth(), r.fill());
        }
        return reviewer.review(in, depth, rel, trade * m);
    }

    /**
     * 附加卖出判断（H2 提前离场、exit-rule）：只在出现新收盘的 1h K 线时判断一次；返回卖出原因或 null；现行规则已决定卖出、盘口异常或成交不完整时不判断。
     *
     * @param maxNet 持仓期间最大可退出净利（U），没有记录为 NaN（按本轮净利）
     */
    public String earlyExit(String book, VariantOverrides o, Position pos, ExitDecision d, double maxNet, Kline lastClosedHour,
                             Instant now) {
        if (!o.hasEarlyExit() || d.sell() || !"HOLD".equals(d.reason()) || d.notes().contains("ABNORMAL_BOOK_HOLD")
                || d.fill() == null || !d.fill().complete() || lastClosedHour == null || !(lastClosedHour.close() > 0)) {
            return null;
        }
        String key = book + ":" + pos.venue() + ":" + pos.symbol() + ":" + pos.openedAt();
        Long prev = lastHour.get(key);
        if (prev != null && lastClosedHour.openTime() <= prev) return null;
        lastHour.put(key, lastClosedHour.openTime());
        double mx = Double.isNaN(maxNet) ? d.netPnl().doubleValue() : Math.max(maxNet, d.netPnl().doubleValue());
        double ret = (lastClosedHour.close() / pos.avgEntryPrice().doubleValue() - 1) * 100;
        double cost = pos.costBasis().doubleValue();
        return o.extraExit(Duration.between(pos.openedAt(), now), d.updated().protectArmed(), mx, cost,
                d.netPnl().doubleValue() / cost * 100, ret);
    }

    /** 卖出后清掉记录。 */
    public void forget(String book, Position pos) {
        lastHour.remove(book + ":" + pos.venue() + ":" + pos.symbol() + ":" + pos.openedAt());
    }
}
