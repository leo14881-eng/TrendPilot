package com.trendpilot.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.market.BarsLoader;
import com.trendpilot.market.Kline;
import com.trendpilot.market.KlineStore;
import com.trendpilot.market.Venue;

/**
 * 信号后的价格路径：每个币一段连续 EARLY 的第一个信号（之前 6 小时没出现过 EARLY），
 * 统计之后 24 小时内多久首次盈利（扣往返手续费）、多久涨到 +10% / +20%、多久到达短时高点及高点涨幅、
 * 高点之前的最大回撤、24 小时末收益。只用本地已缓存的 15m K 线，覆盖不足的样本跳过，不补造。
 * 时间按 K 线收盘计，精度 15 分钟。
 */
@Component
public class SignalPath {

    static final Duration WINDOW = Duration.ofHours(24);
    static final Duration EPISODE_GAP = Duration.ofHours(6);
    static final int MIN_BARS = 90; // 24 小时 96 根，最多缺 6 根
    static final long Q = BarsLoader.QUARTER_MS;

    private final JdbcClient jdbc;
    private final KlineStore klines;
    private final double roundTripFeePct;

    public SignalPath(JdbcClient jdbc, KlineStore klines, StrategyParams params) {
        this.jdbc = jdbc;
        this.klines = klines;
        this.roundTripFeePct = 2 * params.feeRate() * 100;
    }

    record Signal(long id, Venue venue, String symbol, double price, double score, Instant at) {
    }

    /** 一个信号的路径结果；没有达到的里程碑为 null。 */
    public record Path(Integer minutesToProfit, Integer minutesTo10, Integer minutesTo20, double peakPct,
                       int minutesToPeak, double drawdownBeforePeakPct, double return24hPct) {
    }

    /** @return 本次新写入的样本数 */
    public int run(Instant now) {
        // 满 24 小时、K 线缓存仍在（保留 3 天）的信号段起点
        List<Signal> starts = jdbc.sql("""
                SELECT s.id, s.venue, s.symbol, s.price, s.score, s.created_at FROM signal_log s
                WHERE s.created_at >= :from AND s.created_at <= :to AND s.price > 0
                  AND NOT EXISTS (SELECT 1 FROM signal_path p WHERE p.signal_id = s.id)
                """)
                .param("from", now.minus(Duration.ofHours(72)).toString())
                .param("to", now.minus(WINDOW).toString())
                .query((rs, i) -> new Signal(rs.getLong(1), Venue.valueOf(rs.getString(2)), rs.getString(3),
                        rs.getDouble(4), rs.getDouble(5), Instant.parse(rs.getString(6))))
                .list().stream().filter(this::isEpisodeStart).toList();
        int added = 0;
        for (Signal s : starts) {
            long from = s.at().toEpochMilli() - Math.floorMod(s.at().toEpochMilli(), Q);
            long end = s.at().plus(WINDOW).toEpochMilli();
            List<Kline> bars = klines.range(s.venue(), s.symbol(), from, end - Q);
            if (bars.size() < MIN_BARS) continue;
            Path p = path(s.price(), s.at(), bars, roundTripFeePct);
            jdbc.sql("""
                    INSERT OR IGNORE INTO signal_path(signal_id, venue, symbol, score, signal_at, price, minutes_to_profit,
                        minutes_to_10, minutes_to_20, peak_pct, minutes_to_peak, drawdown_before_peak_pct, return_24h_pct,
                        created_at)
                    VALUES (:id, :v, :s, :score, :at, :p, :mp, :m10, :m20, :peak, :mpeak, :dd, :ret, :t)
                    """)
                    .param("id", s.id()).param("v", s.venue().name()).param("s", s.symbol()).param("score", s.score())
                    .param("at", s.at().toString()).param("p", s.price()).param("mp", p.minutesToProfit())
                    .param("m10", p.minutesTo10()).param("m20", p.minutesTo20()).param("peak", p.peakPct())
                    .param("mpeak", p.minutesToPeak()).param("dd", p.drawdownBeforePeakPct())
                    .param("ret", p.return24hPct()).param("t", now.toString()).update();
            added++;
        }
        return added;
    }

    private boolean isEpisodeStart(Signal s) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM signal_log WHERE venue = :v AND symbol = :s AND created_at < :at AND created_at >= :gap
                """).param("v", s.venue().name()).param("s", s.symbol()).param("at", s.at().toString())
                .param("gap", s.at().minus(EPISODE_GAP).toString()).query(Integer.class).single() == 0;
    }

    static Path path(double price, Instant at, List<Kline> bars, double feePct) {
        long t0 = at.toEpochMilli();
        Integer toProfit = null, to10 = null, to20 = null;
        double peak = Double.NEGATIVE_INFINITY, low = Double.POSITIVE_INFINITY, lowBeforePeak = price;
        int toPeak = 0;
        for (Kline k : bars) {
            int minutes = (int) Math.max(0, (k.openTime() + Q - t0) / 60_000);
            double up = (k.high() / price - 1) * 100;
            if (toProfit == null && up > feePct) toProfit = minutes;
            if (to10 == null && up >= 10) to10 = minutes;
            if (to20 == null && up >= 20) to20 = minutes;
            low = Math.min(low, k.low());
            if (up > peak) {
                peak = up;
                toPeak = minutes;
                lowBeforePeak = low;
            }
        }
        return new Path(toProfit, to10, to20, peak, toPeak, Math.min(0, (lowBeforePeak / price - 1) * 100),
                (bars.getLast().close() / price - 1) * 100);
    }

    /** 最近 days 天的统计（中位数与分布）。 */
    public record Stats(int samples, double profitShare, Double medianMinutesToProfit, double up10Share,
                        Double medianMinutesTo10, double up20Share, Double medianMinutesTo20, double medianPeakPct,
                        double medianMinutesToPeak, double medianDrawdownPct, List<String> peakTiming) {

        public List<String> lines() {
            if (samples == 0) return List.of("暂无满 24 小时的信号样本");
            return List.of(
                    String.format(Locale.ROOT, "样本 %d 个（每段连续 EARLY 的第一个信号，看之后 24 小时）", samples),
                    String.format(Locale.ROOT, "扣费后转盈利：%.0f%%，中位用时 %s", profitShare * 100, hours(medianMinutesToProfit)),
                    String.format(Locale.ROOT, "涨到 +10%%：%.0f%%，中位用时 %s", up10Share * 100, hours(medianMinutesTo10)),
                    String.format(Locale.ROOT, "涨到 +20%%：%.0f%%，中位用时 %s", up20Share * 100, hours(medianMinutesTo20)),
                    String.format(Locale.ROOT, "短时高点：中位涨幅 %.1f%%，中位用时 %s；高点前中位最大回撤 %.1f%%",
                            medianPeakPct, hours(medianMinutesToPeak), medianDrawdownPct),
                    "高点出现在：" + String.join("，", peakTiming));
        }

        private static String hours(Double minutes) {
            return minutes == null || Double.isNaN(minutes) ? "-" : String.format(Locale.ROOT, "%.1f 小时", minutes / 60);
        }
    }

    public Stats stats(Instant now, int days) {
        List<Path> rows = jdbc.sql("""
                SELECT minutes_to_profit, minutes_to_10, minutes_to_20, peak_pct, minutes_to_peak,
                    drawdown_before_peak_pct, return_24h_pct FROM signal_path WHERE signal_at >= :from
                """).param("from", now.minus(Duration.ofDays(days)).toString())
                .query((rs, i) -> new Path(rs.getObject(1, Integer.class), rs.getObject(2, Integer.class),
                        rs.getObject(3, Integer.class), rs.getDouble(4), rs.getInt(5), rs.getDouble(6),
                        rs.getDouble(7)))
                .list();
        int n = rows.size();
        if (n == 0) return new Stats(0, 0, null, 0, null, 0, null, Double.NaN, Double.NaN, Double.NaN, List.of());
        int[] edges = {60, 240, 720, Integer.MAX_VALUE};
        String[] labels = {"1 小时内", "1-4 小时", "4-12 小时", "12-24 小时"};
        List<String> timing = new ArrayList<>();
        int prev = -1;
        for (int i = 0; i < edges.length; i++) {
            int lo = prev, hi = edges[i];
            long c = rows.stream().filter(r -> r.minutesToPeak() > lo && r.minutesToPeak() <= hi).count();
            timing.add(String.format(Locale.ROOT, "%s %.0f%%", labels[i], c * 100.0 / n));
            prev = hi;
        }
        return new Stats(n,
                share(rows, r -> r.minutesToProfit()), median(rows, r -> r.minutesToProfit()),
                share(rows, r -> r.minutesTo10()), median(rows, r -> r.minutesTo10()),
                share(rows, r -> r.minutesTo20()), median(rows, r -> r.minutesTo20()),
                median(rows.stream().mapToDouble(Path::peakPct).boxed().toList()),
                median(rows.stream().mapToDouble(r -> r.minutesToPeak()).boxed().toList()),
                median(rows.stream().mapToDouble(Path::drawdownBeforePeakPct).boxed().toList()),
                timing);
    }

    private static double share(List<Path> rows, java.util.function.Function<Path, Integer> f) {
        return rows.stream().filter(r -> f.apply(r) != null).count() / (double) rows.size();
    }

    private static Double median(List<Path> rows, java.util.function.Function<Path, Integer> f) {
        List<Double> v = rows.stream().map(f).filter(x -> x != null).map(Integer::doubleValue).toList();
        return v.isEmpty() ? null : median(v);
    }

    static double median(List<Double> values) {
        List<Double> v = values.stream().sorted().toList();
        int n = v.size();
        return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2;
    }
}
