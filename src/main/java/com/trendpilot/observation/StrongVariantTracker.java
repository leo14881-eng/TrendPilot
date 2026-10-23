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
import com.trendpilot.portfolio.Position;
import com.trendpilot.portfolio.StrongVariantBook;
import com.trendpilot.signal.EarlySignal;
import com.trendpilot.signal.EarlySignalCalculator;
import com.trendpilot.universe.UniverseFilter;

/**
 * 强信号对照（V5，2026-10-23 用户决定）：V2 保持原买入规则作为基准，强信号买入规则（1h 相对 BTC ≥ 5.855%）单独记一本影子账，同期对比半个月（约 11-07 评估）。
 * <ul>
 * <li>进入：每个候选独立判断（不只看 V2 的拒绝），V5 没有这个币时，用正常审核规则、正常深度门槛，另加新开仓 rel1h 门槛重新审核为 BUY，
 *     按审核时取到的真实盘口模拟买入（含滑点和手续费），每笔 1,000U。</li>
 * <li>退出：与 V2 相同的退出规则和山寨止损，卖出按真实全数量盘口模拟成交（含滑点）。不加仓、不占 V2 资金、不发 Telegram。</li>
 * </ul>
 */
@Component
public class StrongVariantTracker {

    private static final Logger log = LoggerFactory.getLogger(StrongVariantTracker.class);

    private final StrongVariantBook book;
    private final Markets markets;
    private final EarlySignalCalculator signals;
    private final ExitPolicy exitPolicy;
    private final double minRel1h;
    private final com.trendpilot.monitor.LossGuard guard;

    public StrongVariantTracker(StrongVariantBook book, Markets markets, EarlySignalCalculator signals,
                               ExitPolicy exitPolicy, com.trendpilot.monitor.LossGuard guard,
                               @Value("${trendpilot.variants.strong-min-rel1h:5.855}") double minRel1h) {
        this.guard = guard;
        this.book = book;
        this.markets = markets;
        this.signals = signals;
        this.exitPolicy = exitPolicy;
        this.minRel1h = minRel1h;
    }

    /** 新开仓要求的 1h 相对 BTC 收益（%）。 */
    public double minRel1h() {
        return minRel1h;
    }

    public StrongVariantBook book() {
        return book;
    }

    /** reviewed：按强信号门槛重新审核的结果（只有 BUY 新开仓才进入）。@return 本轮进入的标的 */
    public List<String> enter(String gen, Instant now, Map<String, Venue> venueOf, List<ReviewResult> reviewed) {
        List<String> entered = new ArrayList<>();
        for (ReviewResult r : reviewed) {
            if (!r.approved() || r.action() != com.trendpilot.capital.Action.BUY || r.fill() == null) continue;
            if (book.position(r.symbol()).isPresent() || venueOf.get(r.symbol()) == null) continue;
            book.open(venueOf.get(r.symbol()), r.symbol(), r.fill(), gen, now);
            entered.add(r.symbol());
        }
        if (!entered.isEmpty()) {
            log.info("V5 强信号对照买入 {} 个 {}：{}", entered.size(), gen, entered);
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
                log.warn("V5 本轮跳过 {}：K 线获取失败 {}", e.getKey(), ex.getMessage());
                continue;
            }
            for (Position pos : guard.needDailyAtr(StrongVariantBook.BOOK, e.getValue())) {
                guard.provideDailyAtr(StrongVariantBook.BOOK, pos, com.trendpilot.monitor.LossGuard.dailyAtrPct(m, pos.symbol(), now));
            }
            for (Position pos : e.getValue()) {
                try {
                    OrderBook ob = m.depth().getCovering(pos.symbol(), com.trendpilot.job.DiscoveryJob.DEPTH_SPAN_PCT,
                            pos.costBasis().doubleValue() * 1.5);
                    if (ob == null || !ob.sane()) continue; // 盘口缺失或异常：本轮不判断
                    out.put(pos.symbol(), new Inputs(ob, m.directory().get(pos.symbol()), bars.bars().get(pos.symbol()),
                            bars.bars().get(UniverseFilter.BTC)));
                } catch (RuntimeException ex) {
                    log.debug("V5 {} 本轮缺数据：{}", pos.symbol(), ex.getMessage());
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
                    String g = guard.check(StrongVariantBook.BOOK, pos, d, lastHour.close(), lastHour.openTime(), now);
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
                log.warn("V5 {} 本轮判断出错，跳过：{}", pos.symbol(), ex.toString());
            }
        }
        if (!exits.isEmpty()) log.info("V5 强信号对照卖出 {} 个 {}：{}", exits.size(), gen, exits);
        return exits;
    }
}
