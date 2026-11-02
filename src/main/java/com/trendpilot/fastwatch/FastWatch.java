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
 * <p>
 * V2D15 和配置化影子变体（V5、V6、V7…）也用同一套观察和确认（用户 2026-11-02 批准）：只管利润保护，
 * 发现跌破底线就让 Monitor 按这本账自己的退出规则、重新取的全数量盘口确认卖出，止损、3 天等反弹等其他退出不变。
 * 这些账本不发 Telegram、不写 fastwatch_observation；启用"让赢家跑"的变体底线算法不同，不做快速观察。
 * 任何一本账出错只记 warn，不影响 V2。
 */
@Component
public class FastWatch {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FastWatch.class);

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
    /** 每个持仓上一次观察到的净利和时间，用来确认最高净利是持续的。 */
    private final Map<String, double[]> lastNet = new ConcurrentHashMap<>();
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

    /** 实时最优价来源（默认 WebSocket，30 秒内的才算新鲜）。 */
    @FunctionalInterface
    public interface QuoteSource {
        StreamHub.Quote quote(Venue venue, String symbol);
    }

    /** 其他账本（V2D15、配置化变体）：名字 → 当前持仓。由 Monitor 提供，避免 FastWatch 直接依赖各账本。 */
    private java.util.function.Supplier<Map<String, List<Position>>> otherBooks = Map::of;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setOtherBooks(com.trendpilot.job.FastWatchBooks books) {
        this.otherBooks = books::positions;
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 30_000)
    public void scheduled() {
        observe(Instant.now());
    }

    public void observe(Instant now) {
        observe(now, (v, sym) -> hub.quote(v, sym, MAX_QUOTE_AGE_SECONDS));
    }

    public void observe(Instant now, QuoteSource quotes) {
        List<Position> positions = book.positions();
        Map<String, List<Position>> others;
        try {
            others = otherBooks.get();
        } catch (RuntimeException e) {
            log.warn("FastWatch 读取其他账本持仓失败，本轮只看 V2：{}", e.toString());
            others = Map.of();
        }
        // 订阅 = 所有账本持仓的并集（StreamHub 负责单连接上限、发送间隔和 Bybit 每条 10 个主题），持仓变化时增量订阅 / 退订
        for (Venue v : Venue.values()) {
            Set<String> symbols = positions.stream().filter(p -> p.venue() == v).map(Position::symbol)
                    .collect(Collectors.toCollection(java.util.HashSet::new));
            others.values().forEach(list -> list.stream().filter(p -> p.venue() == v).forEach(p -> symbols.add(p.symbol())));
            hub.watchBooks(v, symbols);
        }
        lastKind.keySet().removeIf(k -> !k.contains("|") && positions.stream().noneMatch(p -> key(p).equals(k)));
        lastConfirm.keySet().removeIf(k -> !k.contains("|") && positions.stream().noneMatch(p -> key(p).equals(k)));
        peakSeen.keySet().removeIf(k -> !k.contains("|") && positions.stream().noneMatch(p -> key(p).equals(k)));
        lastNet.keySet().removeIf(k -> !k.contains("|") && positions.stream().noneMatch(p -> key(p).equals(k)));
        Map<String, BigDecimal> hints = new java.util.HashMap<>();
        Set<String> confirm = new java.util.HashSet<>();
        for (Position p : positions) {
            StreamHub.Quote q = quotes.quote(p.venue(), p.symbol());
            if (q == null || q.bid() <= 0 || q.ask() <= 0 || q.bid() > q.ask()) continue; // 没有新鲜、合理的价格就不观察
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
        for (var e : others.entrySet()) {
            try {
                observeOther(e.getKey(), e.getValue(), quotes, now);
            } catch (RuntimeException ex) {
                log.warn("FastWatch {} 本轮出错，跳过（不影响 V2）：{}", e.getKey(), ex.toString());
            }
        }
    }

    /** 其他账本：同一套观察（底线按实时最高净利估算、60 秒内最多确认一次），确认交给 Monitor 按这本账的规则卖。 */
    private void observeOther(String name, List<Position> positions, QuoteSource quotes, Instant now) {
        Set<String> keys = positions.stream().map(p -> name + "|" + key(p)).collect(Collectors.toSet());
        for (Map<String, ?> m : List.of(lastKind, lastConfirm, peakSeen, lastNet)) {
            m.keySet().removeIf(k -> k.startsWith(name + "|") && !keys.contains(k));
        }
        Map<String, BigDecimal> hints = new java.util.HashMap<>();
        Set<String> confirm = new java.util.HashSet<>();
        for (Position p : positions) {
            StreamHub.Quote q = quotes.quote(p.venue(), p.symbol());
            if (q == null || q.bid() <= 0 || q.ask() <= 0 || q.bid() > q.ask()) continue;
            String k = name + "|" + key(p);
            String kind = classify(p, k, q.bid(), q.ask(), now);
            lastKind.put(k, kind);
            if (kind.equals("WOULD_PROTECT_SELL") || kind.equals("GAPPED")) {
                Instant last = lastConfirm.get(k);
                if (last == null || now.isAfter(last.plusSeconds(CONFIRM_GAP_SECONDS))) {
                    lastConfirm.put(k, now);
                    confirm.add(p.symbol());
                    BigDecimal seen = peakSeen.get(k);
                    if (seen != null) hints.put(p.symbol(), seen);
                }
            }
        }
        if (!confirm.isEmpty()) monitor.confirmNow(name, confirm, now, hints);
    }

    /** 一次观察的估算结果。 */
    private record Obs(String kind, double net, double floor, double rawRet, double spreadBps) {
    }

    /** @return 本次观察结论（HOLD / WOULD_ARM / WOULD_PROTECT_SELL / GAPPED） */
    String observe(Position p, double bid, double ask, Instant now) {
        Obs o = estimate(p, key(p), bid, ask, now);
        String kind = o.kind();
        String prev = lastKind.put(key(p), kind);
        if (!kind.equals("HOLD") && !kind.equals(prev)) {
            jdbc.sql("""
                    INSERT INTO fastwatch_observation(venue, symbol, kind, bid, ask, spread_bps, est_net, raw_ret_pct, created_at)
                    VALUES (:v, :s, :k, :b, :a, :sp, :n, :r, :t)
                    """)
                    .param("v", p.venue().name()).param("s", p.symbol()).param("k", kind).param("b", bid).param("a", ask)
                    .param("sp", o.spreadBps()).param("n", o.net()).param("r", o.rawRet()).param("t", now.toString()).update();
            if (kind.equals("WOULD_PROTECT_SELL")) {
                notifier.send("fastwatch:" + key(p) + ":" + now.getEpochSecond() / 3600, String.format(Locale.ROOT,
                        "👀 FastWatch：V2 %s%s 估算净利 %.2f U 已低于保护底线 %.2f U\n正在立即用全数量盘口确认，确认后马上卖出",
                        p.symbol(), Messages.venueTag(p.venue()), o.net(), o.floor()));
            }
        }
        return kind;
    }

    /** 其他账本用：只估算结论，不记录、不通知。 */
    private String classify(Position p, String k, double bid, double ask, Instant now) {
        return estimate(p, k, bid, ask, now).kind();
    }

    /** V2 和其他账本共用的估算：按最优买价算可退出净利，底线用和 Monitor 同一公式、按持续的实时最高净利。 */
    private Obs estimate(Position p, String k, double bid, double ask, Instant now) {
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
        // 交给 Monitor 的最高净利只认"持续"的价格：取相隔至少 15 秒的两次观察中较低的一次，
        // 单条异常报价（插针）不会把保护底线永久抬高
        double[] prevObs = lastNet.put(k, new double[] {net, now.toEpochMilli()});
        if (p.protectArmed() && prevObs != null && now.toEpochMilli() - (long) prevObs[1] >= 15_000) {
            peakSeen.merge(k, BigDecimal.valueOf(Math.min(net, prevObs[0])).max(p.peakNet()), BigDecimal::max);
        }
        peak = peak.max(peakSeen.getOrDefault(k, BigDecimal.ZERO));
        double floor = com.trendpilot.monitor.ExitPolicy.protectionFloor(p.costBasis(), peak, p.protectFloor(), e)
                .doubleValue();
        if (p.protectArmed()) {
            if (net <= 0) kind = "GAPPED";
            else if (net < floor) kind = "WOULD_PROTECT_SELL";
        } else if (rawRet >= e.protectArmPct() && net > 0) {
            kind = "WOULD_ARM";
        }
        return new Obs(kind, net, floor, rawRet, spreadBps);
    }

    private static String key(Position p) {
        return p.venue() + ":" + p.symbol() + ":" + p.openedAt();
    }
}
