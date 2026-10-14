package com.trendpilot.replay;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.trendpilot.job.JobRuns;
import com.trendpilot.market.Markets;
import com.trendpilot.market.SymbolInfo;
import com.trendpilot.market.Ticker24h;
import com.trendpilot.market.Venue;
import com.trendpilot.market.VenueMarket;
import com.trendpilot.notify.Messages;
import com.trendpilot.notify.Notifier;
import com.trendpilot.portfolio.ObservationBook;
import com.trendpilot.portfolio.ShadowBook;
import com.trendpilot.research.ResearchService;
import com.trendpilot.universe.UniverseFilter;

/**
 * Missed Replay（每天越南时间 23:55）：复盘 24h 涨幅榜前列，何时第一次出现 EARLY、为什么没买。
 * 只读已有的 signal_log / decision_log / 账本事件，不补造历史交易。
 */
@Component
public class MissedReplay {

    public static final String JOB = "MISSED_REPLAY";
    static final int TOP = 10;
    private static final Logger log = LoggerFactory.getLogger(MissedReplay.class);

    private final Markets markets;
    private final UniverseFilter universeFilter;
    private final ResearchService research;
    private final JdbcClient jdbc;
    private final Notifier notifier;
    private final JobRuns runs;
    private final BlindReplay blind;
    private final ZoneId zone;

    public MissedReplay(Markets markets, UniverseFilter universeFilter, ResearchService research, JdbcClient jdbc,
                        Notifier notifier, JobRuns runs, BlindReplay blind, @Value("${trendpilot.zone}") String zone) {
        this.markets = markets;
        this.universeFilter = universeFilter;
        this.research = research;
        this.jdbc = jdbc;
        this.notifier = notifier;
        this.runs = runs;
        this.blind = blind;
        this.zone = ZoneId.of(zone);
    }

    /** 涨幅榜中的一行复盘。 */
    public record Missed(Venue venue, String symbol, double changePct, Instant firstEarlyAt, Double firstEarlyPrice,
                         double lastPrice, String outcome, String reasons) {

        String line() {
            String found = firstEarlyAt == null ? "24h 内未出现 EARLY"
                    : String.format(Locale.ROOT, "首次 EARLY %s，之后涨 %.1f%%", firstEarlyAt.toString().substring(11, 16) + "Z",
                            (lastPrice / firstEarlyPrice - 1) * 100);
            return String.format(Locale.ROOT, "· %s%s +%.1f%%：%s；%s%s", symbol, Messages.venueTag(venue), changePct,
                    found, outcomeText(outcome), reasons.isEmpty() ? "" : "（" + reasons + "）");
        }
    }

    static String outcomeText(String outcome) {
        return switch (outcome) {
            case "V2_BOUGHT" -> "V2 已买入";
            case "V1_ONLY" -> "只有 V1 观察买入";
            case "NEVER_EARLY" -> "信号没发现";
            case "EARLY_NOT_REVIEWED" -> "出现 EARLY 但未进入审核名单";
            default -> outcome.startsWith("EARLY_") ? "审核结论 " + outcome.substring(6) : outcome;
        };
    }

    @Scheduled(cron = "0 55 23 * * *", zone = "${trendpilot.zone}")
    public void scheduled() {
        Instant started = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        try {
            List<Missed> rows = run(started);
            runs.record(JOB, "X-" + started, started, Instant.now(), "SUCCESS", "N/A", rows.size() + " 个");
        } catch (RuntimeException e) {
            log.error("Missed Replay 失败", e);
            runs.record(JOB, "X-" + started, started, Instant.now(), "FAILED", "N/A", String.valueOf(e.getMessage()));
        }
    }

    public List<Missed> run(Instant now) {
        String day = now.atZone(zone).toLocalDate().toString();
        Instant from = now.minus(Duration.ofHours(24));
        List<Missed> rows = new ArrayList<>();
        Set<String> binanceListed = Set.of();
        for (VenueMarket m : markets.all()) {
            List<Ticker24h> universe;
            try {
                universe = universeFilter.select(m.directory().all(), m.source().tickers24h(),
                        m.venue() == Venue.BINANCE ? Set.of() : binanceListed, research.excludedSymbols(m.venue()));
            } catch (RuntimeException e) {
                log.warn("Missed Replay：{} 行情获取失败：{}", m.venue(), e.getMessage());
                continue;
            }
            if (m.venue() == Venue.BINANCE) {
                binanceListed = m.directory().all().stream().filter(SymbolInfo::trading).map(SymbolInfo::symbol)
                        .collect(Collectors.toSet());
            }
            universe.stream().filter(t -> !t.symbol().equals(UniverseFilter.BTC))
                    .sorted(Comparator.comparingDouble(Ticker24h::priceChangePct).reversed()).limit(TOP)
                    .forEach(t -> rows.add(explain(m.venue(), t, from)));
        }
        rows.forEach(r -> save(day, r, now));
        notifier.send("missed:" + day, message(day, rows, now));
        return rows;
    }

    private Missed explain(Venue venue, Ticker24h t, Instant from) {
        String s = t.symbol();
        record Early(Instant at, double price) {
        }
        Optional<Early> first = jdbc.sql("""
                SELECT created_at, price FROM signal_log WHERE venue = :v AND symbol = :s AND created_at >= :f
                ORDER BY created_at LIMIT 1
                """).param("v", venue.name()).param("s", s).param("f", from.toString())
                .query((rs, i) -> new Early(Instant.parse(rs.getString(1)), rs.getDouble(2))).optional();
        String outcome;
        String reasons = "";
        if (bought(ShadowBook.BOOK, venue, s, from)) {
            outcome = "V2_BOUGHT";
        } else if (first.isEmpty()) {
            outcome = "NEVER_EARLY";
        } else {
            record Decision(String decision, int funded, String reasons) {
            }
            Optional<Decision> last = jdbc.sql("""
                    SELECT decision, funded, reasons FROM decision_log WHERE venue = :v AND symbol = :s AND created_at >= :f
                    ORDER BY created_at DESC LIMIT 1
                    """).param("v", venue.name()).param("s", s).param("f", from.toString())
                    .query((rs, i) -> new Decision(rs.getString(1), rs.getInt(2), rs.getString(3))).optional();
            if (last.isEmpty()) {
                outcome = "EARLY_NOT_REVIEWED";
            } else {
                outcome = "EARLY_" + last.get().decision();
                reasons = last.get().reasons() == null ? "" : last.get().reasons();
            }
            if (bought(ObservationBook.BOOK, venue, s, from)) {
                outcome = "V1_ONLY";
            }
        }
        return new Missed(venue, s, t.priceChangePct(), first.map(Early::at).orElse(null),
                first.map(Early::price).orElse(null), t.lastPrice(), outcome, reasons);
    }

    private boolean bought(String book, Venue venue, String symbol, Instant from) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM shadow_event WHERE book = :b AND venue = :v AND symbol = :s
                    AND type IN ('BUY', 'ADD') AND created_at >= :f
                """).param("b", book).param("v", venue.name()).param("s", symbol).param("f", from.toString())
                .query(Integer.class).single() > 0;
    }

    private void save(String day, Missed r, Instant now) {
        jdbc.sql("""
                INSERT OR REPLACE INTO missed_replay(day, venue, symbol, change_pct, first_early_at, first_early_price,
                    outcome, reasons, created_at) VALUES (:d, :v, :s, :c, :fa, :fp, :o, :r, :t)
                """)
                .param("d", day).param("v", r.venue().name()).param("s", r.symbol()).param("c", r.changePct())
                .param("fa", r.firstEarlyAt() == null ? null : r.firstEarlyAt().toString())
                .param("fp", r.firstEarlyPrice()).param("o", r.outcome()).param("r", r.reasons())
                .param("t", now.toString()).update();
    }

    private String message(String day, List<Missed> rows, Instant now) {
        List<String> lines = new ArrayList<>();
        lines.add("🔍 Missed Replay " + day + "（24h 涨幅榜复盘，只复盘不补交易）");
        if (rows.isEmpty()) lines.add("行情获取失败，今天没有复盘结果");
        rows.forEach(r -> lines.add(r.line()));
        List<BlindReplay.Bucket> buckets = blind.summary(now, 7);
        if (!buckets.isEmpty()) {
            lines.add("");
            lines.add("🧪 Blind Replay 近 7 天（EARLY 信号前向收益，研究样本，不代表策略已盈利）");
            buckets.forEach(b -> lines.add(b.line()));
        }
        return Messages.lines(lines);
    }
}
