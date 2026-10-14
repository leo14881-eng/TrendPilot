package com.trendpilot.tracking;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trendpilot.config.StrategyParams;
import com.trendpilot.job.JobRuns;
import com.trendpilot.market.BookTicker;
import com.trendpilot.market.Kline;
import com.trendpilot.market.KlineStore;
import com.trendpilot.market.Markets;
import com.trendpilot.market.SymbolInfo;
import com.trendpilot.market.Ticker24h;
import com.trendpilot.market.Venue;
import com.trendpilot.market.VenueMarket;
import com.trendpilot.risk.RiskState;
import com.trendpilot.signal.EarlySignal;

/**
 * 信号跟踪（docs/信号跟踪与退出策略设计.md）：只读行情、只写跟踪表，不影响交易。
 * <ul>
 * <li>Discovery 结束后：每个 EARLY 开一个 SIGNAL 样本（同一标的 24 小时内重复出现只记重复次数）；
 *     每笔 V2 影子买入/加仓开一个 V2_ENTRY 样本。起点价用本轮全市场最优价的卖一价，拿不到不开样本。</li>
 * <li>每小时 :25：从本地 K 线缓存把新收盘的 15m K 线复制进样本自己的副本，重算指标。跟踪本身不发请求。</li>
 * </ul>
 */
@Component
public class SignalTracker {

    public static final String JOB = "SIGNAL_TRACKING";
    private static final Logger log = LoggerFactory.getLogger(SignalTracker.class);
    static final long Q = 15 * 60_000L;

    private final JdbcClient jdbc;
    private final KlineStore klines;
    private final Markets markets;
    private final TrackingProperties p;
    private final StrategyParams params;
    private final JobRuns runs;
    private final ObjectMapper json = new ObjectMapper();

    public SignalTracker(JdbcClient jdbc, KlineStore klines, Markets markets, TrackingProperties p,
                         StrategyParams params, JobRuns runs) {
        this.jdbc = jdbc;
        this.klines = klines;
        this.markets = markets;
        this.p = p;
        this.params = params;
        this.runs = runs;
    }

    /** 一个 EARLY 信号及其审核结论。 */
    public record SignalInput(Venue venue, EarlySignal signal, Ticker24h ticker, String reviewResult,
                              String reviewReasons, Double firstEarlyPrice) {
    }

    /** 一笔 V2 影子买入或加仓。 */
    public record EntryInput(SignalInput signal, double fillPrice) {
    }

    /** 本轮 Discovery 的截面。 */
    public record Context(String generation, Instant tSig, Instant t0, RiskState risk,
                          Map<Venue, Map<String, BookTicker>> quotes) {
    }

    /** @return 新开的样本数 */
    @Transactional
    public int onDiscovery(Context ctx, List<SignalInput> signals, List<EntryInput> entries) {
        if (!p.enabled()) return 0;
        int opened = 0, noQuote = 0;
        for (SignalInput s : signals) {
            BookTicker q = quote(ctx, s);
            if (q == null) {
                noQuote++;
                continue;
            }
            String sym = s.signal().symbol();
            Long tracking = jdbc.sql("""
                    SELECT id FROM signal_episode WHERE venue = :v AND symbol = :s AND start_type = 'SIGNAL' AND t0 >= :since
                    ORDER BY t0 DESC LIMIT 1
                    """).param("v", s.venue().name()).param("s", sym)
                    .param("since", ctx.t0().minus(Duration.ofHours(p.dedupeHours())).toString())
                    .query(Long.class).optional().orElse(null);
            if (tracking != null) {
                jdbc.sql("UPDATE signal_episode SET repeat_count = repeat_count + 1, last_repeat_time = :t WHERE id = :id")
                        .param("t", ctx.t0().toString()).param("id", tracking).update();
                continue;
            }
            insert(ctx, s, "SIGNAL", null, q.ask(), q.bid(), q.spreadBps());
            opened++;
        }
        for (EntryInput e : entries) {
            BookTicker q = quote(ctx, e.signal());
            Long eventId = jdbc.sql("""
                    SELECT MAX(id) FROM shadow_event WHERE book = 'V2' AND symbol = :s AND type IN ('BUY', 'ADD') AND generation = :g
                    """).param("s", e.signal().signal().symbol()).param("g", ctx.generation())
                    .query(Long.class).optional().orElse(null);
            insert(ctx, e.signal(), "V2_ENTRY", eventId, e.fillPrice(), q == null ? Double.NaN : q.bid(),
                    q == null ? 0 : q.spreadBps());
            opened++;
        }
        if (noQuote > 0) log.info("信号跟踪：{} 个 EARLY 拿不到卖一价，没有开样本", noQuote);
        return opened;
    }

    private static BookTicker quote(Context ctx, SignalInput s) {
        BookTicker q = ctx.quotes().getOrDefault(s.venue(), Map.of()).get(s.signal().symbol());
        return q == null || q.ask() <= 0 || q.bid() <= 0 ? null : q;
    }

    /** 附录 A：没有盘口时按 24h 成交额估算 1,000U 卖出冲击。 */
    static double modelImpactBps(double quoteVolume) {
        if (quoteVolume >= 50e6) return 5;
        if (quoteVolume >= 10e6) return 10;
        if (quoteVolume >= 2e6) return 25;
        return 50;
    }

    private void insert(Context ctx, SignalInput s, String type, Long eventId, double p0, double bid, double spreadBps) {
        EarlySignal sig = s.signal();
        String sym = sig.symbol();
        Long prior = jdbc.sql("""
                SELECT id FROM signal_episode WHERE venue = :v AND symbol = :s AND start_type = :type ORDER BY t0 DESC LIMIT 1
                """).param("v", s.venue().name()).param("s", sym).param("type", type)
                .query(Long.class).optional().orElse(null);
        double impact = modelImpactBps(s.ticker().quoteVolume());
        double fee = params.feeRate();
        RiskState r = ctx.risk();
        Double chase = s.firstEarlyPrice() == null || s.firstEarlyPrice() <= 0 ? null
                : (sig.price() / s.firstEarlyPrice() - 1) * 100;
        jdbc.sql("""
                INSERT INTO signal_episode(venue, symbol, start_type, v2_event_id, prior_episode_id, is_first_episode,
                    t_sig, t0, generation_id, param_version, conditions, hit_count, score, rel_1h, rel_4h, accel,
                    volume_ratio, compression, mom_15m, change_24h, quote_volume_24h, atr_1h_pct, first_early_price,
                    chase_pct, regime, btc_ret_1h, btc_ret_4h, btc_ret_24h, breadth, review_result, review_reasons,
                    p0, p0_bid, spread_bps, impact_bps, impact_source, fee_rate, roundtrip_cost_bps, status, window_end)
                VALUES (:v, :s, :type, :ev, :prior, :first, :tsig, :t0, :gen, :pv, :cond, :hits, :score, :r1, :r4, :acc,
                    :vol, :comp, :mom, :ch, :qv, :atr, :fe, :chase, :regime, :b1, :b4, :b24, :breadth, :rr, :rreasons,
                    :p0, :bid, :spread, :impact, 'MODEL', :fee, :cost, 'TRACKING', :end)
                """)
                .param("v", s.venue().name()).param("s", sym).param("type", type).param("ev", eventId)
                .param("prior", prior).param("first", prior == null ? 1 : 0)
                .param("tsig", ctx.tSig().toString()).param("t0", ctx.t0().toString()).param("gen", ctx.generation())
                .param("pv", params.version()).param("cond", String.join(",", sig.hits())).param("hits", sig.hits().size())
                .param("score", sig.score()).param("r1", sig.rel1h()).param("r4", sig.rel4h()).param("acc", sig.accel())
                .param("vol", sig.volumeAccel()).param("comp", sig.compression()).param("mom", sig.ret15m())
                .param("ch", s.ticker().priceChangePct()).param("qv", s.ticker().quoteVolume())
                .param("atr", sig.atr1hPct()).param("fe", s.firstEarlyPrice()).param("chase", chase)
                .param("regime", r == null ? null : r.regime().name())
                .param("b1", r == null ? null : nan(r.btc1hPct())).param("b4", r == null ? null : nan(r.btc4hPct()))
                .param("b24", r == null ? null : nan(r.btc24hPct())).param("breadth", r == null ? null : nan(r.breadth()))
                .param("rr", s.reviewResult()).param("rreasons", s.reviewReasons())
                .param("p0", p0).param("bid", nan(bid)).param("spread", spreadBps).param("impact", impact)
                .param("fee", fee).param("cost", 2 * fee * 10_000 + spreadBps / 2 + impact)
                .param("end", ctx.t0().plus(Duration.ofHours(p.extWindowHours())).toString())
                .update();
    }

    private static Double nan(double v) {
        return Double.isNaN(v) ? null : v;
    }

    // ---------------------------------------------------------------- 跟踪

    record Episode(long id, Venue venue, String symbol, Instant t0, double p0, double fee, double spreadBps,
                   double impactBps, double atrPct, Instant windowEnd, Long lastBarTime) {

        PathCalculator.Cost cost() {
            return new PathCalculator.Cost(p0, fee, spreadBps / 10_000, impactBps / 10_000);
        }
    }

    /** 仍在跟踪窗口内的信号标的，需要继续订阅 K 线，避免跟踪数据出现缺口。 */
    public java.util.Set<String> trackingSymbols(com.trendpilot.market.Venue venue) {
        return new java.util.HashSet<>(jdbc.sql("SELECT DISTINCT symbol FROM signal_episode WHERE status = 'TRACKING' AND venue = :v")
                .param("v", venue.name()).query(String.class).list());
    }

    @Scheduled(cron = "0 25 * * * *", zone = "${trendpilot.zone}")
    public void scheduled() {
        Instant started = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        try {
            int n = track(started);
            runs.record(JOB, "T-" + started, started, Instant.now(), "SUCCESS", "N/A", "更新 " + n + " 个样本");
        } catch (RuntimeException e) {
            log.error("信号跟踪失败", e);
            runs.record(JOB, "T-" + started, started, Instant.now(), "FAILED", "N/A", String.valueOf(e.getMessage()));
        }
    }

    /** @return 本次重算的样本数 */
    public int track(Instant now) {
        if (!p.enabled()) return 0;
        List<Episode> active = jdbc.sql("""
                SELECT id, venue, symbol, t0, p0, fee_rate, spread_bps, impact_bps, atr_1h_pct, window_end, last_bar_time
                FROM signal_episode WHERE status = 'TRACKING' ORDER BY id
                """)
                .query((rs, i) -> new Episode(rs.getLong(1), Venue.valueOf(rs.getString(2)), rs.getString(3),
                        Instant.parse(rs.getString(4)), rs.getDouble(5), rs.getDouble(6), rs.getDouble(7),
                        rs.getDouble(8), rs.getObject(9) == null ? Double.NaN : rs.getDouble(9),
                        Instant.parse(rs.getString(10)), rs.getObject(11) == null ? null : rs.getLong(11)))
                .list();
        for (Episode e : active) {
            update(e, now);
        }
        return active.size();
    }

    @Transactional
    void update(Episode e, Instant now) {
        // 1. 复制新收盘的 K 线（只读本地缓存，不发请求）
        long from = e.lastBarTime() == null ? e.t0().toEpochMilli() - Q : e.lastBarTime() - Q + 1;
        long to = Math.min(now.toEpochMilli(), e.windowEnd().toEpochMilli());
        for (Kline k : klines.range(e.venue(), e.symbol(), from, to)) {
            long close = k.openTime() + Q;
            if (close > now.toEpochMilli() || close <= e.t0().toEpochMilli()) continue;
            jdbc.sql("""
                    INSERT OR IGNORE INTO signal_track_bar(episode_id, bar_close_time, open, high, low, close, quote_volume)
                    VALUES (:id, :t, :o, :h, :l, :c, :qv)
                    """).param("id", e.id()).param("t", close).param("o", k.open()).param("h", k.high())
                    .param("l", k.low()).param("c", k.close()).param("qv", k.quoteVolume()).update();
        }
        List<PathCalculator.Bar> bars = bars(e.id());
        boolean delisted = isDelisted(e);
        PathCalculator.Result r = PathCalculator.compute(e.t0().toEpochMilli(), e.cost(), e.atrPct(), bars, delisted, p);
        save(e.id(), r, now);

        Long lastBar = bars.isEmpty() ? null : bars.getLast().closeTime();
        String status = "TRACKING";
        if (r.dataGap()) status = "DATA_GAP";
        else if (delisted) status = "DELISTED";
        else if (lastBar != null && lastBar >= e.windowEnd().toEpochMilli()) status = "COMPLETED";
        else if (now.isAfter(e.windowEnd().plus(Duration.ofDays(1)))) status = "COMPLETED"; // 缓存里再也等不到，按删失结束
        jdbc.sql("""
                UPDATE signal_episode SET status = :st, last_bar_time = :lb, gap_bars = :g, ambiguous_bars = :a WHERE id = :id
                """).param("st", status).param("lb", lastBar).param("g", r.gapBars()).param("a", r.ambiguousBars())
                .param("id", e.id()).update();
    }

    private boolean isDelisted(Episode e) {
        VenueMarket m = markets.get(e.venue());
        if (m == null) return false;
        try {
            SymbolInfo info = m.directory().get(e.symbol());
            return info == null || !info.trading();
        } catch (RuntimeException ex) {
            return false; // 交易状态拿不到时不判断
        }
    }

    List<PathCalculator.Bar> bars(long episodeId) {
        return jdbc.sql("""
                SELECT bar_close_time, open, high, low, close FROM signal_track_bar WHERE episode_id = :id ORDER BY bar_close_time
                """).param("id", episodeId)
                .query((rs, i) -> new PathCalculator.Bar(rs.getLong(1), rs.getDouble(2), rs.getDouble(3),
                        rs.getDouble(4), rs.getDouble(5)))
                .list();
    }

    private void save(long id, PathCalculator.Result r, Instant now) {
        String metrics;
        try {
            metrics = json.writeValueAsString(r.metrics());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        jdbc.sql("""
                INSERT OR REPLACE INTO signal_outcome(episode_id, observed_minutes, calc_version, computed_at, metrics)
                VALUES (:id, :obs, :v, :t, :m)
                """).param("id", id).param("obs", r.observedMinutes()).param("v", PathCalculator.VERSION)
                .param("t", now.toString()).param("m", metrics).update();
        jdbc.sql("DELETE FROM signal_pullback WHERE episode_id = :id").param("id", id).update();
        for (PathCalculator.Pullback b : r.pullbacks()) {
            jdbc.sql("""
                    INSERT INTO signal_pullback(episode_id, seq, peak_time, peak_gain_net, trough_time, depth_pct, recovered,
                        recover_time) VALUES (:id, :seq, :pt, :pg, :tt, :d, :r, :rt)
                    """).param("id", id).param("seq", b.seq()).param("pt", b.peakTime()).param("pg", b.peakGainNet())
                    .param("tt", b.troughTime()).param("d", b.depthPct()).param("r", b.recovered() ? 1 : 0)
                    .param("rt", b.recoverTime()).update();
        }
    }
}
