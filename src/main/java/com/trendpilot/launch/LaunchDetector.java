package com.trendpilot.launch;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.trendpilot.market.Kline;
import com.trendpilot.market.Venue;

/**
 * 秒级"启动探测"（用户 2026-10-26："不能做到秒级监听吗"）：**只观察记录，不买卖，也不进任何影子账本。**
 * <ul>
 * <li>数据：已订阅的 15m K 线 WebSocket 每 1～2 秒的实时推送（当前价 + 本根累计成交额），覆盖扫描池内全部币（Binance 主池、Bybit 独立池）。
 *     不另开全市场流（Binance !miniTicker@arr 每秒推全部交易对，带宽约每天数 GB，而池外的币也没有 K 线可以算后续收益），
 *     不加订阅主题，不占 REST。</li>
 * <li>每个币 1 小时滚动窗口（{@link LaunchWindows}，原始类型环形缓冲，约 550 个币 × 360 格，约 2MB），不存逐条行情、不写原始推送。</li>
 * <li>启动（5 分钟涨 ≥ 2% 且成交额 ≥ 之前平均 3 倍，最低一档）写 launch_event；更高档（3%/5%，5 倍）在统计时按记录的数值筛。
 *     WebSocket 线程只放进内存队列，写库在后台每 30 秒一次，不挡行情。</li>
 * <li>每小时补算后来 1h / 4h / 24h 的最高涨幅和收益、同时刻随机一个币的 24h 收益（随机基准）、Discovery 晚多少分钟才记到 EARLY；
 *     K 线不够就标 MISSING，不补造。</li>
 * <li>断线、推送中断超过 60 秒：窗口作废重新攒，期间不记录。攒满约 4 周再回测"启动探测触发审核买入"，在此之前不用它下任何单。</li>
 * </ul>
 */
@Component
public class LaunchDetector {

    private static final Logger log = LoggerFactory.getLogger(LaunchDetector.class);
    /** 记录的档位（%，倍）：最低一档触发记录，统计时再按更高档筛。 */
    public static final double[] RET_LEVELS = {2, 3, 5};
    public static final double[] VOL_LEVELS = {3, 5};

    private final JdbcClient jdbc;
    private final boolean enabled;
    private final LaunchWindows windows = new LaunchWindows(RET_LEVELS[0], VOL_LEVELS[0]);
    private final BlockingQueue<Object[]> pending = new ArrayBlockingQueue<>(1000);

    public LaunchDetector(JdbcClient jdbc, @Value("${trendpilot.launch.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    /** WebSocket 线程调用：只做内存计算；队列满了就丢（只是少记一条，不影响任何交易）。 */
    public void onLive(Venue venue, String symbol, Kline k, Instant at) {
        if (!enabled) return;
        try {
            LaunchWindows.Launch l = windows.onTick(venue + ":" + symbol, k.close(), k.quoteVolume(), k.openTime(), at.toEpochMilli());
            if (l != null) pending.offer(new Object[]{venue.name(), symbol, at, l});
        } catch (RuntimeException e) {
            log.debug("启动探测 {} {} 跳过：{}", venue, symbol, e.toString());
        }
    }

    public LaunchWindows windows() {
        return windows;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void flush() {
        windows.prune(System.currentTimeMillis());
        List<Object[]> batch = new ArrayList<>();
        pending.drainTo(batch);
        for (Object[] e : batch) {
            try {
                record((String) e[0], (String) e[1], (Instant) e[2], (LaunchWindows.Launch) e[3]);
            } catch (RuntimeException ex) {
                log.warn("启动事件没写进去（只是少记一条）：{}", ex.getMessage());
            }
        }
    }

    void record(String venue, String symbol, Instant at, LaunchWindows.Launch l) {
        int had = jdbc.sql("SELECT COUNT(*) FROM signal_log WHERE venue = :v AND symbol = :s AND created_at >= :f AND created_at <= :t")
                .param("v", venue).param("s", symbol).param("f", at.minus(Duration.ofHours(2)).toString()).param("t", at.toString())
                .query(Integer.class).single();
        jdbc.sql("""
                INSERT INTO launch_event(venue, symbol, at, price, ret1m, ret5m, ret15m, vol_ratio, had_signal)
                VALUES (:v, :s, :at, :p, :r1, :r5, :r15, :vr, :h)
                """).param("v", venue).param("s", symbol).param("at", at.toString()).param("p", l.price())
                .param("r1", fin(l.ret1mPct())).param("r5", l.ret5mPct()).param("r15", fin(l.ret15mPct()))
                .param("vr", Double.isFinite(l.volRatio()) ? l.volRatio() : 9999).param("h", had > 0 ? 1 : 0).update();
    }

    private static Double fin(double v) {
        return Double.isFinite(v) ? v : null;
    }

    @Scheduled(cron = "0 41 * * * *", zone = "${trendpilot.zone}")
    public void scheduledOutcomes() {
        try {
            fillOutcomes(Instant.now());
        } catch (RuntimeException e) {
            log.warn("启动事件后续收益补算本次没有完成：{}", e.getMessage());
        }
    }

    /** 补算满 24 小时的事件：后来的最高涨幅、收益、随机基准、Discovery 晚多少分钟。@return 本次处理的条数 */
    public int fillOutcomes(Instant now) {
        List<Object[]> due = jdbc.sql("""
                SELECT id, venue, symbol, at, price FROM launch_event WHERE outcome = 'PENDING' AND at <= :t ORDER BY id LIMIT 500
                """).param("t", now.minus(Duration.ofHours(25)).toString())
                .query((rs, i) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3), Instant.parse(rs.getString(4)),
                        rs.getDouble(5)}).list();
        for (Object[] r : due) {
            long id = (Long) r[0];
            String venue = (String) r[1], sym = (String) r[2];
            Instant at = (Instant) r[3];
            double p0 = (Double) r[4];
            List<double[]> bars = bars(venue, sym, at, Duration.ofHours(24));
            if (bars.size() < 96 * 0.9) {
                jdbc.sql("UPDATE launch_event SET outcome = 'MISSING' WHERE id = :id").param("id", id).update();
                continue;
            }
            double[] h1 = window(bars, at, 1, p0), h4 = window(bars, at, 4, p0), h24 = window(bars, at, 24, p0);
            Double after = jdbc.sql("""
                    SELECT MIN(created_at) FROM signal_log WHERE venue = :v AND symbol = :s AND created_at > :f AND created_at <= :t
                    """).param("v", venue).param("s", sym).param("f", at.toString()).param("t", at.plus(Duration.ofHours(24)).toString())
                    .query(String.class).optional().filter(x -> x != null && !x.isBlank())
                    .map(x -> Duration.between(at, Instant.parse(x)).toSeconds() / 60.0).orElse(null);
            String base = randomPeer(venue, sym, at, id);
            double[] b24 = null;
            if (base != null) {
                List<double[]> bb = bars(venue, base, at, Duration.ofHours(24));
                Double bp = startPrice(venue, base, at);
                if (bb.size() >= 96 * 0.9 && bp != null) b24 = window(bb, at, 24, bp);
            }
            jdbc.sql("""
                    UPDATE launch_event SET max_up_1h = :m1, max_up_4h = :m4, max_up_24h = :m24, ret_1h = :r1, ret_4h = :r4, ret_24h = :r24,
                        signal_after_min = :sa, base_symbol = :bs, base_ret_24h = :br, base_max_up_24h = :bm, outcome = 'DONE'
                    WHERE id = :id
                    """).param("m1", h1[0]).param("m4", h4[0]).param("m24", h24[0]).param("r1", h1[1]).param("r4", h4[1])
                    .param("r24", h24[1]).param("sa", after).param("bs", b24 == null ? null : base)
                    .param("br", b24 == null ? null : b24[1]).param("bm", b24 == null ? null : b24[0]).param("id", id).update();
        }
        return due.size();
    }

    /** at 之后 span 内的 15m K 线：{openTime, high, close}。 */
    private List<double[]> bars(String venue, String sym, Instant at, Duration span) {
        return jdbc.sql("""
                SELECT open_time, high, close FROM kline_15m WHERE venue = :v AND symbol = :s AND open_time >= :f AND open_time < :t
                ORDER BY open_time
                """).param("v", venue).param("s", sym).param("f", at.toEpochMilli()).param("t", at.plus(span).toEpochMilli())
                .query((rs, i) -> new double[]{rs.getLong(1), rs.getDouble(2), rs.getDouble(3)}).list();
    }

    private Double startPrice(String venue, String sym, Instant at) {
        return jdbc.sql("""
                SELECT close FROM kline_15m WHERE venue = :v AND symbol = :s AND open_time < :t AND open_time >= :f ORDER BY open_time DESC LIMIT 1
                """).param("v", venue).param("s", sym).param("t", at.toEpochMilli()).param("f", at.minus(Duration.ofMinutes(30)).toEpochMilli())
                .query(Double.class).optional().orElse(null);
    }

    /** 同交易所、同一时刻有 K 线的其他币里，按事件编号固定挑一个（结果可复现）。 */
    private String randomPeer(String venue, String sym, Instant at, long seed) {
        List<String> peers = jdbc.sql("""
                SELECT DISTINCT symbol FROM kline_15m WHERE venue = :v AND open_time = :t AND symbol != :s AND symbol != 'BTCUSDT'
                ORDER BY symbol
                """).param("v", venue).param("t", Math.floorDiv(at.toEpochMilli(), 900_000L) * 900_000L - 900_000L).param("s", sym)
                .query(String.class).list();
        return peers.isEmpty() ? null : peers.get(new java.util.Random(seed).nextInt(peers.size()));
    }

    /** {最高涨幅 %, 窗口末收盘收益 %}。 */
    static double[] window(List<double[]> bars, Instant at, int hours, double p0) {
        long end = at.toEpochMilli() + hours * 3_600_000L;
        double hi = Double.NEGATIVE_INFINITY, last = Double.NaN;
        for (double[] b : bars) {
            if (b[0] >= end) break;
            hi = Math.max(hi, b[1]);
            last = b[2];
        }
        return new double[]{(hi / p0 - 1) * 100, (last / p0 - 1) * 100};
    }

    /** 每周信号跟踪周报的一节：事件数、各档后来的表现、和随机基准对比、进当天涨幅前 20 的比例、比 Discovery 早多少分钟。 */
    public List<String> weeklyLines(Instant now) {
        List<String> out = new ArrayList<>();
        String since = now.minus(Duration.ofDays(7)).toString();
        int total = jdbc.sql("SELECT COUNT(*) FROM launch_event WHERE at >= :t").param("t", since).query(Integer.class).single();
        out.add("🚀 秒级启动探测（只观察，不买卖）：最近 7 天 " + total + " 次");
        if (total == 0) {
            out.add("还没有记录。");
            return out;
        }
        for (double r : RET_LEVELS) {
            for (double v : VOL_LEVELS) {
                List<double[]> rows = jdbc.sql("""
                        SELECT ret_24h, base_ret_24h, max_up_24h, signal_after_min, had_signal FROM launch_event
                        WHERE at >= :t AND outcome = 'DONE' AND ret5m >= :r AND vol_ratio >= :vr
                        """).param("t", since).param("r", r).param("vr", v)
                        .query((rs, i) -> new double[]{rs.getDouble(1), rs.getObject(2) == null ? Double.NaN : rs.getDouble(2),
                                rs.getDouble(3), rs.getObject(4) == null ? Double.NaN : rs.getDouble(4), rs.getInt(5)}).list();
                if (rows.isEmpty()) continue;
                double[] d = rows.stream().filter(x -> !Double.isNaN(x[1])).mapToDouble(x -> x[0] - x[1]).toArray();
                double mean = java.util.Arrays.stream(d).average().orElse(Double.NaN);
                double sd = Math.sqrt(java.util.Arrays.stream(d).map(x -> (x - mean) * (x - mean)).sum() / Math.max(1, d.length - 1));
                double[] lead = rows.stream().filter(x -> x[4] == 0 && !Double.isNaN(x[3])).mapToDouble(x -> x[3]).sorted().toArray();
                long noSignal = rows.stream().filter(x -> x[4] == 0).count();
                out.add(String.format(Locale.ROOT,
                        "· 5 分钟涨 ≥ %.0f%%、放量 ≥ %.0f 倍：%d 次，24h 后收益均值 %+.2f%%，比同时刻随机币 %+.2f ±%.2f pp（%d 对），24h 最高涨幅中位 %.1f%%；"
                                + "启动前 Discovery 没有信号的 %d 次里，之后 24h 内记到 EARLY 的 %d 次，中位晚 %s 分钟",
                        r, v, rows.size(), rows.stream().mapToDouble(x -> x[0]).average().orElse(Double.NaN), mean,
                        d.length < 2 ? Double.NaN : 1.96 * sd / Math.sqrt(d.length), d.length,
                        median(rows.stream().mapToDouble(x -> x[2]).sorted().toArray()), noSignal, lead.length,
                        lead.length == 0 ? "-" : String.format(Locale.ROOT, "%.0f", median(lead))));
            }
        }
        Double topShare = topGainerShare(since);
        if (topShare != null) out.add(String.format(Locale.ROOT, "· 启动的币当天（UTC 日）进本交易所池内涨幅前 20 的比例 %.0f%%", topShare * 100));
        return out;
    }

    /** 启动事件里，当天（UTC 日开到收）进池内涨幅前 20 的比例；K 线不够时为 null。 */
    private Double topGainerShare(String since) {
        List<Object[]> ev = jdbc.sql("SELECT venue, symbol, at FROM launch_event WHERE at >= :t").param("t", since)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getString(2), Instant.parse(rs.getString(3))}).list();
        int hit = 0, n = 0;
        java.util.Map<String, java.util.Set<String>> cache = new java.util.HashMap<>();
        for (Object[] e : ev) {
            Instant at = (Instant) e[2];
            long dayStart = at.truncatedTo(java.time.temporal.ChronoUnit.DAYS).toEpochMilli();
            String key = e[0] + ":" + dayStart;
            java.util.Set<String> top = cache.computeIfAbsent(key, k -> new java.util.HashSet<>(jdbc.sql("""
                    SELECT symbol FROM (SELECT symbol, MAX(CASE WHEN open_time = :last THEN close END) /
                        MAX(CASE WHEN open_time = :first THEN open END) AS g
                        FROM kline_15m WHERE venue = :v AND open_time IN (:first, :last) GROUP BY symbol)
                    WHERE g IS NOT NULL ORDER BY g DESC LIMIT 20
                    """).param("v", e[0]).param("first", dayStart).param("last", dayStart + 95 * 900_000L).query(String.class).list()));
            if (top.isEmpty()) continue;
            n++;
            if (top.contains((String) e[1])) hit++;
        }
        return n == 0 ? null : hit / (double) n;
    }

    private static double median(double[] sorted) {
        if (sorted.length == 0) return Double.NaN;
        return sorted.length % 2 == 1 ? sorted[sorted.length / 2] : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
    }
}
