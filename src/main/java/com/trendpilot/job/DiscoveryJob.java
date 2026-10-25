package com.trendpilot.job;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.trendpilot.capital.Action;
import com.trendpilot.capital.CapitalAllocator;
import com.trendpilot.capital.CapitalReviewer;
import com.trendpilot.capital.ReviewInput;
import com.trendpilot.capital.ReviewResult;
import com.trendpilot.config.StrategyParams;
import com.trendpilot.market.BarsLoader;
import com.trendpilot.market.BookTicker;
import com.trendpilot.market.Markets;
import com.trendpilot.market.OrderBook;
import com.trendpilot.market.SymbolBars;
import com.trendpilot.market.SymbolInfo;
import com.trendpilot.market.Ticker24h;
import com.trendpilot.market.Venue;
import com.trendpilot.market.VenueMarket;
import com.trendpilot.market.stream.StreamHub;
import com.trendpilot.notify.Messages;
import com.trendpilot.notify.Notifier;
import com.trendpilot.observation.V1Tracker;
import com.trendpilot.research.ResearchRecord;
import com.trendpilot.research.ResearchService;
import com.trendpilot.portfolio.Position;
import com.trendpilot.portfolio.ShadowBook;
import com.trendpilot.risk.MarketEvidence;
import com.trendpilot.risk.RiskRecovery;
import com.trendpilot.risk.RiskState;
import com.trendpilot.risk.SystemRiskAssessor;
import com.trendpilot.signal.EarlySignal;
import com.trendpilot.signal.EarlySignalCalculator;
import com.trendpilot.tracking.SignalTracker;
import com.trendpilot.universe.UniverseFilter;

/**
 * Discovery（每小时 :17，越南时间）：行情全集（Binance 主池 + Bybit 独立池）→ EARLY → 系统风险
 * → Capital Review → 资金分配 → V2 账本。扫描不完整时只记录信号，不做新买入。
 */
@Component
public class DiscoveryJob {

    public static final String JOB = "DISCOVERY";
    /** 盘口覆盖范围：审核看 ±2% 深度，多取 0.5% 余量。 */
    public static final double DEPTH_SPAN_PCT = 2.5;
    /** 稳定币证据：这些交易对相对 1 美元的偏离。 */
    static final Set<String> STABLE_REFERENCES = Set.of("USDCUSDT", "FDUSDUSDT");

    private static final Logger log = LoggerFactory.getLogger(DiscoveryJob.class);

    private final Markets markets;
    private final ResearchService research;
    private final StreamHub streams;
    private final V1Tracker v1;
    private final UniverseFilter universeFilter;
    private final EarlySignalCalculator signals;
    private final SystemRiskAssessor riskAssessor;
    private final RiskRecovery recovery;
    private final CapitalReviewer reviewer;
    private final CapitalAllocator allocator;
    private final ShadowBook book;
    private final PortfolioLock lock;
    private final Notifier notifier;
    private final JobRuns runs;
    private final AppState state;
    private final JdbcClient jdbc;
    private final StrategyParams params;
    private final SignalTracker tracker;
    private final com.trendpilot.market.KlineStore klineStore;
    private final com.trendpilot.observation.DepthVariantTracker depthVariant;
    private final com.trendpilot.variant.VariantTracker variants;
    private final com.trendpilot.variant.ParamSets paramSets;

    private final org.springframework.beans.factory.ObjectProvider<com.trendpilot.lease.ActiveLease> lease;
    /** 锁外抓的盘口进入审核时最多这么旧。 */
    static final java.time.Duration MAX_BOOK_AGE = java.time.Duration.ofSeconds(90);

    /** 不允许新开仓（保留其他风险结论）。 */
    static RiskState blockNew(RiskState r, String reason) {
        List<String> reasons = new ArrayList<>(r.reasons());
        reasons.add(reason);
        return new RiskState(r.regime(), false, 0, r.btc24hPct(), r.btc4hPct(), r.btc1hPct(), r.breadth(),
                r.recentLossExits(), List.copyOf(reasons));
    }

    public DiscoveryJob(Markets markets, ResearchService research, StreamHub streams, V1Tracker v1, UniverseFilter universeFilter, EarlySignalCalculator signals, SystemRiskAssessor riskAssessor, RiskRecovery recovery, CapitalReviewer reviewer,
                        CapitalAllocator allocator, ShadowBook book, PortfolioLock lock, Notifier notifier,
                        JobRuns runs, AppState state, JdbcClient jdbc, StrategyParams params,
                        SignalTracker tracker, com.trendpilot.market.KlineStore klineStore,
                        org.springframework.beans.factory.ObjectProvider<com.trendpilot.lease.ActiveLease> lease,
                        com.trendpilot.observation.DepthVariantTracker depthVariant,
                        com.trendpilot.variant.VariantTracker variants,
                        com.trendpilot.variant.ParamSets paramSets) {
        this.depthVariant = depthVariant;
        this.variants = variants;
        this.paramSets = paramSets;
        this.lease = lease;
        this.tracker = tracker;
        this.klineStore = klineStore;
        this.markets = markets;
        this.research = research;
        this.streams = streams;
        this.v1 = v1;
        this.universeFilter = universeFilter;
        this.signals = signals;
        this.riskAssessor = riskAssessor;
        this.recovery = recovery;
        this.reviewer = reviewer;
        this.allocator = allocator;
        this.book = book;
        this.lock = lock;
        this.notifier = notifier;
        this.runs = runs;
        this.state = state;
        this.jdbc = jdbc;
        this.params = params;
    }

    public record Report(String generation, int universe, int missing, int early, int reviewed, List<String> entries,
                         boolean scanComplete, int klineRequests, String pools, List<String> v1Entries) {
    }

    /**
     * 启动后马上恢复 K 线订阅（最近一天有缓存的标的 + 持仓 + 跟踪中的信号），
     * 不等下一次 :17 的 Discovery，重启后的 K 线缓存不会断一小时。
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void watchAtStartup() {
        Instant since = Instant.now().minus(java.time.Duration.ofDays(1));
        for (VenueMarket m : markets.all()) {
            try {
                Set<String> watched = klineStore.recentSymbols(m.venue(), since);
                book.positions().stream().filter(p -> p.venue() == m.venue()).forEach(p -> watched.add(p.symbol()));
                v1.positions().stream().filter(p -> p.venue() == m.venue()).forEach(p -> watched.add(p.symbol()));
                watched.addAll(tracker.trackingSymbols(m.venue()));
                if (!watched.isEmpty()) streams.watchKlines(m.venue(), watched);
                log.info("启动时恢复 {} K 线订阅 {} 个", m.venue(), watched.size());
            } catch (RuntimeException e) {
                log.warn("启动时恢复 K 线订阅失败 {}：{}", m.venue(), e.getMessage());
            }
        }
    }

    @Scheduled(cron = "0 17 * * * *", zone = "${trendpilot.zone}")
    public void scheduled() {
        runOnce(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    public Report runOnce(Instant now) {
        String gen = Generations.of("D", now);
        try {
            Report r = discover(gen, now);
            runs.record(JOB, gen, now, Instant.now(), "SUCCESS", r.scanComplete() ? "COMPLETE" : "PARTIAL_DATA",
                    "universe=" + r.universe() + " missing=" + r.missing() + " early=" + r.early()
                            + " reviewed=" + r.reviewed() + " klineRequests=" + r.klineRequests() + " entries=" + r.entries()
                            + " v1Entries=" + r.v1Entries().size() + " pools=" + r.pools());
            log.info("Discovery 完成 {}", r);
            return r;
        } catch (RuntimeException e) {
            log.error("Discovery 失败 {}", gen, e);
            runs.record(JOB, gen, now, Instant.now(), "FAILED", "N/A", String.valueOf(e.getMessage()));
            notifier.send("fail:" + JOB + ":" + now.truncatedTo(ChronoUnit.HOURS),
                    Messages.failure(JOB, String.valueOf(e.getMessage())));
            return new Report(gen, 0, 0, 0, 0, List.of(), false, 0, "N/A", List.of());
        }
    }

    /** 一个交易所池在本轮截面上的数据。error 不为空表示该池本轮整体不可用。 */
    private record Pool(VenueMarket market, List<Ticker24h> tickers, List<Ticker24h> universe, BarsLoader.Result bars,
                        SymbolBars btc, boolean complete, String error) {

        Venue venue() {
            return market.venue();
        }

        int missing() {
            return bars == null ? universe.size() : bars.missing().size();
        }
    }

    /** 候选：信号所在的交易所 + 信号 + 24h 行情。 */
    private record Candidate(Venue venue, EarlySignal signal, Ticker24h ticker) {
    }

    private Report discover(String gen, Instant now) {
        // 1. 行情全集与 K 线（锁外）。Binance 是主池，失败则本轮失败；Bybit 独立池只覆盖 Binance 没有的标的，
        //    Bybit 不可用（例如地区限制 403）只记为该池缺数据，不影响 Binance。
        VenueMarket binance = markets.get(Venue.BINANCE);
        if (binance == null) {
            throw new IllegalStateException("Binance 行情未启用");
        }
        Pool main = loadPool(binance, Set.of(), now);
        Set<String> binanceListed = binance.directory().all().stream().filter(SymbolInfo::trading)
                .map(SymbolInfo::symbol).collect(Collectors.toSet());
        List<Pool> pools = new ArrayList<>(List.of(main));
        VenueMarket bybit = markets.get(Venue.BYBIT);
        if (bybit != null) {
            try {
                // Bybit 上的持仓即使 Binance 也有这个币，也用 Bybit 自己的信号和盘口审核加仓
                Set<String> covered = new java.util.HashSet<>(binanceListed);
                // 名字不同但其实是同一个币（例如 Binance BEAMX 与 Bybit BEAM）也算已覆盖，避免同一个币两边各买一次
                covered.addAll(research.sameCoinOn(Venue.BYBIT, Venue.BINANCE, binanceListed));
                book.positions().stream().filter(p -> p.venue() == Venue.BYBIT).forEach(p -> covered.remove(p.symbol()));
                pools.add(loadPool(bybit, covered, now));
            } catch (RuntimeException e) {
                log.warn("Bybit 独立池本轮不可用：{}", e.getMessage());
                pools.add(new Pool(bybit, List.of(), List.of(), null, null, false, "BYBIT_UNAVAILABLE: " + e.getMessage()));
            }
        }
        markets.prune(now);

        // 2. EARLY 信号：每个池用自己的 BTC 基准；基准缺失的池不计算信号
        Map<String, Candidate> bySymbol = new LinkedHashMap<>();
        for (Pool pool : pools) {
            if (pool.btc() == null) continue;
            for (Ticker24h t : pool.universe()) {
                SymbolBars b = pool.bars().bars().get(t.symbol());
                if (b != null && !t.symbol().equals(UniverseFilter.BTC)) {
                    EarlySignal s = signals.compute(b, pool.btc());
                    if (s == null) continue;
                    Venue held = book.position(t.symbol()).map(Position::venue).orElse(null);
                    // 有持仓的币用持仓所在交易所的信号（加仓按持仓的盘口审核）
                    if (held == pool.venue()) bySymbol.put(t.symbol(), new Candidate(pool.venue(), s, t));
                    else bySymbol.putIfAbsent(t.symbol(), new Candidate(pool.venue(), s, t));
                }
            }
        }
        List<Candidate> early = bySymbol.values().stream().filter(c -> c.signal().early()).toList();
        early.forEach(c -> logSignal(gen, c, now));

        // 3. 系统风险（以 Binance 主池的 BTC 和市场广度为准）
        Ticker24h btcTicker = main.tickers().stream().filter(t -> t.symbol().equals(UniverseFilter.BTC)).findFirst().orElse(null);
        // 亏损冻结：窗口内亏损退出 + 深度浮亏的持仓（亏损仓不卖出，不能让它们在风控里隐身）
        int lossExits = book.lossExitsSince(now.minus(Duration.ofHours(params.risk().lossWindowHours())))
                + book.underwaterCountWithin(params.risk().underwaterLossPct(), now,
                        Duration.ofHours(params.risk().lossWindowHours()));
        // 全市场最优买卖价：每个池一次请求，用于流动性证据和信号跟踪的起点价
        Map<Venue, Map<String, BookTicker>> quotes = new EnumMap<>(Venue.class);
        for (Pool pool : pools) {
            try {
                quotes.put(pool.venue(), pool.market().tickers().bookTickers().stream()
                        .collect(Collectors.toMap(BookTicker::symbol, q -> q, (a, b) -> a)));
            } catch (RuntimeException e) {
                log.warn("{} 最优价获取失败：{}", pool.venue(), e.getMessage());
                quotes.put(pool.venue(), Map.of());
            }
        }
        MarketEvidence ev = evidence(quotes.getOrDefault(Venue.BINANCE, Map.of()), main, pools, now);
        RiskState raw = riskAssessor.assess(main.btc(), btcTicker, main.universe(), lossExits, ev);
        // V1 只受市场类风险（BTC 急跌、流动性、稳定币、数据缺失）限制，不受 V2 的亏损冻结和恢复期限制，
        // 否则 V2 浮亏时 V1 也停止采样，对比数据正好在行情差的时候断档
        RiskState v1Raw = riskAssessor.assess(main.btc(), btcTicker, main.universe(), 0,
                new MarketEvidence(ev.medianSpreadBps(), ev.stableDeviationPct(), 0, ev.altCrash()));
        if (main.btc() != null) recovery.recordShockIfAny(raw, now);
        RiskState recovered = recovery.apply(raw, gen);
        // 跨机器保护：确认不了自己持有运行租约时不开新仓（另一台可能已接管）
        com.trendpilot.lease.ActiveLease l = lease.getIfAvailable();
        boolean leaseOk = l == null || l.canEnter();
        RiskState risk = leaseOk ? recovered : blockNew(recovered, "LEASE_UNCONFIRMED");
        RiskState v1Risk = leaseOk ? v1Raw : blockNew(v1Raw, "LEASE_UNCONFIRMED");
        String prevRegime = state.get("regime").orElse(null);
        if (prevRegime != null && !prevRegime.equals(risk.regime().name())) {
            notifier.send("regime:" + gen, Messages.regime(prevRegime, risk));
        }
        state.put("regime", risk.regime().name());

        // 4. 候选：评分达标的 EARLY + 已持仓且仍有信号的标的（加仓审核）
        Set<String> held = new HashSet<>(book.positions().stream().map(Position::symbol).toList());
        List<Candidate> candidates = bySymbol.values().stream()
                .filter(c -> (c.signal().early() && c.signal().score() >= params.review().minScore()) || held.contains(c.signal().symbol()))
                .toList();
        Map<String, OrderBook> fetchedBooks = fetchBooks(candidates);
        // 配置化影子变体：涨幅榜动量变体要看的几个币（Binance 主池），候选里没有的补抓盘口；出错只影响变体
        com.trendpilot.variant.VariantTracker.Round variantRound = variants.round();
        try {
            if (main.bars() != null) {
                java.util.Set<String> wanted = new java.util.LinkedHashSet<>(variantRound.momentumSymbols(main.universe(), main.bars().bars()));
                wanted.removeAll(fetchedBooks.keySet());
                if (!wanted.isEmpty()) fetchedBooks = new LinkedHashMap<>(fetchedBooks);
                for (String sym : wanted) {
                    try {
                        OrderBook ob = binance.depth().getCovering(sym, DEPTH_SPAN_PCT, 0);
                        if (ob != null) fetchedBooks.put(sym, ob);
                    } catch (RuntimeException e) {
                        log.debug("涨幅榜动量 {} 盘口获取失败，本轮不买：{}", sym, e.getMessage());
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("涨幅榜动量本轮准备出错，变体不买：{}", e.toString());
        }
        final Map<String, OrderBook> booksForRound = fetchedBooks;
        long booksFetchedNanos = System.nanoTime();

        // 研究：轮转 + 前 N 个 EARLY + 前 N 个价格异动；所有 EARLY 和候选都要有身份结论（只读本地资料）
        List<ResearchService.Target> universeTargets = pools.stream().flatMap(p -> p.universe().stream()
                        .filter(t -> !t.symbol().equals(UniverseFilter.BTC))
                        .map(t -> new ResearchService.Target(p.venue(), t, "ROTATION")))
                .toList();
        List<ResearchService.Target> earlyRanked = early.stream()
                .sorted(Comparator.comparingDouble((Candidate c) -> -c.signal().score()))
                .map(c -> new ResearchService.Target(c.venue(), c.ticker(), "EARLY")).toList();
        Map<String, ResearchService.Target> targets = new LinkedHashMap<>();
        research.selectTargets(universeTargets, earlyRanked).forEach(t -> targets.put(t.ticker().symbol(), t));
        java.util.stream.Stream.concat(early.stream(), candidates.stream())
                .forEach(c -> targets.putIfAbsent(c.signal().symbol(), new ResearchService.Target(c.venue(), c.ticker(), "EARLY")));
        Map<String, ResearchRecord> researched = research.researchAndRecord(gen, List.copyOf(targets.values()), now);
        Map<Venue, Pool> poolBy = new LinkedHashMap<>();
        pools.forEach(p -> poolBy.put(p.venue(), p));

        // 5. 审核与分配（锁内）
        List<String> entries = new ArrayList<>();
        List<String> v1Entries = new ArrayList<>();
        Map<String, ReviewResult> reviewed = new LinkedHashMap<>();
        Map<String, Boolean> fundedBy = new LinkedHashMap<>();
        Map<String, Double> firstSeen = new LinkedHashMap<>();
        List<SignalTracker.EntryInput> trackEntries = new ArrayList<>();
        Map<String, SignalTracker.SignalInput> trackSignals = new LinkedHashMap<>();
        lock.run(() -> {
            // 盘口是锁外抓的：研究资料或等锁耗时太久，盘口已经不代表现在能成交的价格，本轮不用它买入（记为缺盘口）
            Map<String, OrderBook> books = System.nanoTime() - booksFetchedNanos > MAX_BOOK_AGE.toNanos()
                    ? Map.of() : booksForRound;
            if (books.isEmpty() && !booksForRound.isEmpty()) log.warn("Discovery 盘口已超过 {} 秒，本轮不买入", MAX_BOOK_AGE.toSeconds());
            List<ReviewResult> results = new ArrayList<>();
            List<ReviewResult> relaxed = new ArrayList<>();
            // V2 已转正的参数改动（没有转正过时为空，V2 按基准审核，和以前完全一样）
            com.trendpilot.variant.VariantOverrides v2Overrides = paramSets.current();
            Map<String, Venue> venueOf = new LinkedHashMap<>();
            for (Candidate c : candidates) {
                EarlySignal s = c.signal();
                Pool pool = poolBy.get(c.venue());
                // Binance 扫描不完整时所有池都不推进；Bybit 池还要求自己完整
                boolean scanComplete = main.complete() && pool.complete();
                Instant signalAsOf = Instant.ofEpochMilli(pool.btc().hourly().getLast().closeTime() + 1);
                if (s.early()) book.touchEarlySeen(s.symbol(), s.price(), now);
                Position pos = book.position(s.symbol()).orElse(null);
                Instant lastExit = book.lastExit(s.symbol()).orElse(null);
                int earlySinceExit = lastExit == null ? 0 : book.earlyObservationsSince(s.symbol(), lastExit);
                boolean postExitHit = lastExit == null || !EarlySignalCalculator
                        .hitsAfter(pool.bars().bars().get(s.symbol()), s, lastExit).isEmpty();
                ReviewInput in = new ReviewInput(gen, signalAsOf, now, scanComplete, s, c.ticker(),
                        books.get(s.symbol()), risk, book.earlySeenPrice(s.symbol()).orElse(null), pos,
                        lastExit, researched.get(s.symbol()), earlySinceExit, postExitHit);
                ReviewResult v2 = v2Overrides.isEmpty() ? reviewer.review(in)
                        : com.trendpilot.variant.VariantRules.review(reviewer, params, in, v2Overrides);
                results.add(v2);
                venueOf.put(s.symbol(), c.venue());
                // 深度门槛放宽对照（V2D15）：V2 只因深度不足被拒的新开仓，用对照账本自己的卖出记录、放宽后的深度门槛重新审核
                if (pos == null && com.trendpilot.observation.DepthVariantTracker.rejectedOnlyForDepth(v2)) {
                    var vb = depthVariant.book();
                    Instant vExit = vb.lastExit(s.symbol()).orElse(null);
                    int vEarly = vExit == null ? 0 : book.earlyObservationsSince(s.symbol(), vExit);
                    boolean vHit = vExit == null || !EarlySignalCalculator
                            .hitsAfter(pool.bars().bars().get(s.symbol()), s, vExit).isEmpty();
                    relaxed.add(reviewer.review(new ReviewInput(gen, signalAsOf, now, scanComplete, s, c.ticker(),
                            books.get(s.symbol()), risk, book.earlySeenPrice(s.symbol()).orElse(null), null,
                            vExit, researched.get(s.symbol()), vEarly, vHit), depthVariant.minDepthUsdt()));
                }
                // 配置化影子变体（V5 强信号、V6…）：每个候选独立判断（不看 V2 结论），变体没有这个币时，用变体自己的卖出记录、
                // V2 的审核规则加变体的参数改动重新审核；出错只记日志，不影响 V2
                variantRound.review(s.symbol(), vb -> {
                    Instant vExit = vb.lastExit(s.symbol()).orElse(null);
                    int vEarly = vExit == null ? 0 : book.earlyObservationsSince(s.symbol(), vExit);
                    boolean vHit = vExit == null || !EarlySignalCalculator
                            .hitsAfter(pool.bars().bars().get(s.symbol()), s, vExit).isEmpty();
                    return new ReviewInput(gen, signalAsOf, now, scanComplete, s, c.ticker(),
                            books.get(s.symbol()), risk, book.earlySeenPrice(s.symbol()).orElse(null), null,
                            vExit, researched.get(s.symbol()), vEarly, vHit);
                });
            }
            // 不在候选中的 EARLY 只记录首次出现价格
            early.stream().filter(c -> !candidates.contains(c))
                    .forEach(c -> book.touchEarlySeen(c.signal().symbol(), c.signal().price(), now));
            results.forEach(r -> reviewed.put(r.symbol(), r));
            early.forEach(c -> book.earlySeenPrice(c.signal().symbol())
                    .ifPresent(v -> firstSeen.put(c.signal().symbol(), v.doubleValue())));

            List<ReviewResult> approved = results.stream().filter(ReviewResult::approved).toList();
            CapitalAllocator.Allocation alloc = allocator.allocate(approved, risk, book.deployedCost(), book.realizedPnl(),
                    book.unrealizedPnl());
            for (ReviewResult r : alloc.funded()) {
                if (r.action() == Action.BUY) {
                    book.open(venueOf.get(r.symbol()), r.symbol(), r.fill(), gen, now);
                } else {
                    book.add(book.position(r.symbol()).orElseThrow(), r.fill(), gen, now);
                }
                entries.add(r.action() + ":" + r.symbol());
                fundedBy.put(r.symbol(), true);
                notifier.send("entry:" + gen + ":" + r.symbol(), Messages.entry(venueOf.get(r.symbol()), r));
            }
            // V1 宽口径观察：所有 EARLY（不要求评分 8、不占 V2 资金）
            List<V1Tracker.Candidate> v1Candidates = early.stream()
                    .map(c -> new V1Tracker.Candidate(c.venue(), c.signal(), c.ticker(),
                            main.complete() && poolBy.get(c.venue()).complete(), researched.get(c.signal().symbol()).identity()))
                    .toList();
            v1Entries.addAll(v1.enter(gen, now, v1Candidates, v1Risk));
            depthVariant.enter(gen, now, venueOf, relaxed);
            try {
                variantRound.enter(gen, now, venueOf);
                variantRound.momentumEnter(gen, now, risk, main.complete(), books, Venue.BINANCE);
            } catch (RuntimeException e) {
                log.warn("影子变体本轮买入出错，跳过：{}", e.toString());
            }
            for (ReviewResult r : results) {
                logDecision(gen, venueOf.get(r.symbol()), r, alloc.funded().contains(r), alloc.unfunded().contains(r), now);
            }
        });
        // 信号跟踪：只观察，失败不影响 Discovery
        try {
            for (Candidate c : early) {
                String sym = c.signal().symbol();
                ReviewResult r = reviewed.get(sym);
                String result = r == null ? "NOT_REVIEWED"
                        : Boolean.TRUE.equals(fundedBy.get(sym)) ? "BUY" : r.approved() ? "BUDGET_WAIT" : r.decision().name();
                trackSignals.put(sym, new SignalTracker.SignalInput(c.venue(), c.signal(), c.ticker(), result,
                        r == null ? "" : String.join(",", r.reasons()), firstSeen.get(sym)));
            }
            for (Candidate c : candidates) {
                ReviewResult r = reviewed.get(c.signal().symbol());
                if (r == null || !Boolean.TRUE.equals(fundedBy.get(r.symbol()))) continue;
                SignalTracker.SignalInput in = trackSignals.getOrDefault(r.symbol(), new SignalTracker.SignalInput(
                        c.venue(), c.signal(), c.ticker(), r.action().name(), "", firstSeen.get(r.symbol())));
                trackEntries.add(new SignalTracker.EntryInput(in, r.fill().avgPrice().doubleValue()));
            }
            Instant tSig = Instant.ofEpochMilli(main.btc() == null ? now.toEpochMilli()
                    : main.btc().quarter().getLast().closeTime() + 1);
            tracker.onDiscovery(new SignalTracker.Context(gen, tSig, now, risk, quotes),
                    List.copyOf(trackSignals.values()), trackEntries);
        } catch (RuntimeException e) {
            log.warn("信号跟踪开样本失败：{}", e.getMessage(), e);
        }
        int universe = pools.stream().mapToInt(p -> p.universe().size()).sum();
        int missing = pools.stream().mapToInt(Pool::missing).sum();
        int requests = pools.stream().mapToInt(p -> p.bars() == null ? 0 : p.bars().requests()).sum();
        String poolNote = pools.stream().map(p -> p.venue() + "=" + (p.error() != null ? p.error()
                        : p.universe().size() + "/" + (p.complete() ? "COMPLETE" : "PARTIAL_DATA")))
                .collect(Collectors.joining(" "));
        return new Report(gen, universe, missing, early.size(), candidates.size(), entries, main.complete(), requests,
                poolNote, v1Entries);
    }

    /** 流动性（全市场最优价一次请求）与稳定币证据；拿不到时为 NaN，由风险评估保守处理。 */
    private MarketEvidence evidence(Map<String, BookTicker> quotes, Pool main, List<Pool> pools, Instant now) {
        double medianSpread = Double.NaN;
        try {
            List<Ticker24h> top = main.universe().stream()
                    .sorted(Comparator.comparingDouble((Ticker24h t) -> -t.quoteVolume())).limit(50).toList();
            List<Double> spreads = top.stream()
                    .map(t -> quotes.get(t.symbol())).filter(q -> q != null && q.bid() > 0 && q.ask() > 0)
                    .map(BookTicker::spreadBps).sorted().toList();
            // 至少要覆盖前列标的中的 10 个（池子更小时要求全部覆盖）
            if (!spreads.isEmpty() && spreads.size() >= Math.min(10, top.size())) {
                medianSpread = spreads.get(spreads.size() / 2);
            }
        } catch (RuntimeException e) {
            log.warn("流动性证据获取失败：{}", e.getMessage());
        }
        double stable = main.tickers().stream().filter(t -> STABLE_REFERENCES.contains(t.symbol()) && t.lastPrice() > 0)
                .mapToDouble(t -> Math.abs(t.lastPrice() - 1) * 100).max().orElse(Double.NaN);
        // 每个交易所分别算，取最差的那个：任何一个交易所的山寨崩了就熔断（熔断对所有交易所生效）
        double median1h = Double.NaN;
        String worst = "";
        for (Pool pool : pools) {
            double m = medianReturn1h(pool);
            if (!Double.isNaN(m) && (Double.isNaN(median1h) || m < median1h)) {
                median1h = m;
                worst = pool.venue().name();
            }
        }
        RiskRecovery.AltCrash crash = recovery.altCrash(median1h, now);
        if (crash.started()) {
            notifier.send("alt-crash:" + now, String.format(java.util.Locale.ROOT,
                    "🚨 山寨崩盘熔断：%s 1 小时涨跌中位数 %.2f%%（触发线 %.1f%%）\n已停止 V1/V2 新买入和加仓，不卖出任何持仓。"
                            + "\n中位数回到 %.1f%% 以上或满 %d 小时后解除，之后按恢复期逐步恢复。",
                    worst, median1h, params.risk().altCrashMedian1hPct(), params.risk().altCrashRecoverPct(),
                    params.risk().altCrashMaxHours()));
        } else if (crash.ended()) {
            notifier.send("alt-crash-end:" + now, String.format(java.util.Locale.ROOT,
                    "✅ 山寨崩盘熔断解除：各交易所 1 小时涨跌中位数最差 %.2f%%（%s），之后按恢复期逐步恢复买入", median1h, worst));
        }
        return new MarketEvidence(medianSpread, stable, recovery.confirmedShocks(now), crash.active());
    }

    /** 池内每个标的最近 4 根 15m K 线（约 1 小时）的涨跌，取中位数；样本不足 20 个时为 NaN。 */
    static double medianReturn1h(Pool main) {
        if (main.bars() == null) return Double.NaN;
        List<Double> rets = new ArrayList<>();
        for (SymbolBars b : main.bars().bars().values()) {
            List<com.trendpilot.market.Kline> q = b.quarter();
            if (q.size() < 4 || q.get(q.size() - 4).open() <= 0) continue;
            rets.add((q.getLast().close() / q.get(q.size() - 4).open() - 1) * 100);
        }
        if (rets.size() < 20) return Double.NaN;
        java.util.Collections.sort(rets);
        return rets.get(rets.size() / 2);
    }

    private Pool loadPool(VenueMarket m, Set<String> alreadyCovered, Instant now) {
        List<Ticker24h> tickers = m.tickers().tickers24h();
        Set<String> excluded = research.excludedSymbols(m.venue());
        List<Ticker24h> universe = universeFilter.select(m.directory().all(), tickers, alreadyCovered, excluded);
        List<String> names = new ArrayList<>(universe.stream().map(Ticker24h::symbol).toList());
        if (!names.contains(UniverseFilter.BTC)) {
            names.add(UniverseFilter.BTC);
        }
        // 之后的 15m K 线由 WebSocket 写入缓存：标的全集 + 持仓（V1/V2）+ 跟踪中的信号，掉出全集后也不用 REST 补
        Set<String> watched = new java.util.HashSet<>(names);
        book.positions().stream().filter(p -> p.venue() == m.venue()).forEach(p -> watched.add(p.symbol()));
        v1.positions().stream().filter(p -> p.venue() == m.venue()).forEach(p -> watched.add(p.symbol()));
        try {
            watched.addAll(tracker.trackingSymbols(m.venue()));
        } catch (RuntimeException e) {
            log.warn("跟踪标的读取失败：{}", e.getMessage());
        }
        streams.watchKlines(m.venue(), watched);
        BarsLoader.Result bars = m.bars().load(names, now);
        SymbolBars btc = bars.bars().get(UniverseFilter.BTC);
        double missingRatio = bars.missing().size() / (double) names.size();
        // 分类资料（稳定币、代币化股票）还没加载时不放行：交易所本身不标股票类，只靠名单会漏掉
        boolean categoriesLoaded = !excluded.isEmpty();
        if (!categoriesLoaded) log.warn("{} 分类资料缺失（CATEGORY_DATA_MISSING），本轮不做新买入", m.venue());
        boolean complete = btc != null && missingRatio <= params.universe().maxMissingRatio() && categoriesLoaded;
        return new Pool(m, tickers, universe, bars, btc, complete, categoriesLoaded ? null : "CATEGORY_DATA_MISSING");
    }

    private Map<String, OrderBook> fetchBooks(List<Candidate> candidates) {
        Map<String, OrderBook> out = new LinkedHashMap<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<OrderBook>> fs = new LinkedHashMap<>();
            candidates.forEach(c -> fs.put(c.signal().symbol(),
                    pool.submit(() -> markets.get(c.venue()).depth().getCovering(c.signal().symbol(), DEPTH_SPAN_PCT, 0))));
            for (var e : fs.entrySet()) {
                try {
                    out.put(e.getKey(), e.getValue().get());
                } catch (Exception ex) {
                    log.warn("盘口获取失败 {}：{}", e.getKey(), ex.getMessage()); // 缺盘口 → 审核为 SYSTEM_BLOCKED
                }
            }
        }
        return out;
    }

    private void logSignal(String gen, Candidate cand, Instant now) {
        EarlySignal s = cand.signal();
        jdbc.sql("""
                INSERT INTO signal_log(generation, venue, symbol, price, score, hits, rel1h, rel4h, accel, volume_accel,
                    compression, created_at)
                VALUES (:g, :venue, :s, :p, :score, :hits, :r1, :r4, :a, :v, :c, :t)
                """)
                .param("g", gen).param("venue", cand.venue().name()).param("s", s.symbol()).param("p", s.price()).param("score", s.score())
                .param("hits", String.join(",", s.hits())).param("r1", s.rel1h()).param("r4", s.rel4h())
                .param("a", s.accel()).param("v", s.volumeAccel()).param("c", s.compression())
                .param("t", now.toString()).update();
    }

    private void logDecision(String gen, Venue venue, ReviewResult r, boolean funded, boolean unfunded, Instant now) {
        List<String> reasons = new ArrayList<>(r.reasons());
        if (unfunded) reasons.add("BUDGET_WAIT");
        jdbc.sql("""
                INSERT INTO decision_log(generation, venue, symbol, action, decision, funded, score, rr, spread_bps,
                    slippage_bps, reasons, gaps, params_version, created_at, bid_depth, ask_depth)
                VALUES (:g, :venue, :s, :a, :d, :f, :score, :rr, :sp, :sl, :r, :gaps, :v, :t, :bd, :ad)
                """)
                .param("g", gen).param("venue", venue.name()).param("s", r.symbol()).param("a", r.action().name()).param("d", r.decision().name())
                .param("f", funded ? 1 : 0).param("score", r.score()).param("rr", nan(r.rr()))
                .param("sp", nan(r.spreadBps())).param("sl", nan(r.slippageBps()))
                .param("r", String.join(",", reasons)).param("gaps", String.join(",", r.gaps()))
                .param("v", params.version()).param("t", now.toString())
                .param("bd", nan(r.bidDepth())).param("ad", nan(r.askDepth())).update();
    }

    private static Double nan(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }
}
