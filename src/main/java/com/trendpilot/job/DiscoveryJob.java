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
import com.trendpilot.market.DepthCache;
import com.trendpilot.market.MarketDataSource;
import com.trendpilot.market.OrderBook;
import com.trendpilot.market.SymbolBars;
import com.trendpilot.market.SymbolDirectory;
import com.trendpilot.market.Ticker24h;
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
 * Discovery（每小时 :17，越南时间）：行情全集 → EARLY → 系统风险 → Capital Review → 资金分配 → V2 账本。
 * 扫描不完整时只记录信号，不做新买入。
 */
@Component
public class DiscoveryJob {

    public static final String JOB = "DISCOVERY";
    static final int DEPTH_LIMIT = 500;

    private static final Logger log = LoggerFactory.getLogger(DiscoveryJob.class);

    private final MarketDataSource market;
    private final SymbolDirectory directory;
    private final DepthCache depthCache;
    private final UniverseFilter universeFilter;
    private final BarsLoader barsLoader;
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

    public DiscoveryJob(MarketDataSource market, SymbolDirectory directory, DepthCache depthCache, UniverseFilter universeFilter, BarsLoader barsLoader,
                        EarlySignalCalculator signals, SystemRiskAssessor riskAssessor, CapitalReviewer reviewer,
                        CapitalAllocator allocator, ShadowBook book, PortfolioLock lock, Notifier notifier,
                        JobRuns runs, AppState state, JdbcClient jdbc, StrategyParams params) {
        this.market = market;
        this.directory = directory;
        this.depthCache = depthCache;
        this.universeFilter = universeFilter;
        this.barsLoader = barsLoader;
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
                         boolean scanComplete, int klineRequests) {
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
                            + " reviewed=" + r.reviewed() + " klineRequests=" + r.klineRequests() + " entries=" + r.entries());
            log.info("Discovery 完成 {}", r);
            return r;
        } catch (RuntimeException e) {
            log.error("Discovery 失败 {}", gen, e);
            runs.record(JOB, gen, now, Instant.now(), "FAILED", "N/A", String.valueOf(e.getMessage()));
            notifier.send("fail:" + JOB + ":" + now.truncatedTo(ChronoUnit.HOURS),
                    Messages.failure(JOB, String.valueOf(e.getMessage())));
            return new Report(gen, 0, 0, 0, 0, List.of(), false, 0);
        }
    }

    private Report discover(String gen, Instant now) {
        // 1. 行情全集与 K 线（锁外）：全量 24h 行情一次请求，交易对信息走缓存，K 线只增量拉取
        List<Ticker24h> tickers = market.tickers24h();
        List<Ticker24h> universe = universeFilter.select(directory.all(), tickers);
        List<String> names = new ArrayList<>(universe.stream().map(Ticker24h::symbol).toList());
        if (!names.contains(UniverseFilter.BTC)) {
            names.add(UniverseFilter.BTC);
        }
        BarsLoader.Result bars = barsLoader.load(names, now);
        SymbolBars btc = bars.bars().get(UniverseFilter.BTC);
        double missingRatio = bars.missing().size() / (double) names.size();
        boolean scanComplete = btc != null && missingRatio <= params.universe().maxMissingRatio();

        // 2. EARLY 信号（Binance BTC 基准缺失则不计算信号）
        Map<String, EarlySignal> bySymbol = new LinkedHashMap<>();
        if (btc != null) {
            for (Ticker24h t : universe) {
                SymbolBars b = bars.bars().get(t.symbol());
                if (b != null && !t.symbol().equals(UniverseFilter.BTC)) {
                    EarlySignal s = signals.compute(b, btc);
                    if (s != null) bySymbol.put(t.symbol(), s);
                }
            }
        }
        List<EarlySignal> early = bySymbol.values().stream().filter(EarlySignal::early).toList();
        early.forEach(s -> logSignal(gen, s, now));

        // 3. 系统风险
        Ticker24h btcTicker = tickers.stream().filter(t -> t.symbol().equals(UniverseFilter.BTC)).findFirst().orElse(null);
        int lossExits = book.lossExitsSince(now.minus(Duration.ofHours(params.risk().lossWindowHours())));
        RiskState risk = riskAssessor.assess(btc, btcTicker, universe, lossExits);
        String prevRegime = state.get("regime").orElse(null);
        if (prevRegime != null && !prevRegime.equals(risk.regime().name())) {
            notifier.send("regime:" + gen, Messages.regime(prevRegime, risk));
        }
        state.put("regime", risk.regime().name());

        // 4. 候选：评分达标的 EARLY + 已持仓且仍有信号的标的（加仓审核）
        Set<String> held = new HashSet<>(book.positions().stream().map(Position::symbol).toList());
        List<EarlySignal> candidates = bySymbol.values().stream()
                .filter(s -> (s.early() && s.score() >= params.review().minScore()) || held.contains(s.symbol()))
                .toList();
        Map<String, OrderBook> books = fetchBooks(candidates.stream().map(EarlySignal::symbol).toList());
        Map<String, Ticker24h> tickerBy = new LinkedHashMap<>();
        universe.forEach(t -> tickerBy.put(t.symbol(), t));
        Instant signalAsOf = btc == null ? now : Instant.ofEpochMilli(btc.hourly().getLast().closeTime() + 1);

        // 5. 审核与分配（锁内）
        List<String> entries = new ArrayList<>();
        lock.run(() -> {
            List<ReviewResult> results = new ArrayList<>();
            for (EarlySignal s : candidates) {
                if (s.early()) book.touchEarlySeen(s.symbol(), s.price(), now);
                Position pos = book.position(s.symbol()).orElse(null);
                ReviewInput in = new ReviewInput(gen, signalAsOf, now, scanComplete, s, tickerBy.get(s.symbol()),
                        books.get(s.symbol()), risk, book.earlySeenPrice(s.symbol()).orElse(null), pos,
                        book.lastExit(s.symbol()).orElse(null));
                results.add(reviewer.review(in));
            }
            // 不在候选中的 EARLY 只记录首次出现价格
            early.stream().filter(s -> !candidates.contains(s)).forEach(s -> book.touchEarlySeen(s.symbol(), s.price(), now));

            List<ReviewResult> approved = results.stream().filter(ReviewResult::approved).toList();
            CapitalAllocator.Allocation alloc = allocator.allocate(approved, risk, book.deployedCost(), book.realizedPnl());
            for (ReviewResult r : alloc.funded()) {
                if (r.action() == Action.BUY) {
                    book.open(r.symbol(), r.fill(), gen, now);
                } else {
                    book.add(book.position(r.symbol()).orElseThrow(), r.fill(), gen, now);
                }
                entries.add(r.action() + ":" + r.symbol());
                notifier.send("entry:" + gen + ":" + r.symbol(), Messages.entry(r));
            }
            for (ReviewResult r : results) {
                logDecision(gen, r, alloc.funded().contains(r), alloc.unfunded().contains(r), now);
            }
        });
        return new Report(gen, universe.size(), bars.missing().size(), early.size(), candidates.size(), entries,
                scanComplete, bars.requests());
    }

    private Map<String, OrderBook> fetchBooks(List<String> symbols) {
        Map<String, OrderBook> out = new LinkedHashMap<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<OrderBook>> fs = new LinkedHashMap<>();
            symbols.forEach(s -> fs.put(s, pool.submit(() -> depthCache.get(s, DEPTH_LIMIT))));
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

    private void logSignal(String gen, EarlySignal s, Instant now) {
        jdbc.sql("""
                INSERT INTO signal_log(generation, symbol, price, score, hits, rel1h, rel4h, accel, volume_accel,
                    compression, created_at)
                VALUES (:g, :s, :p, :score, :hits, :r1, :r4, :a, :v, :c, :t)
                """)
                .param("g", gen).param("s", s.symbol()).param("p", s.price()).param("score", s.score())
                .param("hits", String.join(",", s.hits())).param("r1", s.rel1h()).param("r4", s.rel4h())
                .param("a", s.accel()).param("v", s.volumeAccel()).param("c", s.compression())
                .param("t", now.toString()).update();
    }

    private void logDecision(String gen, ReviewResult r, boolean funded, boolean unfunded, Instant now) {
        List<String> reasons = new ArrayList<>(r.reasons());
        if (unfunded) reasons.add("BUDGET_WAIT");
        jdbc.sql("""
                INSERT INTO decision_log(generation, symbol, action, decision, funded, score, rr, spread_bps,
                    slippage_bps, reasons, gaps, params_version, created_at)
                VALUES (:g, :s, :a, :d, :f, :score, :rr, :sp, :sl, :r, :gaps, :v, :t)
                """)
                .param("g", gen).param("s", r.symbol()).param("a", r.action().name()).param("d", r.decision().name())
                .param("f", funded ? 1 : 0).param("score", r.score()).param("rr", nan(r.rr()))
                .param("sp", nan(r.spreadBps())).param("sl", nan(r.slippageBps()))
                .param("r", String.join(",", reasons)).param("gaps", String.join(",", r.gaps()))
                .param("v", params.version()).param("t", now.toString()).update();
    }

    private static Double nan(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }
}
