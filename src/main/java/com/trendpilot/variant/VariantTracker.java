package com.trendpilot.variant;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.trendpilot.capital.Action;
import com.trendpilot.capital.CapitalReviewer;
import com.trendpilot.capital.ReviewInput;
import com.trendpilot.capital.ReviewResult;
import com.trendpilot.config.StrategyParams;
import com.trendpilot.job.PortfolioLock;
import com.trendpilot.market.BarsLoader;
import com.trendpilot.market.Kline;
import com.trendpilot.market.Markets;
import com.trendpilot.market.OrderBook;
import com.trendpilot.market.SymbolBars;
import com.trendpilot.market.Venue;
import com.trendpilot.market.VenueMarket;
import com.trendpilot.monitor.ExitDecision;
import com.trendpilot.monitor.ExitPolicy;
import com.trendpilot.monitor.LossGuard;
import com.trendpilot.portfolio.Ledger;
import com.trendpilot.portfolio.Position;
import com.trendpilot.signal.EarlySignal;
import com.trendpilot.signal.EarlySignalCalculator;
import com.trendpilot.universe.UniverseFilter;

/**
 * 配置化影子变体（V5、V6…）的买卖，替代每个变体写一个类。规则和原来的 V5 完全相同，只是参数来自登记表：
 * <ul>
 * <li>买入：每个候选、每个正在开新仓的变体独立判断。变体没有这个币时，用变体自己的卖出记录、V2 的审核规则加上变体的参数改动重新审核，
 *     按审核时取到的真实盘口、变体自己的每笔金额模拟成交。不加仓、不占 V2 资金、不发 Telegram。</li>
 * <li>卖出：与 V2 相同的 ExitPolicy + 山寨止损（LossGuard，按变体名分开记状态），再加变体的提前离场（H2）。按真实全数量盘口模拟成交。</li>
 * <li>任何一个变体出错只记 warn，不影响 V2 和其他变体；数据缺失的持仓本轮不判断。</li>
 * </ul>
 */
@Component
public class VariantTracker {

    private static final Logger log = LoggerFactory.getLogger(VariantTracker.class);

    private final VariantRegistry registry;
    private final CapitalReviewer reviewer;
    private final StrategyParams params;
    private final Markets markets;
    private final EarlySignalCalculator signals;
    private final ExitPolicy exitPolicy;
    private final LossGuard guard;
    private final VariantRules rules = new VariantRules();

    public VariantTracker(VariantRegistry registry, CapitalReviewer reviewer, StrategyParams params, Markets markets,
                          EarlySignalCalculator signals, ExitPolicy exitPolicy, LossGuard guard) {
        this.registry = registry;
        this.reviewer = reviewer;
        this.params = params;
        this.markets = markets;
        this.signals = signals;
        this.exitPolicy = exitPolicy;
        this.guard = guard;
    }

    public VariantRegistry registry() {
        return registry;
    }

    /** 一轮 Discovery 里各变体的待买清单。 */
    public final class Round {
        private final List<VariantRegistry.Variant> active;
        private final Map<String, List<ReviewResult>> pending = new LinkedHashMap<>();

        Round(List<VariantRegistry.Variant> active) {
            this.active = active;
        }

        /**
         * 审核一个候选。inputFor：给定变体的账本，构造这个变体自己的审核输入（卖出记录、再入场证据都按变体自己的账）。
         */
        public void review(String symbol, Function<Ledger, ReviewInput> inputFor) {
            for (VariantRegistry.Variant v : active) {
                try {
                    if (v.book().position(symbol).isPresent()) continue;
                    if (v.overrides().tradeUsdtOr(params.capital().tradeSize()) <= 0) continue;
                    ReviewResult r = VariantRules.review(reviewer, params, inputFor.apply(v.book()), v.overrides());
                    pending.computeIfAbsent(v.name(), k -> new ArrayList<>()).add(r);
                } catch (RuntimeException e) {
                    log.warn("{} {} 本轮审核出错，跳过：{}", v.name(), symbol, e.toString());
                }
            }
        }

        /** 按审核结果买入（在组合锁内，V2 分配完之后调用）。@return 变体名 → 本轮买入的标的 */
        public Map<String, List<String>> enter(String gen, Instant now, Map<String, Venue> venueOf) {
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (VariantRegistry.Variant v : active) {
                try {
                    List<String> entered = new ArrayList<>();
                    for (ReviewResult r : pending.getOrDefault(v.name(), List.of())) {
                        if (!r.approved() || r.action() != Action.BUY || r.fill() == null) continue;
                        if (v.book().position(r.symbol()).isPresent() || venueOf.get(r.symbol()) == null) continue;
                        v.book().open(venueOf.get(r.symbol()), r.symbol(), r.fill(), gen, now);
                        entered.add(r.symbol());
                    }
                    if (!entered.isEmpty()) {
                        log.info("{} 影子变体买入 {} 个 {}：{}", v.name(), entered.size(), gen, entered);
                        out.put(v.name(), entered);
                    }
                } catch (RuntimeException e) {
                    log.warn("{} 本轮买入出错，跳过：{}", v.name(), e.toString());
                }
            }
            return out;
        }
    }

    /** 开始一轮 Discovery：取出正在开新仓的变体。登记表读不到时返回空的一轮（变体本轮不买，V2 照常）。 */
    public Round round() {
        try {
            return new Round(registry.active());
        } catch (RuntimeException e) {
            log.warn("影子变体登记表读不到，本轮变体不买入：{}", e.toString());
            return new Round(List.of());
        }
    }

    /** 一个持仓本轮的行情输入（锁外抓取）。 */
    public record Inputs(OrderBook book, com.trendpilot.market.SymbolInfo info, SymbolBars bars, SymbolBars btc) {
    }

    /** 抓取一个变体全部持仓的盘口、交易状态和 K 线（联网，在组合锁外调用）。缺数据的持仓不放进结果，本轮不判断。 */
    public Map<String, Inputs> fetch(VariantRegistry.Variant v, Instant now) {
        Map<String, Inputs> out = new java.util.HashMap<>();
        Map<Venue, List<Position>> byVenue = v.book().positions().stream().collect(Collectors.groupingBy(Position::venue,
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
                log.warn("{} 本轮跳过 {}：K 线获取失败 {}", v.name(), e.getKey(), ex.getMessage());
                continue;
            }
            for (Position pos : guard.needDailyAtr(v.name(), e.getValue())) {
                guard.provideDailyAtr(v.name(), pos, LossGuard.dailyAtrPct(m, pos.symbol(), now));
            }
            for (Position pos : e.getValue()) {
                try {
                    OrderBook ob = m.depth().getCovering(pos.symbol(), com.trendpilot.job.DiscoveryJob.DEPTH_SPAN_PCT,
                            pos.costBasis().doubleValue() * 1.5);
                    if (ob == null || !ob.sane()) continue; // 盘口缺失或异常：本轮不判断
                    out.put(pos.symbol(), new Inputs(ob, m.directory().get(pos.symbol()), bars.bars().get(pos.symbol()),
                            bars.bars().get(UniverseFilter.BTC)));
                } catch (RuntimeException ex) {
                    log.debug("{} {} 本轮缺数据：{}", v.name(), pos.symbol(), ex.getMessage());
                }
            }
        }
        return out;
    }

    /** 只用已抓好的输入做判断和记账（不联网，在组合锁内调用）。@return 本轮退出的 "原因:标的" */
    public List<String> manage(VariantRegistry.Variant v, String gen, Instant now, Map<String, Inputs> inputs) {
        List<String> exits = new ArrayList<>();
        Ledger book = v.book();
        for (Position pos : book.positions()) {
            Inputs in = inputs.get(pos.symbol());
            if (in == null) continue;
            try {
                EarlySignal sig = (in.bars() == null || in.btc() == null) ? null : signals.compute(in.bars(), in.btc());
                long hour = sig == null ? -1 : in.bars().hourly().getLast().openTime();
                boolean fresh = sig != null && book.isNewObservation(pos.symbol(), hour);
                ExitDecision d = exitPolicy.evaluate(pos, in.info(), in.book(), sig, fresh);
                Kline lastHour = in.bars() == null || in.bars().hourly().isEmpty() ? null : in.bars().hourly().getLast();
                if (lastHour != null && in.info() != null && in.info().trading()) {
                    String g = guard.check(v.name(), pos, d, lastHour.close(), lastHour.openTime(), now);
                    if (g != null) d = LossGuard.asExit(d, g);
                    if (rules.earlyExit(v.name(), v.overrides(), pos, d, book.maxNet(pos.symbol()), lastHour, now)) {
                        d = LossGuard.asExit(d, VariantOverrides.EARLY_EXIT);
                    }
                }
                if (fresh && !d.sell()) book.markObserved(pos.symbol(), hour);
                for (String note : d.notes()) {
                    if (!note.equals("PROTECTION_GAPPED")) book.note(pos, note, gen, now);
                }
                if (d.sell()) {
                    book.close(pos, d.fill(), d.netPnl(), d.reason(), gen, now);
                    rules.forget(v.name(), pos);
                    exits.add(d.reason() + ":" + pos.symbol());
                } else {
                    book.update(d.updated(), d.netPnl(), now);
                }
            } catch (RuntimeException ex) {
                log.warn("{} {} 本轮判断出错，跳过：{}", v.name(), pos.symbol(), ex.toString());
            }
        }
        if (!exits.isEmpty()) log.info("{} 影子变体卖出 {} 个 {}：{}", v.name(), exits.size(), gen, exits);
        return exits;
    }

    /**
     * Monitor 每 5 分钟调用：每个没归档的变体锁外抓行情、锁内判断，记一次权益；停开新仓且已卖完的变体归档。
     * 任何一个变体出错只记 warn。
     */
    public Map<String, List<String>> monitorAll(String gen, Instant now, PortfolioLock lock) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        List<VariantRegistry.Variant> all;
        try {
            all = registry.managed();
        } catch (RuntimeException e) {
            log.warn("影子变体登记表读不到，本轮变体不判断：{}", e.toString());
            return out;
        }
        for (VariantRegistry.Variant v : all) {
            try {
                Map<String, Inputs> in = fetch(v, now);
                List<String> exits = new ArrayList<>();
                lock.run(() -> exits.addAll(manage(v, gen, now, in)));
                v.book().recordEquity(now);
                if (!exits.isEmpty()) out.put(v.name(), exits);
                if (!v.opensNew() && v.book().positions().isEmpty()) {
                    registry.setStatus(v.name(), VariantRegistry.ARCHIVED,
                            VariantRegistry.statusLabel(v.status()) + "后持仓已卖完，归档", now);
                    log.info("{} 持仓已卖完，归档", v.name());
                }
            } catch (RuntimeException e) {
                log.warn("{} 对照本轮未完成：{}", v.name(), e.getMessage());
            }
        }
        return out;
    }
}
