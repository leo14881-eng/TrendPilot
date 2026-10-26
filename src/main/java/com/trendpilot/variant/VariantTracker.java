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
    /** 涨幅榜动量排名的币池大小（回测是每天成交额前 150）。 */
    static final int MOMENTUM_POOL = 150;

    private final VariantRegistry registry;
    private final CapitalReviewer reviewer;
    private final StrategyParams params;
    private final Markets markets;
    private final EarlySignalCalculator signals;
    private final ExitPolicy exitPolicy;
    private final LossGuard guard;
    private final VariantRules rules = new VariantRules();

    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final com.trendpilot.risk.SystemRiskAssessor assessor;
    private final com.trendpilot.job.AppState state;
    private final com.trendpilot.capital.CapitalAllocator allocator;
    /** 每个变体自己的恢复期状态机（状态存 app_state 的 risk:&lt;变体&gt;:*）。 */
    private final Map<String, com.trendpilot.risk.RiskRecovery> recoveries = new java.util.concurrent.ConcurrentHashMap<>();

    public VariantTracker(VariantRegistry registry, CapitalReviewer reviewer, StrategyParams params, Markets markets,
                          EarlySignalCalculator signals, ExitPolicy exitPolicy, LossGuard guard,
                          org.springframework.jdbc.core.simple.JdbcClient jdbc, com.trendpilot.risk.SystemRiskAssessor assessor,
                          com.trendpilot.job.AppState state, com.trendpilot.capital.CapitalAllocator allocator) {
        this.jdbc = jdbc;
        this.assessor = assessor;
        this.state = state;
        this.allocator = allocator;
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

    /** "让赢家跑"阶段的宽回吐 ExitPolicy（每组参数一个）。 */
    private final Map<String, ExitPolicy> runnerPolicies = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean runnerOn(VariantRegistry.Variant v, Position pos) {
        return v.overrides().runnerActive(pos.protectArmed(), pos.peakNet().doubleValue(), pos.costBasis().doubleValue());
    }

    private ExitPolicy exitPolicyFor(VariantRegistry.Variant v, Position pos) {
        if (!runnerOn(v, pos)) return exitPolicy;
        return runnerPolicies.computeIfAbsent(v.overrides().inline(), k -> new ExitPolicy(VariantRules.runnerParams(params, v.overrides())));
    }

    /** 每个变体上线以来的权益峰值（已实现 + 未实现，U）；第一次用时从 equity_curve 读一次。 */
    private final Map<String, double[]> peaks = new java.util.concurrent.ConcurrentHashMap<>();
    private org.springframework.beans.factory.ObjectProvider<com.trendpilot.notify.Notifier> notifier;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setNotifier(org.springframework.beans.factory.ObjectProvider<com.trendpilot.notify.Notifier> notifier) {
        this.notifier = notifier;
    }

    /**
     * 安全上限（用户 2026-10-26"安全第一"）：变体上线以来净值回撤（资金 = 硬顶 20,000 U，含浮亏）超过 25% 立即停开新仓，
     * 持仓照原规则卖完后归档。只影响这个变体。
     */
    void checkDrawdown(VariantRegistry.Variant v, Instant now) {
        if (!v.opensNew()) return;
        double cur = v.book().realizedPnl() + v.book().unrealizedPnl();
        double[] st = peaks.computeIfAbsent(v.name() + "@" + v.startedAt(), k -> new double[]{baseline(v), Double.NaN});
        double base = st[0];
        if (Double.isNaN(st[1])) st[1] = Math.max(base, maxSince(v));
        st[1] = Math.max(st[1], cur);
        double cap = v.book().capital();
        double dd = ((cap + st[1] - base) - (cap + cur - base)) / (cap + st[1] - base);
        if (dd > AutoPromoter.MAX_NAV_DD) {
            String why = String.format(java.util.Locale.ROOT, "影子期间净值回撤 %.1f%% 超过 25%% 上限", dd * 100);
            registry.setStatus(v.name(), VariantRegistry.STOPPED, why, now);
            log.warn("{} {}，停开新仓", v.name(), why);
            var n = notifier == null ? null : notifier.getIfAvailable();
            if (n != null) n.send("optimize:stop:" + v.name(), "🧪 影子变体 " + v.name() + " 停开新仓：" + why + "。现有持仓按原规则卖完后归档。");
        }
    }

    private double baseline(VariantRegistry.Variant v) {
        return jdbcValue("SELECT realized + unrealized FROM equity_curve WHERE book = :b AND at <= :t ORDER BY at DESC, id DESC LIMIT 1", v);
    }

    private double maxSince(VariantRegistry.Variant v) {
        return jdbcValue("SELECT MAX(realized + unrealized) FROM equity_curve WHERE book = :b AND at > :t", v);
    }

    private double jdbcValue(String sql, VariantRegistry.Variant v) {
        try {
            Double x = jdbc.sql(sql).param("b", v.name()).param("t", v.startedAt().toString()).query(Double.class).optional().orElse(null);
            return x == null ? 0 : x;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * 本轮市场层面的风险输入（与 V2 共用）：BTC K 线和 24h 行情、Binance 主池、流动性 / 稳定币 / 已确认冲击 / 山寨崩盘证据、运行租约。
     * 账本层面的部分（亏损冻结、额度减半、恢复期）每个变体按自己的盈亏单独算。
     */
    public record MarketRisk(SymbolBars btc, com.trendpilot.market.Ticker24h btcTicker, List<com.trendpilot.market.Ticker24h> universe,
                             com.trendpilot.risk.MarketEvidence evidence, boolean leaseOk, String generation, Instant now) {
    }

    /** 某个变体本轮的风控状态（报表用）：NORMAL / LOSS_FREEZE / LOSS_HALF_BUDGET / RECOVERING。 */
    public static String statusKey(String book) {
        return BookState.key(book, "risk:status");
    }

    public static String statusLabel(String status) {
        if (status == null || status.isBlank()) return "正常";
        return switch (status) {
            case "NORMAL" -> "正常";
            case "LOSS_FREEZE" -> "亏损冻结";
            case "LOSS_HALF_BUDGET" -> "额度减半";
            case "RECOVERING" -> "恢复期";
            case "MARKET_BLOCKED" -> "市场风险暂停";
            default -> status;
        };
    }

    /** 报表用：变体最近一次记录的风控状态（中文）。 */
    public String riskLabel(String book) {
        try {
            return statusLabel(state.get(statusKey(book)).orElse("NORMAL"));
        } catch (RuntimeException e) {
            return "暂时取不到";
        }
    }

    /**
     * 按 V2 同一套规则和参数算这个变体自己的风险结论：亏损冻结 = 窗口内自己的亏损卖出 + 自己深度浮亏的持仓（冻结修复口径），
     * 市场部分（BTC 急跌、行情状态、流动性、稳定币、山寨崩盘）与 V2 相同；恢复期状态存 risk:&lt;变体&gt;:*。
     */
    com.trendpilot.risk.RiskState riskFor(VariantRegistry.Variant v, MarketRisk m) {
        StrategyParams.Risk p = params.risk();
        java.time.Duration window = java.time.Duration.ofHours(p.lossWindowHours());
        int losses = v.book().lossExitsSince(m.now().minus(window))
                + v.book().underwaterCountWithin(p.underwaterLossPct(), m.now(), window);
        com.trendpilot.risk.RiskState raw = assessor.assess(m.btc(), m.btcTicker(), m.universe(), losses, m.evidence());
        com.trendpilot.risk.RiskRecovery rec = recoveries.computeIfAbsent(v.name(),
                k -> new com.trendpilot.risk.RiskRecovery(new BookState(state, k), params));
        com.trendpilot.risk.RiskState r = rec.apply(raw, m.generation());
        if (!m.leaseOk()) r = com.trendpilot.job.DiscoveryJob.blockNew(r, "LEASE_UNCONFIRMED");
        String status = r.reasons().contains("LOSS_FREEZE") ? "LOSS_FREEZE"
                : r.reasons().stream().anyMatch(x -> x.startsWith("RISK_RECOVERY")) ? "RECOVERING"
                : r.reasons().contains("LOSS_HALF_BUDGET") ? "LOSS_HALF_BUDGET"
                : !r.newRiskAllowed() ? "MARKET_BLOCKED" : "NORMAL";
        state.put(statusKey(v.name()), status);
        return r;
    }

    /** 一轮 Discovery 里各变体的待买清单。 */
    public final class Round {
        private final List<VariantRegistry.Variant> active;
        private final Map<String, List<ReviewResult>> pending = new LinkedHashMap<>();
        private final Map<String, com.trendpilot.risk.RiskState> risks = new LinkedHashMap<>();

        Round(List<VariantRegistry.Variant> active) {
            this.active = active;
        }

        /** 本轮每个变体自己的风险结论（组合锁内，候选审核之前调用一次）。市场输入缺失时变体本轮不买。 */
        public void prepareRisk(MarketRisk m) {
            for (VariantRegistry.Variant v : active) {
                try {
                    if (m != null && m.btc() != null && m.btcTicker() != null) risks.put(v.name(), riskFor(v, m));
                } catch (RuntimeException e) {
                    log.warn("{} 本轮风控计算出错，不买入：{}", v.name(), e.toString());
                }
            }
        }

        /** 这个变体本轮的风险结论；没算出来（缺数据、出错）时为 null，变体本轮不买。 */
        public com.trendpilot.risk.RiskState risk(String name) {
            return risks.get(name);
        }

        /** 按变体自己的额度分配（与 V2 的 CapitalAllocator 同规则：行情上限 × 恢复系数 − 已占用 + 亏损部分）。 */
        private List<ReviewResult> funded(VariantRegistry.Variant v, List<ReviewResult> approved) {
            com.trendpilot.risk.RiskState r = risks.get(v.name());
            if (r == null || approved.isEmpty()) return List.of();
            return allocator.allocate(approved, r, v.book().deployedCost(), v.book().realizedPnl(), v.book().unrealizedPnl()).funded();
        }

        /**
         * 审核一个候选。inputFor：给定变体的账本，构造这个变体自己的审核输入（卖出记录、再入场证据都按变体自己的账）。
         */
        public void review(String symbol, java.util.function.BiFunction<Ledger, com.trendpilot.risk.RiskState, ReviewInput> inputFor) {
            for (VariantRegistry.Variant v : active) {
                if (v.overrides().isMomentum()) continue; // 涨幅榜动量不走 EARLY 审核，见 momentumEnter
                com.trendpilot.risk.RiskState r0 = risks.get(v.name());
                if (r0 == null) continue;
                try {
                    if (v.book().position(symbol).isPresent()) continue;
                    if (v.overrides().tradeUsdtOr(params.capital().tradeSize()) <= 0) continue;
                    ReviewResult r = VariantRules.review(reviewer, params, inputFor.apply(v.book(), r0), v.overrides());
                    pending.computeIfAbsent(v.name(), k -> new ArrayList<>()).add(r);
                } catch (RuntimeException e) {
                    log.warn("{} {} 本轮审核出错，跳过：{}", v.name(), symbol, e.toString());
                }
            }
        }

        /**
         * 涨幅榜动量变体本轮想看的币（Binance 主池，按滚动 24h 涨幅或最近 4 根 1h 的涨幅取前 N 名）。锁外调用，
         * 调用方据此补抓这几个币的盘口（每个变体每小时最多 N 次请求）。
         */
        public java.util.Set<String> momentumSymbols(List<com.trendpilot.market.Ticker24h> universe,
                                                     Map<String, SymbolBars> bars) {
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            momentumTop.clear();
            for (VariantRegistry.Variant v : active) {
                if (!v.overrides().isMomentum()) continue;
                try {
                    List<String> top = rank(universe, bars, v.overrides().momentumHoursOr24()).stream()
                            .limit(v.overrides().momentumTop()).toList();
                    momentumTop.put(v.name(), top);
                    out.addAll(top);
                } catch (RuntimeException e) {
                    log.warn("{} 涨幅榜排名出错，本轮不买：{}", v.name(), e.toString());
                }
            }
            return out;
        }

        private final Map<String, List<String>> momentumTop = new LinkedHashMap<>();

        /**
         * 涨幅榜动量买入（组合锁内）：前 N 名里没持有的、卖出满 60 分钟的，盘口新鲜、深度和滑点过 V2 审核门槛、扫描完整、
         * 系统风险允许新增（V2 同一轮的风险结论）、没超过 max-open，就按变体每笔金额在这份盘口上模拟买入。缺盘口就不买。
         */
        public Map<String, List<String>> momentumEnter(String gen, Instant now, boolean scanComplete,
                                                       Map<String, OrderBook> books, Venue venue) {
            Map<String, List<String>> out = new LinkedHashMap<>();
            if (!scanComplete) return out;
            StrategyParams.Review rv = params.review();
            for (VariantRegistry.Variant v : active) {
                List<String> top = momentumTop.get(v.name());
                com.trendpilot.risk.RiskState risk = risks.get(v.name());
                if (top == null || risk == null || !risk.newRiskAllowed()) continue;
                try {
                    List<String> entered = new ArrayList<>();
                    List<ReviewResult> picks = new ArrayList<>();
                    int open = v.book().positions().size();
                    for (String sym : top) {
                        if (v.overrides().maxOpen() != null && open >= v.overrides().maxOpen()) break;
                        if (v.book().position(sym).isPresent()) continue;
                        Instant last = v.book().lastExit(sym).orElse(null);
                        if (last != null && java.time.Duration.between(last, now).toMinutes() < rv.reentryCooldownMinutes()) continue;
                        OrderBook ob = books.get(sym);
                        if (ob == null || !ob.usable() || java.time.Duration.between(ob.fetchedAt(), now).toSeconds() > rv.maxSignalAgeMinutes() * 60) {
                            continue;
                        }
                        double depth = Math.min(ob.bidDepthWithin(rv.depthBandPct()), ob.askDepthWithin(rv.depthBandPct()));
                        var fill = com.trendpilot.market.ExecutionSimulator.buy(ob,
                                java.math.BigDecimal.valueOf(v.overrides().tradeUsdtOr(params.capital().tradeSize())), params.feeRate());
                        if (depth < v.overrides().minDepthUsdtOr(rv.minDepthUsdt()) || ob.spreadBps() > rv.maxSpreadBps()
                                || !fill.complete() || fill.slippageBps() > rv.maxSlippageBps()) {
                            continue;
                        }
                        picks.add(new ReviewResult(sym, Action.BUY, com.trendpilot.capital.Decision.BUY, List.of(), List.of(),
                                picks.size() == 0 ? 10 : 10 - picks.size() * 0.01, Double.NaN, ob.spreadBps(), fill.slippageBps(),
                                depth, depth, fill));
                        open++;
                    }
                    // 按变体自己的额度（同 V2 的分配规则），排名靠前的先分到
                    for (ReviewResult r : funded(v, picks)) {
                        v.book().open(venue, r.symbol(), r.fill(), gen, now);
                        entered.add(r.symbol());
                    }
                    if (!entered.isEmpty()) {
                        log.info("{} 涨幅榜动量买入 {} 个 {}：{}", v.name(), entered.size(), gen, entered);
                        out.put(v.name(), entered);
                    }
                } catch (RuntimeException e) {
                    log.warn("{} 涨幅榜动量本轮买入出错，跳过：{}", v.name(), e.toString());
                }
            }
            return out;
        }

        /** 按买入审核同一套"已收盘"数据排名：24h 用 24h 行情涨跌，4h 用最近 4 根已收盘 1h K 线。数据缺的币不参加排名。 */
        List<String> rank(List<com.trendpilot.market.Ticker24h> universe, Map<String, SymbolBars> bars, int hours) {
            Map<String, Double> score = new LinkedHashMap<>();
            // 和 4 年回测同口径：只在 24h 成交额前 150 的币里排名
            List<com.trendpilot.market.Ticker24h> pool = universe.stream()
                    .sorted((a, b) -> Double.compare(b.quoteVolume(), a.quoteVolume())).limit(MOMENTUM_POOL).toList();
            for (var t : pool) {
                if (t.symbol().equals(UniverseFilter.BTC)) continue;
                double x;
                if (hours == 24) {
                    x = t.priceChangePct();
                } else {
                    SymbolBars b = bars.get(t.symbol());
                    if (b == null || b.hourly().size() < hours + 1) continue;
                    var h = b.hourly();
                    double from = h.get(h.size() - 1 - hours).close();
                    x = from > 0 ? (h.getLast().close() / from - 1) * 100 : Double.NaN;
                }
                if (Double.isFinite(x)) score.put(t.symbol(), x);
            }
            return score.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).map(Map.Entry::getKey).toList();
        }

        /** 按审核结果买入（在组合锁内，V2 分配完之后调用）。@return 变体名 → 本轮买入的标的 */
        public Map<String, List<String>> enter(String gen, Instant now, Map<String, Venue> venueOf) {
            Map<String, List<String>> out = new LinkedHashMap<>();
            for (VariantRegistry.Variant v : active) {
                try {
                    List<String> entered = new ArrayList<>();
                    List<ReviewResult> approved = pending.getOrDefault(v.name(), List.of()).stream()
                            .filter(r -> r.approved() && r.action() == Action.BUY && r.fill() != null).toList();
                    for (ReviewResult r : funded(v, approved)) {
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
                guard.provideDailyAtr(v.name(), pos, guard.dailyAtrCached(m, pos.symbol(), now));
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
                ExitDecision d = exitPolicyFor(v, pos).evaluate(runnerOn(v, pos) ? VariantRules.runnerView(pos) : pos, in.info(),
                        in.book(), sig, fresh);
                Kline lastHour = in.bars() == null || in.bars().hourly().isEmpty() ? null : in.bars().hourly().getLast();
                if (lastHour != null && in.info() != null && in.info().trading()) {
                    String g = guard.check(v.name(), pos, d, lastHour.close(), lastHour.openTime(), now);
                    if (g != null) d = LossGuard.asExit(d, g);
                    String extra = rules.earlyExit(v.name(), v.overrides(), pos, d, book.maxNet(pos.symbol()), lastHour, now);
                    if (extra != null) d = LossGuard.asExit(d, extra);
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
                checkDrawdown(v, now);
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
