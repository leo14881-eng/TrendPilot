package com.trendpilot.replay;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.trendpilot.job.JobRuns;
import com.trendpilot.market.BarsLoader;
import com.trendpilot.market.Kline;
import com.trendpilot.market.KlineStore;
import com.trendpilot.market.Venue;

/**
 * Blind Replay（每小时 :31）：给已记录的 EARLY 信号补上 1h / 4h / 24h 前向收益，按评分分档统计。
 * 只用本地已缓存的 15m K 线；缺 K 线的样本跳过，不补造。这是研究样本，不能当作策略盈利已经证明。
 */
@Component
public class BlindReplay {

    public static final String JOB = "BLIND_REPLAY";
    static final int[] HORIZONS = {1, 4, 24};
    private static final Logger log = LoggerFactory.getLogger(BlindReplay.class);

    private final JdbcClient jdbc;
    private final KlineStore klines;
    private final JobRuns runs;
    private final SignalPath paths;

    public BlindReplay(JdbcClient jdbc, KlineStore klines, JobRuns runs, SignalPath paths) {
        this.paths = paths;
        this.jdbc = jdbc;
        this.klines = klines;
        this.runs = runs;
    }

    record SignalRow(long id, Venue venue, String symbol, double price, double score, Instant at) {
    }

    /** 某档评分在某个持有期上的统计。 */
    public record Bucket(String scoreBand, int horizonH, int samples, double avgReturnPct, double winRate,
                         double avgMaxUpPct) {
        public String line() {
            return String.format(Locale.ROOT, "评分 %s · %dh：%d 个，均收益 %.2f%%，胜率 %.0f%%，平均最大涨幅 %.2f%%",
                    scoreBand, horizonH, samples, avgReturnPct, winRate * 100, avgMaxUpPct);
        }
    }

    @Scheduled(cron = "0 31 * * * *", zone = "${trendpilot.zone}")
    public void scheduled() {
        Instant started = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        try {
            int n = run(started);
            int p = paths.run(started);
            runs.record(JOB, "R-" + started, started, Instant.now(), "SUCCESS", "N/A",
                    "新增 " + n + " 个前向收益样本，" + p + " 个信号路径");
        } catch (RuntimeException e) {
            log.error("Blind Replay 失败", e);
            runs.record(JOB, "R-" + started, started, Instant.now(), "FAILED", "N/A", String.valueOf(e.getMessage()));
        }
    }

    /** @return 本次新写入的样本数 */
    public int run(Instant now) {
        // K 线缓存保留 3 天，只回看这个范围内的信号
        List<SignalRow> signals = jdbc.sql("""
                SELECT id, venue, symbol, price, score, created_at FROM signal_log
                WHERE created_at >= :from AND price > 0
                """).param("from", now.minus(Duration.ofHours(72)).toString())
                .query((rs, i) -> new SignalRow(rs.getLong(1), Venue.valueOf(rs.getString(2)), rs.getString(3),
                        rs.getDouble(4), rs.getDouble(5), Instant.parse(rs.getString(6))))
                .list();
        int added = 0;
        for (SignalRow s : signals) {
            for (int h : HORIZONS) {
                Instant target = s.at().plus(Duration.ofHours(h));
                if (target.isAfter(now) || exists(s.id(), h)) continue;
                long q = BarsLoader.QUARTER_MS;
                // 信号之后、目标时刻之前已收盘的 15m K 线
                List<Kline> bars = klines.range(s.venue(), s.symbol(), s.at().toEpochMilli() - q, target.toEpochMilli())
                        .stream().filter(k -> k.openTime() >= s.at().toEpochMilli() - q
                                && k.openTime() + q <= target.toEpochMilli()).toList();
                if (bars.isEmpty() || bars.getLast().openTime() + 2 * q < target.toEpochMilli()) continue; // 缺数据
                double ret = (bars.getLast().close() / s.price() - 1) * 100;
                double maxUp = (bars.stream().mapToDouble(Kline::high).max().orElse(s.price()) / s.price() - 1) * 100;
                jdbc.sql("""
                        INSERT OR IGNORE INTO replay_outcome(signal_id, horizon_h, venue, symbol, score, return_pct,
                            max_up_pct, created_at) VALUES (:id, :h, :v, :s, :score, :r, :m, :t)
                        """)
                        .param("id", s.id()).param("h", h).param("v", s.venue().name()).param("s", s.symbol())
                        .param("score", s.score()).param("r", ret).param("m", maxUp).param("t", now.toString())
                        .update();
                added++;
            }
        }
        return added;
    }

    private boolean exists(long id, int h) {
        return jdbc.sql("SELECT COUNT(*) FROM replay_outcome WHERE signal_id = :id AND horizon_h = :h")
                .param("id", id).param("h", h).query(Integer.class).single() > 0;
    }

    static String band(double score) {
        if (score < 8) return "<8";
        if (score < 10) return "8-10";
        if (score < 12) return "10-12";
        return "≥12";
    }

    /** 最近 days 天信号的分档统计（按评分档、持有期排序）。 */
    public List<Bucket> summary(Instant now, int days) {
        record Row(double score, int h, double ret, double maxUp) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT o.score, o.horizon_h, o.return_pct, o.max_up_pct FROM replay_outcome o
                JOIN signal_log s ON s.id = o.signal_id WHERE s.created_at >= :from
                """).param("from", now.minus(Duration.ofDays(days)).toString())
                .query((rs, i) -> new Row(rs.getDouble(1), rs.getInt(2), rs.getDouble(3), rs.getDouble(4))).list();
        Map<String, List<Row>> groups = new TreeMap<>();
        rows.forEach(r -> groups.computeIfAbsent(band(r.score()) + "|" + r.h(), k -> new ArrayList<>()).add(r));
        List<Bucket> out = new ArrayList<>();
        groups.forEach((k, list) -> {
            String[] parts = k.split("\\|");
            out.add(new Bucket(parts[0], Integer.parseInt(parts[1]), list.size(),
                    list.stream().mapToDouble(Row::ret).average().orElse(0),
                    list.stream().filter(r -> r.ret() > 0).count() / (double) list.size(),
                    list.stream().mapToDouble(Row::maxUp).average().orElse(0)));
        });
        out.sort((a, b) -> a.scoreBand().equals(b.scoreBand()) ? Integer.compare(a.horizonH(), b.horizonH())
                : a.scoreBand().compareTo(b.scoreBand()));
        return out;
    }
}
