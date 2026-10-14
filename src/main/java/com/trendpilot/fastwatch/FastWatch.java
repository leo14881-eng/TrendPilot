package com.trendpilot.fastwatch;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.market.Venue;
import com.trendpilot.market.stream.StreamHub;
import com.trendpilot.notify.Messages;
import com.trendpilot.notify.Notifier;
import com.trendpilot.portfolio.Position;
import com.trendpilot.portfolio.ShadowBook;

/**
 * FastWatch 快速观察（每 15 秒）：用 WebSocket 最优价观察 V2 持仓的价格、价差和利润保护。
 * 自己不写正式组合；发现可能跌破保护底线（或已跌到亏损）时，立即调用 Monitor 的快速确认：
 * 重新取该币全数量盘口，按正式退出规则判断，确认就马上卖出，不等 5 分钟检查。
 * 同一持仓 60 秒内最多确认一次。
 */
@Component
public class FastWatch {

    static final long MAX_QUOTE_AGE_SECONDS = 30;
    private static final MathContext MC = MathContext.DECIMAL64;

    private final ShadowBook book;
    private final StreamHub hub;
    private final JdbcClient jdbc;
    private final Notifier notifier;
    private final StrategyParams params;
    /** 每个持仓上一次的观察结论，只在变化时记录。 */
    private final Map<String, String> lastKind = new ConcurrentHashMap<>();
    /** 每个持仓上一次快速确认的时间。 */
    private final Map<String, Instant> lastConfirm = new ConcurrentHashMap<>();
    /** 两次 Monitor 之间看到的最高可退出净利（已启动保护的持仓）。 */
    private final Map<String, BigDecimal> peakSeen = new ConcurrentHashMap<>();
    private final com.trendpilot.job.MonitorJob monitor;
    static final long CONFIRM_GAP_SECONDS = 60;

    public FastWatch(ShadowBook book, StreamHub hub, JdbcClient jdbc, Notifier notifier, StrategyParams params,
                     @org.springframework.context.annotation.Lazy com.trendpilot.job.MonitorJob monitor) {
        this.monitor = monitor;
        this.book = book;
        this.hub = hub;
        this.jdbc = jdbc;
        this.notifier = notifier;
        this.params = params;
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 30_000)
    public void scheduled() {
        observe(Instant.now());
    }

    public void observe(Instant now) {
        List<Position> positions = book.positions();
        for (Venue v : Venue.values()) {
            Set<String> symbols = positions.stream().filter(p -> p.venue() == v).map(Position::symbol)
                    .collect(Collectors.toSet());
            hub.watchBooks(v, symbols);
        }
        lastKind.keySet().removeIf(k -> positions.stream().noneMatch(p -> key(p).equals(k)));
        lastConfirm.keySet().removeIf(k -> positions.stream().noneMatch(p -> key(p).equals(k)));
        peakSeen.keySet().removeIf(k -> positions.stream().noneMatch(p -> key(p).equals(k)));
        Map<String, BigDecimal> hints = new java.util.HashMap<>();
        Set<String> confirm = new java.util.HashSet<>();
        for (Position p : positions) {
            StreamHub.Quote q = hub.quote(p.venue(), p.symbol(), MAX_QUOTE_AGE_SECONDS);
            if (q == null || q.bid() <= 0 || q.ask() <= 0) continue; // 没有新鲜价格就不观察
            String kind = observe(p, q.bid(), q.ask(), now);
            if (kind.equals("WOULD_PROTECT_SELL") || kind.equals("GAPPED")) {
                Instant last = lastConfirm.get(key(p));
                if (last == null || now.isAfter(last.plusSeconds(CONFIRM_GAP_SECONDS))) {
                    lastConfirm.put(key(p), now);
                    confirm.add(p.symbol());
                    BigDecimal seen = peakSeen.get(key(p));
                    if (seen != null) hints.put(p.symbol(), seen);
                }
            }
        }
        if (!confirm.isEmpty()) monitor.confirmNow(confirm, now, hints);
    }

    /** @return 本次观察结论（HOLD / WOULD_ARM / WOULD_PROTECT_SELL / GAPPED） */
    String observe(Position p, double bid, double ask, Instant now) {
        StrategyParams.Exit e = params.exit();
        BigDecimal proceeds = BigDecimal.valueOf(bid).multiply(p.qty(), MC)
                .multiply(BigDecimal.valueOf(1 - params.feeRate()), MC);
        double net = proceeds.subtract(p.costBasis()).doubleValue();
        double mid = (bid + ask) / 2;
        double rawRet = (mid / p.avgEntryPrice().doubleValue() - 1) * 100;
        double spreadBps = (ask - bid) / mid * 10_000;

        String kind = "HOLD";
        // 底线按实时最高净利估算（与 Monitor 同一公式），避免两次检查之间的新高没被计入
        BigDecimal peak = p.peakNet().max(BigDecimal.valueOf(net));
        if (p.protectArmed()) peakSeen.merge(key(p), peak, BigDecimal::max);
        peak = peak.max(peakSeen.getOrDefault(key(p), BigDecimal.ZERO));
        double floor = com.trendpilot.monitor.ExitPolicy.protectionFloor(p.costBasis(), peak, p.protectFloor(), e)
                .doubleValue();
        if (p.protectArmed()) {
            if (net <= 0) kind = "GAPPED";
            else if (net < floor) kind = "WOULD_PROTECT_SELL";
        } else if (rawRet >= e.protectArmPct() && net > 0) {
            kind = "WOULD_ARM";
        }
        String prev = lastKind.put(key(p), kind);
        if (!kind.equals("HOLD") && !kind.equals(prev)) {
            jdbc.sql("""
                    INSERT INTO fastwatch_observation(venue, symbol, kind, bid, ask, spread_bps, est_net, raw_ret_pct, created_at)
                    VALUES (:v, :s, :k, :b, :a, :sp, :n, :r, :t)
                    """)
                    .param("v", p.venue().name()).param("s", p.symbol()).param("k", kind).param("b", bid).param("a", ask)
                    .param("sp", spreadBps).param("n", net).param("r", rawRet).param("t", now.toString()).update();
            if (kind.equals("WOULD_PROTECT_SELL")) {
                notifier.send("fastwatch:" + key(p) + ":" + now.getEpochSecond() / 3600, String.format(Locale.ROOT,
                        "👀 FastWatch：V2 %s%s 估算净利 %.2f U 已低于保护底线 %.2f U\n正在立即用全数量盘口确认，确认后马上卖出",
                        p.symbol(), Messages.venueTag(p.venue()), net, floor));
            }
        }
        return kind;
    }

    private static String key(Position p) {
        return p.venue() + ":" + p.symbol() + ":" + p.openedAt();
    }
}
