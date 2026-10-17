package com.trendpilot.observation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.trendpilot.capital.ReviewResult;
import com.trendpilot.market.BarsLoader;
import com.trendpilot.market.Markets;
import com.trendpilot.market.OrderBook;
import com.trendpilot.market.SymbolBars;
import com.trendpilot.market.Venue;
import com.trendpilot.market.VenueMarket;
import com.trendpilot.monitor.ExitDecision;
import com.trendpilot.monitor.ExitPolicy;
import com.trendpilot.portfolio.DepthVariantBook;
import com.trendpilot.portfolio.Position;
import com.trendpilot.signal.EarlySignal;
import com.trendpilot.signal.EarlySignalCalculator;
import com.trendpilot.universe.UniverseFilter;

/**
 * 深度门槛放宽的影子对照（V2D15）：用户问"深度 30000 有没有必要"，用数据回答。
 * <ul>
 * <li>进入：V2 审核只因 DEPTH_LT_30000 被拒（其他规则全过）的新开仓，按放宽后的深度门槛重新审核（其余规则完全相同）为 BUY，
 *     且对照账本里没有这个币。按审核时取到的真实盘口模拟买入（含滑点和手续费），每笔 1,000U。</li>
 * <li>退出：与 V2 相同的退出规则，卖出按真实全数量盘口模拟成交（含滑点）。不加仓、不占 V2 资金、不发 Telegram。</li>
 * </ul>
 * 每周复盘比较 V2D15 与 V2 的净收益、胜率、回撤和滑点；满足设计文档第 8.1 节才会调整正式门槛。
 */
@Component
public class DepthVariantTracker {

    private static final Logger log = LoggerFactory.getLogger(DepthVariantTracker.class);

    private final DepthVariantBook book;
    private final Markets markets;
    private final EarlySignalCalculator signals;
    private final ExitPolicy exitPolicy;
    private final double minDepthUsdt;
    private final com.trendpilot.monitor.LossGuard guard;

    public DepthVariantTracker(DepthVariantBook book, Markets markets, EarlySignalCalculator signals,
                               ExitPolicy exitPolicy, com.trendpilot.monitor.LossGuard guard,
                               @Value("${trendpilot.variants.depth-min-usdt:15000}") double minDepthUsdt) {
        this.guard = guard;
        this.book = book;
        this.markets = markets;
        this.signals = signals;
        this.exitPolicy = exitPolicy;
        this.minDepthUsdt = minDepthUsdt;
    }

    public double minDepthUsdt() {
        return minDepthUsdt;
    }

    public DepthVariantBook book() {
        return book;
    }

    /** V2 只因深度不足被拒：拒绝原因只有一条 DEPTH_LT_*。 */
    public static boolean rejectedOnlyForDepth(ReviewResult v2) {
        return v2.decision() == com.trendpilot.capital.Decision.REJECT && v2.reasons().size() == 1
                && v2.reasons().getFirst().startsWith("DEPTH_LT_");
    }

    /** relaxed：按放宽门槛重新审核的结果（只有 BUY 新开仓才进入）。@return 本轮进入的标的 */
    public List<String> enter(String gen, Instant now, Map<String, Venue> venueOf, List<ReviewResult> relaxed) {
        List<String> entered = new ArrayList<>();
        for (ReviewResult r : relaxed) {
            if (!r.approved() || r.action() != com.trendpilot.capital.Action.BUY || r.fill() == null) continue;
            if (book.position(r.symbol()).isPresent()) continue;
            book.open(venueOf.get(r.symbol()), r.symbol(), r.fill(), gen, now);
            entered.add(r.symbol());
        }
        if (!entered.isEmpty()) {
            log.info("V2D15 对照买入 {} 个 {}：{}", entered.size(), gen, entered);
        }
        return entered;
    }

    /** 一个持仓本轮的行情输入（锁外抓取）。 */
    public record Inputs(OrderBook book, com.trendpilot.market.SymbolInfo info, SymbolBars bars, SymbolBars btc) {
    }

    /** 抓取盘口、交易状态和 K 线（联网，在组合锁外调用）。缺数据的持仓不放进结果，本轮不判断。 */
    public Map<String, Inputs> fetch(Instant now) {
        Map<String, Inputs> out = new java.util.HashMap<>();
        Map<Venue, List<Position>> byVenue = book.positions().stream().collect(Collectors.groupingBy(Position::venue,
                () -> new EnumMap<>(Venue.class), Collectors.toList()));
        for (var e : byVenue.entrySet()) {
            VenueMarket m = markets.get(e.getKey());
            if (m == null) continue;
            BarsLoader.Result bars;
            try {
                List<String> names = new ArrayList<>(e.getValue().stream().map(Position::symbol).toList());
                names.add(UniverseFilter.BTC);
                bars = m.bars().load(names, now);
            } catch (RuntimeException ex) {
                log.warn("V2D15 本轮跳过 {}：K 线获取失败 {}", e.getKey(), ex.getMessage());
                continue;
            }
            for (Position pos : guard.needDailyAtr(DepthVariantBook.BOOK, e.getValue())) {
                guard.provideDailyAtr(DepthVariantBook.BOOK, pos, com.trendpilot.monitor.LossGuard.dailyAtrPct(m, pos.symbol(), now));
            }
            for (Position pos : e.getValue()) {
                try {
                    OrderBook ob = m.depth().getCovering(pos.symbol(), com.trendpilot.job.DiscoveryJob.DEPTH_SPAN_PCT,
                            pos.costBasis().doubleValue() * 1.5);
                    if (ob == null || !ob.sane()) continue; // 盘口缺失或异常：本轮不判断
                    out.put(pos.symbol(), new Inputs(ob, m.directory().get(pos.symbol()), bars.bars().get(pos.symbol()),
                            bars.bars().get(UniverseFilter.BTC)));
                } catch (RuntimeException ex) {
                    log.debug("V2D15 {} 本轮缺数据：{}", pos.symbol(), ex.getMessage());
                }
            }
        }
        return out;
    }

    /** Monitor 每 5 分钟调用：与 V2 相同的退出规则，按真实盘口判断和成交。@return 本轮退出的标的 */
    public List<String> manage(String gen, Instant now) {
        return manage(gen, now, fetch(now));
    }

    /** 只用已抓好的输入做判断和记账（不联网，在组合锁内调用）。 */
    public List<String> manage(String gen, Instant now, Map<String, Inputs> inputs) {
        List<String> exits = new ArrayList<>();
        for (Position pos : book.positions()) {
            Inputs in = inputs.get(pos.symbol());
            if (in == null) continue;
            try {
                EarlySignal sig = (in.bars() == null || in.btc() == null) ? null : signals.compute(in.bars(), in.btc());
                long hour = sig == null ? -1 : in.bars().hourly().getLast().openTime();
                boolean fresh = sig != null && book.isNewObservation(pos.symbol(), hour);
                ExitDecision d = exitPolicy.evaluate(pos, in.info(), in.book(), sig, fresh);
                var lastHour = in.bars() == null || in.bars().hourly().isEmpty() ? null : in.bars().hourly().getLast();
                if (lastHour != null && in.info() != null && in.info().trading()) {
                    String g = guard.check(DepthVariantBook.BOOK, pos, d, lastHour.close(), lastHour.openTime(), now);
                    if (g != null) d = com.trendpilot.monitor.LossGuard.asExit(d, g);
                }
                if (fresh && !d.sell()) book.markObserved(pos.symbol(), hour);
                for (String note : d.notes()) {
                    if (!note.equals("PROTECTION_GAPPED")) book.note(pos, note, gen, now);
                }
                if (d.sell()) {
                    book.close(pos, d.fill(), d.netPnl(), d.reason(), gen, now);
                    exits.add(pos.symbol());
                } else {
                    book.update(d.updated(), d.netPnl(), now);
                }
            } catch (RuntimeException ex) {
                log.warn("V2D15 {} 本轮判断出错，跳过：{}", pos.symbol(), ex.toString());
            }
        }
        if (!exits.isEmpty()) log.info("V2D15 对照卖出 {} 个 {}：{}", exits.size(), gen, exits);
        return exits;
    }
}
