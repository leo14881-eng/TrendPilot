package com.trendpilot.job;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import com.trendpilot.market.Markets;
import com.trendpilot.market.OrderBook;
import com.trendpilot.market.SymbolBars;
import com.trendpilot.market.SymbolInfo;
import com.trendpilot.market.Ticker24h;
import com.trendpilot.market.Venue;
import com.trendpilot.market.VenueMarket;
import com.trendpilot.notify.Messages;
import com.trendpilot.notify.Notifier;
import com.trendpilot.portfolio.Position;
import com.trendpilot.portfolio.ShadowBook;
import com.trendpilot.risk.RiskState;
import com.trendpilot.risk.SystemRiskAssessor;
import com.trendpilot.signal.EarlySignal;
import com.trendpilot.signal.EarlySignalCalculator;
import com.trendpilot.universe.UniverseFilter;

/**
 * Discovery（每小时 :17，越南时间）：行情全集（Binance 主池 + Bybit 独立池）→ EARLY → 系统风险
 * → Capital Review → 资金分配 → V2 账本。扫描不完整时只记录信号，不做新买入。
 */
@Component
public class DiscoveryJob {

    public static final String JOB = "DISCOVERY";
    static final int DEPTH_LIMIT = 500;

    private static final Logger log = LoggerFactory.getLogger(DiscoveryJob.class);

    private final Markets markets;
    private final UniverseFilter universeFilter;
    private final EarlySignalCalculator signals;
    private final SystemRiskAssessor riskAssessor;
    private final CapitalReviewer reviewer;
    private final CapitalAllocator allocator;
    private final ShadowBook book;
    private final PortfolioLock lock;
    private final Notifier notifier;
    private final JobRuns runs;
    private final AppState state;
    private final JdbcClient jdbc;
    private final StrategyParams params;

    public DiscoveryJob(Markets markets, UniverseFilter universeFilter, EarlySignalCalculator signals, SystemRiskAssessor riskAssessor, CapitalReviewer reviewer,
                        CapitalAllocator allocator, ShadowBook book, PortfolioLock lock, Notifier notifier,
                        JobRuns runs, AppState state, JdbcClient jdbc, StrategyParams params) {
        this.markets = markets;
        this.universeFilter = universeFilter;
        this.signals = signals;
        this.riskAssessor = riskAssessor;
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
                         boolean scanComplete, int klineRequests, String pools) {
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
                            + " pools=" + r.pools());
            log.info("Discovery 完成 {}", r);
            return r;
        } catch (RuntimeException e) {
            log.error("Discovery 失败 {}", gen, e);
            runs.record(JOB, gen, now, Instant.now(), "FAILED", "N/A", String.valueOf(e.getMessage()));
            notifier.send("fail:" + JOB + ":" + now.truncatedTo(ChronoUnit.HOURS),
                    Messages.failure(JOB, String.valueOf(e.getMessage())));
            return new Report(gen, 0, 0, 0, 0, List.of(), false, 0, "N/A");
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
                pools.add(loadPool(bybit, binanceListed, now));
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
                    if (s != null) bySymbol.putIfAbsent(t.symbol(), new Candidate(pool.venue(), s, t));
                }
            }
        }
        List<Candidate> early = bySymbol.values().stream().filter(c -> c.signal().early()).toList();
        early.forEach(c -> logSignal(gen, c, now));

        // 3. 系统风险（以 Binance 主池的 BTC 和市场广度为准）
        Ticker24h btcTicker = main.tickers().stream().filter(t -> t.symbol().equals(UniverseFilter.BTC)).findFirst().orElse(null);
        int lossExits = book.lossExitsSince(now.minus(Duration.ofHours(params.risk().lossWindowHours())));
        RiskState risk = riskAssessor.assess(main.btc(), btcTicker, main.universe(), lossExits);
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
        Map<String, OrderBook> books = fetchBooks(candidates);
        Map<Venue, Pool> poolBy = new LinkedHashMap<>();
        pools.forEach(p -> poolBy.put(p.venue(), p));

        // 5. 审核与分配（锁内）
        List<String> entries = new ArrayList<>();
        lock.run(() -> {
            List<ReviewResult> results = new ArrayList<>();
            Map<String, Venue> venueOf = new LinkedHashMap<>();
            for (Candidate c : candidates) {
                EarlySignal s = c.signal();
                Pool pool = poolBy.get(c.venue());
                // Binance 扫描不完整时所有池都不推进；Bybit 池还要求自己完整
                boolean scanComplete = main.complete() && pool.complete();
                Instant signalAsOf = Instant.ofEpochMilli(pool.btc().hourly().getLast().closeTime() + 1);
                if (s.early()) book.touchEarlySeen(s.symbol(), s.price(), now);
                Position pos = book.position(s.symbol()).orElse(null);
                ReviewInput in = new ReviewInput(gen, signalAsOf, now, scanComplete, s, c.ticker(),
                        books.get(s.symbol()), risk, book.earlySeenPrice(s.symbol()).orElse(null), pos,
                        book.lastExit(s.symbol()).orElse(null));
                results.add(reviewer.review(in));
                venueOf.put(s.symbol(), c.venue());
            }
            // 不在候选中的 EARLY 只记录首次出现价格
            early.stream().filter(c -> !candidates.contains(c))
                    .forEach(c -> book.touchEarlySeen(c.signal().symbol(), c.signal().price(), now));

            List<ReviewResult> approved = results.stream().filter(ReviewResult::approved).toList();
            CapitalAllocator.Allocation alloc = allocator.allocate(approved, risk, book.deployedCost(), book.realizedPnl());
            for (ReviewResult r : alloc.funded()) {
                if (r.action() == Action.BUY) {
                    book.open(venueOf.get(r.symbol()), r.symbol(), r.fill(), gen, now);
                } else {
                    book.add(book.position(r.symbol()).orElseThrow(), r.fill(), gen, now);
                }
                entries.add(r.action() + ":" + r.symbol());
                notifier.send("entry:" + gen + ":" + r.symbol(), Messages.entry(venueOf.get(r.symbol()), r));
            }
            for (ReviewResult r : results) {
                logDecision(gen, venueOf.get(r.symbol()), r, alloc.funded().contains(r), alloc.unfunded().contains(r), now);
            }
        });
        int universe = pools.stream().mapToInt(p -> p.universe().size()).sum();
        int missing = pools.stream().mapToInt(Pool::missing).sum();
        int requests = pools.stream().mapToInt(p -> p.bars() == null ? 0 : p.bars().requests()).sum();
        String poolNote = pools.stream().map(p -> p.venue() + "=" + (p.error() != null ? p.error()
                        : p.universe().size() + "/" + (p.complete() ? "COMPLETE" : "PARTIAL_DATA")))
                .collect(Collectors.joining(" "));
        return new Report(gen, universe, missing, early.size(), candidates.size(), entries, main.complete(), requests,
                poolNote);
    }

    private Pool loadPool(VenueMarket m, Set<String> alreadyCovered, Instant now) {
        List<Ticker24h> tickers = m.source().tickers24h();
        List<Ticker24h> universe = universeFilter.select(m.directory().all(), tickers, alreadyCovered);
        List<String> names = new ArrayList<>(universe.stream().map(Ticker24h::symbol).toList());
        if (!names.contains(UniverseFilter.BTC)) {
            names.add(UniverseFilter.BTC);
        }
        BarsLoader.Result bars = m.bars().load(names, now);
        SymbolBars btc = bars.bars().get(UniverseFilter.BTC);
        double missingRatio = bars.missing().size() / (double) names.size();
        boolean complete = btc != null && missingRatio <= params.universe().maxMissingRatio();
        return new Pool(m, tickers, universe, bars, btc, complete, null);
    }

    private Map<String, OrderBook> fetchBooks(List<Candidate> candidates) {
        Map<String, OrderBook> out = new LinkedHashMap<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<OrderBook>> fs = new LinkedHashMap<>();
            candidates.forEach(c -> fs.put(c.signal().symbol(),
                    pool.submit(() -> markets.get(c.venue()).depth().get(c.signal().symbol(), DEPTH_LIMIT))));
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
                    slippage_bps, reasons, gaps, params_version, created_at)
                VALUES (:g, :venue, :s, :a, :d, :f, :score, :rr, :sp, :sl, :r, :gaps, :v, :t)
                """)
                .param("g", gen).param("venue", venue.name()).param("s", r.symbol()).param("a", r.action().name()).param("d", r.decision().name())
                .param("f", funded ? 1 : 0).param("score", r.score()).param("rr", nan(r.rr()))
                .param("sp", nan(r.spreadBps())).param("sl", nan(r.slippageBps()))
                .param("r", String.join(",", reasons)).param("gaps", String.join(",", r.gaps()))
                .param("v", params.version()).param("t", now.toString()).update();
    }

    private static Double nan(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }
}
