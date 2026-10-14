package com.trendpilot.job;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.trendpilot.market.BarsLoader;
import com.trendpilot.market.MarketDataSource;
import com.trendpilot.market.OrderBook;
import com.trendpilot.market.SymbolBars;
import com.trendpilot.market.SymbolInfo;
import com.trendpilot.monitor.ExitDecision;
import com.trendpilot.monitor.ExitPolicy;
import com.trendpilot.notify.Messages;
import com.trendpilot.notify.Notifier;
import com.trendpilot.portfolio.Position;
import com.trendpilot.portfolio.ShadowBook;
import com.trendpilot.signal.EarlySignal;
import com.trendpilot.signal.EarlySignalCalculator;
import com.trendpilot.universe.UniverseFilter;

/**
 * Monitor（每 5 分钟）：对每个持仓拉新鲜盘口和 K 线，按退出顺序判定。
 * 任何持仓的交易状态或盘口拿不到时，本轮整体停止，不用旧数据做决定。
 */
@Component
public class MonitorJob {

    public static final String JOB = "MONITOR";

    private static final Logger log = LoggerFactory.getLogger(MonitorJob.class);

    private final MarketDataSource market;
    private final BarsLoader barsLoader;
    private final EarlySignalCalculator signals;
    private final ExitPolicy exitPolicy;
    private final ShadowBook book;
    private final PortfolioLock lock;
    private final Notifier notifier;
    private final JobRuns runs;
    private final AppState state;

    public MonitorJob(MarketDataSource market, BarsLoader barsLoader, EarlySignalCalculator signals,
                      ExitPolicy exitPolicy, ShadowBook book, PortfolioLock lock, Notifier notifier, JobRuns runs,
                      AppState state) {
        this.market = market;
        this.barsLoader = barsLoader;
        this.signals = signals;
        this.exitPolicy = exitPolicy;
        this.book = book;
        this.lock = lock;
        this.notifier = notifier;
        this.runs = runs;
        this.state = state;
    }

    public record Report(String generation, int positions, List<String> exits, String dataStatus) {
    }

    private record Observed(SymbolInfo info, OrderBook book) {
    }

    @Scheduled(cron = "0 */5 * * * *", zone = "${trendpilot.zone}")
    public void scheduled() {
        runOnce(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    public Report runOnce(Instant now) {
        String gen = Generations.of("M", now);
        try {
            Report r = monitor(gen, now);
            runs.record(JOB, gen, now, Instant.now(), "SUCCESS", r.dataStatus(),
                    "positions=" + r.positions() + " exits=" + r.exits());
            return r;
        } catch (RuntimeException e) {
            log.error("Monitor 失败 {}", gen, e);
            runs.record(JOB, gen, now, Instant.now(), "FAILED", "N/A", String.valueOf(e.getMessage()));
            notifier.send("fail:" + JOB + ":" + now.truncatedTo(ChronoUnit.HOURS),
                    Messages.failure(JOB, String.valueOf(e.getMessage())));
            return new Report(gen, 0, List.of(), "N/A");
        }
    }

    private Report monitor(String gen, Instant now) {
        List<Position> snapshot = book.positions();
        if (snapshot.isEmpty()) {
            return new Report(gen, 0, List.of(), "N/A");
        }
        List<String> symbols = snapshot.stream().map(Position::symbol).toList();

        // 必需数据：交易状态 + 全数量盘口；缺任何一个就停止本轮
        Map<String, Observed> observed = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<Observed>> fs = new LinkedHashMap<>();
            symbols.forEach(s -> fs.put(s, pool.submit(
                    () -> new Observed(market.symbol(s), market.depth(s, DiscoveryJob.DEPTH_LIMIT)))));
            for (var e : fs.entrySet()) {
                try {
                    Observed o = e.getValue().get();
                    if (o.book() == null || !o.book().usable()) missing.add(e.getKey());
                    else observed.put(e.getKey(), o);
                } catch (Exception ex) {
                    missing.add(e.getKey());
                }
            }
        }
        if (!missing.isEmpty()) {
            log.warn("Monitor 本轮停止，必需数据缺失：{}", missing);
            return new Report(gen, snapshot.size(), List.of(), "STOPPED_MISSING:" + missing);
        }

        // 信号指标（可选）：K 线缺失时该持仓本轮不算有效观察
        List<String> barNames = new ArrayList<>(symbols);
        barNames.add(UniverseFilter.BTC);
        BarsLoader.Result bars = barsLoader.load(barNames, now);
        SymbolBars btc = bars.bars().get(UniverseFilter.BTC);

        List<String> exits = new ArrayList<>();
        lock.run(() -> {
            for (Position pos : book.positions()) {
                Observed o = observed.get(pos.symbol());
                if (o == null) {
                    continue; // 抓取之后新开的仓，下轮再看
                }
                SymbolBars b = bars.bars().get(pos.symbol());
                EarlySignal sig = (b == null || btc == null) ? null : signals.compute(b, btc);
                ExitDecision d = exitPolicy.evaluate(pos, o.info(), o.book(), sig);
                apply(gen, now, pos, d, exits);
            }
        });
        return new Report(gen, snapshot.size(), exits, bars.complete() ? "COMPLETE" : "PARTIAL_DATA");
    }

    private void apply(String gen, Instant now, Position pos, ExitDecision d, List<String> exits) {
        String posKey = pos.symbol() + ":" + pos.openedAt();
        for (String note : d.notes()) {
            if (note.equals("PROTECTION_GAPPED")) {
                // 每个持仓只记一次
                String flag = "gapped:" + posKey;
                if (state.get(flag).isPresent()) continue;
                state.put(flag, now.toString());
            }
            book.note(pos.symbol(), note, gen, now);
            notifier.send("note:" + note + ":" + posKey + ":" + gen, Messages.note(pos.symbol(), note, d));
        }
        if (d.sell()) {
            book.close(pos, d.fill(), d.netPnl(), d.reason(), gen, now);
            exits.add(d.reason() + ":" + pos.symbol());
            notifier.send("exit:" + posKey, Messages.exit(d));
        } else {
            book.update(d.updated(), now);
        }
    }
}
