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
import com.trendpilot.market.Kline;
import com.trendpilot.market.Markets;
import com.trendpilot.market.OrderBook;
import com.trendpilot.market.SymbolBars;
import com.trendpilot.market.SymbolInfo;
import com.trendpilot.market.Venue;
import com.trendpilot.market.BookTicker;
import com.trendpilot.market.stream.StreamHub;
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
import com.trendpilot.variant.ParallelExits;

/**
 * Monitor（每 5 分钟）：对每个持仓拉新鲜盘口和 K 线，按退出顺序判定。
 * 某个持仓的交易状态或盘口拿不到时，改用最优买价（WebSocket 或批量 bookTicker）做保守的利润保护判断，其他持仓照常管理。
 */
@Component
public class MonitorJob {

    public static final String JOB = "MONITOR";
    public static final String FAST_JOB = "MONITOR_FAST";
    /** 锁外抓的盘口在锁内使用时最多这么旧。 */
    static final java.time.Duration MAX_BOOK_AGE = java.time.Duration.ofSeconds(90);

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
    private final ParallelExits parallel;
    private final com.trendpilot.observation.DepthVariantTracker depthVariant;
    private final StreamHub hub;

    public MonitorJob(Markets markets, V1Tracker v1, EarlySignalCalculator signals,
                      ExitPolicy exitPolicy, ShadowBook book, PortfolioLock lock, Notifier notifier, JobRuns runs,
                      AppState state, ParallelExits parallel,
                      com.trendpilot.observation.DepthVariantTracker depthVariant, StreamHub hub) {
        this.depthVariant = depthVariant;
        this.hub = hub;
        this.parallel = parallel;
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

    private record Observed(SymbolInfo info, OrderBook book, long fetchedNanos) {
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
            // 行情都在锁外抓好，锁内只判断和记账：慢接口不会挡住 Discovery 和 FastWatch
            List<String> v1Exits = new ArrayList<>();
            try {
                var v1In = v1.fetch(now);
                lock.run(() -> v1Exits.addAll(v1.manage(gen, now, v1In)));
                v1.recordEquity(now);
            } catch (RuntimeException e) {
                log.warn("V1 对照本轮失败：{}", e.getMessage()); // 失败不影响 V2
            }
            // 深度放宽对照账本：失败不影响 V2 / V1
            try {
                var dIn = depthVariant.fetch(now);
                lock.run(() -> depthVariant.manage(gen, now, dIn));
                depthVariant.book().recordEquity(now);
            } catch (RuntimeException e) {
                log.warn("V2D15 对照本轮失败：{}", e.getMessage());
            }
            book.recordEquity(now);
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

    /**
     * FastWatch 发现可能跌破保护底线时立即确认：只针对这些持仓，丢掉盘口缓存重新取全数量盘口，
     * 按与 5 分钟检查完全相同的退出规则判断，确认就立刻卖出，不等下一轮。
     */
    public Report confirmNow(java.util.Set<String> symbols, Instant now) {
        return confirmNow(symbols, now, Map.of());
    }

    /** peakHints：FastWatch 在两次检查之间看到的最高可退出净利，用来把保护底线及时上调。 */
    public Report confirmNow(java.util.Set<String> symbols, Instant now, Map<String, java.math.BigDecimal> peakHints) {
        String gen = Generations.of("F", now);
        try {
            for (Position p : book.positions()) {
                if (symbols.contains(p.symbol()) && markets.get(p.venue()) != null) {
                    markets.get(p.venue()).depth().invalidate(p.symbol());
                }
            }
            Report r = monitor(gen, now, symbols, peakHints);
            runs.record(FAST_JOB, gen, now, Instant.now(), "SUCCESS", r.dataStatus(),
                    "symbols=" + symbols + " exits=" + r.exits());
            return r;
        } catch (RuntimeException e) {
            log.error("快速确认失败 {}", gen, e);
            runs.record(FAST_JOB, gen, now, Instant.now(), "FAILED", "N/A", String.valueOf(e.getMessage()));
            return new Report(gen, 0, List.of(), "N/A");
        }
    }

    private final SkipAlerts skipAlerts = new SkipAlerts();

    /** 最优买价兜底：WebSocket 60 秒内的报价优先（不占 REST），否则用批量 bookTicker（30 秒缓存）。都没有返回 null。 */
    private Double fallbackBid(Position p) {
        try {
            StreamHub.Quote q = hub.quote(p.venue(), p.symbol(), 60);
            if (q != null && q.bid() > 0) return q.bid();
        } catch (RuntimeException e) {
            log.debug("WebSocket 报价不可用 {}：{}", p.symbol(), e.toString());
        }
        VenueMarket m = markets.get(p.venue());
        if (m == null) return null;
        try {
            for (BookTicker t : m.tickers().bookTickers()) {
                if (t.symbol().equals(p.symbol())) return t.bid() > 0 ? t.bid() : null;
            }
        } catch (RuntimeException e) {
            log.warn("Monitor {} bookTicker 兜底也失败：{}", p.symbol(), cause(e));
        }
        return null;
    }

    /** 异常的根本原因，一行内说清（例如 HTTP 429、超时）。 */
    static String cause(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        msg = msg.replaceAll("\\s+", " ").strip();
        return msg.length() > 120 ? msg.substring(0, 120) + "…" : msg;
    }

    private Report monitor(String gen, Instant now) {
        return monitor(gen, now, null, Map.of());
    }

    private Report monitor(String gen, Instant now, java.util.Set<String> only,
                           Map<String, java.math.BigDecimal> peakHints) {
        List<Position> snapshot = book.positions().stream()
                .filter(p -> only == null || only.contains(p.symbol())).toList();
        if (snapshot.isEmpty()) {
            return new Report(gen, 0, List.of(), "N/A");
        }

        // 必需数据：交易状态（缓存的整表）+ 全数量盘口，都取持仓所在交易所的。
        // 按持仓隔离：某个持仓缺数据只跳过它本身，其他持仓照常管理。
        Map<String, Observed> observed = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        Map<String, String> why = new LinkedHashMap<>();
        List<String> delisted = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<Position, Future<SymbolInfo>> infos = new LinkedHashMap<>();
            Map<Position, Future<OrderBook>> books = new LinkedHashMap<>();
            for (Position p : snapshot) {
                VenueMarket m = markets.get(p.venue());
                if (m == null) {
                    missing.add(p.symbol());
                    why.put(p.symbol(), p.venue() + " 交易所未启用");
                    continue;
                }
                infos.put(p, pool.submit(() -> m.directory().get(p.symbol())));
                // 全数量卖出：买盘总额要覆盖持仓价值的 1.5 倍，否则取 500 档
                books.put(p, pool.submit(() -> m.depth().getCovering(p.symbol(), DiscoveryJob.DEPTH_SPAN_PCT,
                        p.costBasis().doubleValue() * 1.5)));
            }
            for (Position p : infos.keySet()) {
                SymbolInfo info;
                try {
                    info = infos.get(p).get();
                } catch (Exception ex) {
                    missing.add(p.symbol()); // 交易状态未知：不能判断是否下架，本持仓跳过
                    why.put(p.symbol(), p.venue() + " 交易状态：" + cause(ex));
                    continue;
                }
                OrderBook ob;
                String bookErr = "盘口数据异常";
                try {
                    ob = books.get(p).get();
                } catch (Exception ex) {
                    ob = null;
                    bookErr = cause(ex);
                }
                if (ob != null && ob.sane()) {
                    observed.put(p.symbol(), new Observed(info, ob, System.nanoTime())); // 下架但还有盘口：硬失效按盘口卖出
                } else if (info == null || !info.trading()) {
                    delisted.add(p.symbol());
                    markDelistedUnpriced(gen, now, p);
                } else {
                    missing.add(p.symbol());
                    why.put(p.symbol(), p.venue() + " 盘口：" + bookErr);
                }
            }
        }
        // 兜底：盘口或交易状态拿不到时，用最优买价（先 WebSocket，再批量 bookTicker，一次请求覆盖全部币）做保守的利润保护判断，
        // 不让持仓因为一个接口失败就错过止盈
        Map<String, Double> degraded = new LinkedHashMap<>();
        for (Position p : snapshot) {
            if (!missing.contains(p.symbol())) continue;
            Double bid = fallbackBid(p);
            if (bid == null) {
                why.merge(p.symbol(), "，最优买价也拿不到，本轮无法判断", String::concat);
            } else {
                degraded.put(p.symbol(), bid);
                why.merge(p.symbol(), "，已改用最优买价做利润保护判断", String::concat);
            }
        }
        if (!missing.isEmpty()) log.warn("Monitor 本轮缺完整数据的持仓：{}", why);
        // 告警只看 5 分钟全量轮次（FastWatch 只核对个别币，只记日志）：开始跳过时提醒，持续中 6 小时提醒一次，恢复时提醒一次
        if (only == null) {
            String alert = skipAlerts.onRound(missing.isEmpty() ? Map.of() : why, now);
            if (alert != null) notifier.send("monitor-skip:" + now.toEpochMilli(), alert);
        }

        // 信号指标（可选）：K 线走本地缓存，只有新收盘的 15m K 线才请求；每个交易所用自己的 BTC 基准；
        // 缺失时该持仓本轮不算有效观察
        Map<Venue, BarsLoader.Result> barsBy = new EnumMap<>(Venue.class);
        for (Venue v : snapshot.stream().map(Position::venue).distinct().toList()) {
            if (markets.get(v) == null) continue; // 交易所已停用：上面已记为缺数据
            List<String> names = new ArrayList<>(snapshot.stream().filter(p -> p.venue() == v).map(Position::symbol).toList());
            names.add(UniverseFilter.BTC);
            try {
                barsBy.put(v, markets.get(v).bars().load(names, now));
            } catch (RuntimeException e) {
                log.warn("Monitor {} K 线获取失败，本轮该交易所持仓没有信号指标：{}", v, e.getMessage());
            }
        }

        // 候选退出 X1 的影子并行需要买入时的日线 ATR：只为还没有的新持仓各取一次日线（锁外，失败就按默认距离）
        for (Position p : parallel.needDailyAtr(snapshot)) {
            VenueMarket m = markets.get(p.venue());
            if (m == null) continue;
            try {
                long t = now.toEpochMilli();
                List<double[]> daily = m.source().klines(p.symbol(), "1d", null, 16).stream()
                        .filter(k -> k.closeTime() < t).map(k -> new double[]{k.high(), k.low(), k.close()}).toList();
                double price = daily.isEmpty() ? Double.NaN : daily.getLast()[2];
                parallel.provideDailyAtr(p, com.trendpilot.variant.AtrStaleExit.atrDailyPct(daily, price));
            } catch (RuntimeException e) {
                log.debug("影子并行 {} 日线获取失败：{}", p.symbol(), e.toString());
            }
        }

        List<String> exits = new ArrayList<>();
        lock.run(() -> {
            for (Position current : book.positions()) {
                if (only != null && !only.contains(current.symbol())) continue;
                java.math.BigDecimal hint = peakHints.get(current.symbol());
                Position pos = hint != null && current.protectArmed() && hint.compareTo(current.peakNet()) > 0
                        ? current.withProtection(true, current.protectFloor(), hint) : current;
                Observed o = observed.get(pos.symbol());
                if (o == null) {
                    Double bid = degraded.get(pos.symbol());
                    if (bid != null) {
                        try {
                            applyDegraded(gen, now, pos, exitPolicy.evaluateDegraded(pos, bid, Instant.now()), exits);
                        } catch (RuntimeException e) {
                            log.error("Monitor {} 降级判断出错：{}", pos.symbol(), e.toString(), e);
                        }
                    }
                    continue; // 没有降级价格：抓取之后新开的仓，或者真的什么都拿不到
                }
                // 盘口是锁外抓的；等锁太久（被别的任务占着）这份盘口已经旧了，不用它做卖出判断
                if (System.nanoTime() - o.fetchedNanos() > MAX_BOOK_AGE.toNanos()) {
                    Double bid = fallbackBid(pos);
                    log.warn("Monitor {} 盘口已超过 {} 秒，{}", pos.symbol(), MAX_BOOK_AGE.toSeconds(),
                            bid == null ? "本轮不判断" : "改用最优买价做利润保护判断");
                    if (bid != null) applyDegraded(gen, now, pos, exitPolicy.evaluateDegraded(pos, bid, Instant.now()), exits);
                    continue;
                }
                try {
                BarsLoader.Result bars = barsBy.get(pos.venue());
                SymbolBars b = bars == null ? null : bars.bars().get(pos.symbol());
                SymbolBars btc = bars == null ? null : bars.bars().get(UniverseFilter.BTC);
                EarlySignal sig = (b == null || btc == null) ? null : signals.compute(b, btc);
                long hour = sig == null ? -1 : b.hourly().getLast().openTime();
                boolean fresh = sig != null && book.isNewObservation(pos.symbol(), hour);
                ExitDecision d = exitPolicy.evaluate(pos, o.info(), o.book(), sig, fresh);
                Kline lastHour = b == null || b.hourly().isEmpty() ? null : b.hourly().getLast();
                apply(gen, now, pos, d, exits, sig == null ? Double.NaN : sig.atr1hPct(),
                        lastHour == null ? Double.NaN : lastHour.close(), lastHour == null ? -1 : lastHour.openTime());
                if (fresh && !d.sell()) book.markObserved(pos.symbol(), hour);
                } catch (RuntimeException e) {
                    // 一个持仓的数据异常不能让其他持仓失去管理
                    log.error("Monitor {} 判断出错，跳过：{}", pos.symbol(), e.toString(), e);
                    notifier.send("monitor-error:" + pos.symbol() + ":" + now.truncatedTo(ChronoUnit.HOURS),
                            "⚠️ Monitor 判断 " + pos.symbol() + " 时出错（" + e.getClass().getSimpleName() + "），本轮跳过，其他持仓照常管理");
                }
            }
        });
        boolean complete = barsBy.values().stream().allMatch(BarsLoader.Result::complete);
        String status = complete && missing.isEmpty() && delisted.isEmpty() ? "COMPLETE" : "PARTIAL_DATA";
        if (!missing.isEmpty()) status += ":SKIPPED=" + missing;
        if (!delisted.isEmpty()) status += ":DELISTED_UNPRICED=" + delisted;
        return new Report(gen, snapshot.size(), exits, status);
    }

    /**
     * 已下架且拿不到盘口：没有可信的卖出价格，不凭空构造成交。持仓冻结在账上，浮亏按最后一次可信标记保留，
     * 每个持仓只记录一次 DELISTED_UNPRICED 事件并提醒。
     */
    private void markDelistedUnpriced(String gen, Instant now, Position pos) {
        String flag = "delisted:" + pos.symbol() + ":" + pos.openedAt();
        if (state.get(flag).isPresent()) return;
        state.put(flag, now.toString());
        lock.run(() -> book.note(pos, "DELISTED_UNPRICED", gen, now));
        notifier.send("note:DELISTED_UNPRICED:" + flag, "⚠️ V2 " + pos.symbol() + Messages.venueTag(pos.venue())
                + " 已停止交易且拿不到盘口：无法模拟卖出，持仓冻结在账上，浮亏按最后一次标记计入");
    }

    /** 降级判断的结果：卖出照常记账；不卖时只更新持仓状态（启动保护、底线），不喂给对比账本，避免折价净利污染对比。 */
    private void applyDegraded(String gen, Instant now, Position pos, ExitDecision d, List<String> exits) {
        if (d.sell()) {
            apply(gen, now, pos, d, exits, Double.NaN, Double.NaN, -1);
            return;
        }
        String posKey = pos.symbol() + ":" + pos.openedAt();
        for (String note : d.notes()) {
            book.note(pos, note, gen, now);
            notifier.send("note:" + note + ":" + posKey + ":" + gen, Messages.note(pos.symbol(), note, d));
        }
        book.update(d.updated(), d.netPnl(), now);
    }

    private void apply(String gen, Instant now, Position pos, ExitDecision d, List<String> exits, double atrPct,
                       double hourClose, long hourOpen) {
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
            parallel.closeWithV2(pos, d, now);
        } else {
            book.update(d.updated(), d.netPnl(), now);
            parallel.observe(pos, d, atrPct, hourClose, hourOpen, now);
        }
    }
}
