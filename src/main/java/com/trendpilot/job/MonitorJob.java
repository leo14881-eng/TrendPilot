package com.trendpilot.job;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
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
import com.trendpilot.market.Markets;
import com.trendpilot.market.OrderBook;
import com.trendpilot.market.SymbolBars;
import com.trendpilot.market.SymbolInfo;
import com.trendpilot.market.Venue;
import com.trendpilot.market.VenueMarket;
import com.trendpilot.monitor.ExitDecision;
import com.trendpilot.monitor.ExitPolicy;
import com.trendpilot.notify.Messages;
import com.trendpilot.observation.V1Tracker;
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

    private final Markets markets;
    private final V1Tracker v1;
    private final EarlySignalCalculator signals;
    private final ExitPolicy exitPolicy;
    private final ShadowBook book;
    private final PortfolioLock lock;
    private final Notifier notifier;
    private final JobRuns runs;
    private final AppState state;

    public MonitorJob(Markets markets, V1Tracker v1, EarlySignalCalculator signals,
                      ExitPolicy exitPolicy, ShadowBook book, PortfolioLock lock, Notifier notifier, JobRuns runs,
                      AppState state) {
        this.markets = markets;
        this.v1 = v1;
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
            // V1 观察账本与 V2 分开：V2 本轮因缺数据停止时，V1 仍用自己的参考价判断
            List<String> v1Exits = new ArrayList<>();
            lock.run(() -> v1Exits.addAll(v1.manage(gen, now)));
            runs.record(JOB, gen, now, Instant.now(), "SUCCESS", r.dataStatus(),
                    "positions=" + r.positions() + " exits=" + r.exits() + " v1Exits=" + v1Exits);
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

        // 必需数据：交易状态（缓存的整表）+ 全数量盘口，都取持仓所在交易所的；缺任何一个就停止本轮
        Map<String, Observed> observed = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<Observed>> fs = new LinkedHashMap<>();
            for (Position p : snapshot) {
                VenueMarket m = markets.get(p.venue());
                if (m == null) {
                    missing.add(p.symbol());
                    continue;
                }
                fs.put(p.symbol(), pool.submit(() -> new Observed(m.directory().get(p.symbol()),
                        m.depth().get(p.symbol(), DiscoveryJob.DEPTH_LIMIT))));
            }
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

        // 信号指标（可选）：K 线走本地缓存，只有新收盘的 15m K 线才请求；每个交易所用自己的 BTC 基准；
        // 缺失时该持仓本轮不算有效观察
        Map<Venue, BarsLoader.Result> barsBy = new EnumMap<>(Venue.class);
        for (Venue v : snapshot.stream().map(Position::venue).distinct().toList()) {
            List<String> names = new ArrayList<>(snapshot.stream().filter(p -> p.venue() == v).map(Position::symbol).toList());
            names.add(UniverseFilter.BTC);
            barsBy.put(v, markets.get(v).bars().load(names, now));
        }

        List<String> exits = new ArrayList<>();
        lock.run(() -> {
            for (Position pos : book.positions()) {
                Observed o = observed.get(pos.symbol());
                if (o == null) {
                    continue; // 抓取之后新开的仓，下轮再看
                }
                BarsLoader.Result bars = barsBy.get(pos.venue());
                SymbolBars b = bars == null ? null : bars.bars().get(pos.symbol());
                SymbolBars btc = bars == null ? null : bars.bars().get(UniverseFilter.BTC);
                EarlySignal sig = (b == null || btc == null) ? null : signals.compute(b, btc);
                ExitDecision d = exitPolicy.evaluate(pos, o.info(), o.book(), sig);
                apply(gen, now, pos, d, exits);
            }
        });
        boolean complete = barsBy.values().stream().allMatch(BarsLoader.Result::complete);
        return new Report(gen, snapshot.size(), exits, complete ? "COMPLETE" : "PARTIAL_DATA");
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
            book.note(pos, note, gen, now);
            notifier.send("note:" + note + ":" + posKey + ":" + gen, Messages.note(pos.symbol(), note, d));
        }
        if (d.sell()) {
            book.close(pos, d.fill(), d.netPnl(), d.reason(), gen, now);
            exits.add(d.reason() + ":" + pos.symbol());
            notifier.send("exit:" + posKey, Messages.exit(pos.venue(), d));
        } else {
            book.update(d.updated(), d.netPnl(), now);
        }
    }
}
