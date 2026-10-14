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
 * FastWatch 快速观察（每 15 秒）：用 WebSocket 最优价观察 V2 持仓的价格、价差和假设的利润保护。
 * formal_writer = false：只写观察表，不改正式组合，也不产生正式 SELL；
 * 即使观察到卖出条件，正式卖出仍由 5 分钟 Monitor 用全数量盘口确认。
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

    public FastWatch(ShadowBook book, StreamHub hub, JdbcClient jdbc, Notifier notifier, StrategyParams params) {
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
        for (Position p : positions) {
            StreamHub.Quote q = hub.quote(p.venue(), p.symbol(), MAX_QUOTE_AGE_SECONDS);
            if (q == null || q.bid() <= 0 || q.ask() <= 0) continue; // 没有新鲜价格就不观察
            observe(p, q.bid(), q.ask(), now);
        }
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
        if (p.protectArmed()) {
            if (net <= 0) kind = "GAPPED";
            else if (net < p.protectFloor().doubleValue()) kind = "WOULD_PROTECT_SELL";
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
                        "👀 FastWatch 观察：V2 %s%s 估算净利 %.2f U 已低于保护底线 %.2f U\n只是观察，正式卖出等 5 分钟持仓检查用全数量盘口确认",
                        p.symbol(), Messages.venueTag(p.venue()), net, p.protectFloor().doubleValue()));
            }
        }
        return kind;
    }

    private static String key(Position p) {
        return p.venue() + ":" + p.symbol() + ":" + p.openedAt();
    }
}
