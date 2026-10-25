package com.trendpilot.job;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trendpilot.btcdaily.BtcDailyService;
import com.trendpilot.portfolio.ObservationBook;
import com.trendpilot.portfolio.ShadowBook;
import com.trendpilot.replay.BlindReplay;
import com.trendpilot.tracking.ExitReplay;
import com.trendpilot.tracking.TrackingReport;
import com.trendpilot.variant.ParallelExits;

/**
 * 每日统计快照（供每周策略分析）：V1/V2 对比、信号跟踪、各门槛拦截、候选退出规则回放、影子并行、复盘、BTC 每日短线。
 * 只含统计，不含任何密钥。每天 23:58 写入 data/snapshots/yyyy-MM-dd.json，保留 90 天。
 */
@Component
public class SnapshotJob {

    static final int KEEP_DAYS = 90;
    private static final Logger log = LoggerFactory.getLogger(SnapshotJob.class);

    private final ShadowBook v2;
    private final ObservationBook v1;
    private final com.trendpilot.portfolio.DepthVariantBook depthVariant;
    private final com.trendpilot.portfolio.StrongVariantBook strongVariant;
    private final TrackingReport tracking;
    private final ExitReplay exitReplay;
    private final ParallelExits parallel;
    private final BlindReplay blind;
    private final BtcDailyService btcDaily;
    private final JdbcClient jdbc;
    private final Path dir;
    private final ZoneId zone;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public SnapshotJob(ShadowBook v2, ObservationBook v1, TrackingReport tracking, ExitReplay exitReplay,
                       ParallelExits parallel, BlindReplay blind, BtcDailyService btcDaily, JdbcClient jdbc,
                       @Value("${trendpilot.data-dir:./data}") String dataDir, @Value("${trendpilot.zone}") String zone,
                       com.trendpilot.portfolio.DepthVariantBook depthVariant,
                       com.trendpilot.portfolio.StrongVariantBook strongVariant) {
        this.depthVariant = depthVariant;
        this.strongVariant = strongVariant;
        this.v2 = v2;
        this.v1 = v1;
        this.tracking = tracking;
        this.exitReplay = exitReplay;
        this.parallel = parallel;
        this.blind = blind;
        this.btcDaily = btcDaily;
        this.jdbc = jdbc;
        this.dir = Path.of(dataDir, "snapshots");
        this.zone = ZoneId.of(zone);
    }

    private org.springframework.beans.factory.ObjectProvider<com.trendpilot.variant.VariantRegistry> variants;

    @org.springframework.beans.factory.annotation.Autowired
    void setVariants(org.springframework.beans.factory.ObjectProvider<com.trendpilot.variant.VariantRegistry> variants) {
        this.variants = variants;
    }

    /** 当前快照内容。 */
    public Map<String, Object> snapshot(Instant now) {
        String since = now.minus(Duration.ofDays(7)).toString();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("at", now);
        s.put("paramsVersion", jdbc.sql("SELECT params_version FROM decision_log ORDER BY id DESC LIMIT 1")
                .query(String.class).optional().orElse(null));
        List<Object> books = new java.util.ArrayList<>(List.of(v2.stats(), v1.stats(), depthVariant.stats(), strongVariant.stats()));
        Map<String, Object> open = new LinkedHashMap<>(Map.of("V2", v2.positions().size(), "V1", v1.positions().size(),
                "V2D15", depthVariant.positions().size(), "V5", strongVariant.positions().size()));
        try {
            var reg = variants == null ? null : variants.getIfAvailable();
            if (reg != null) {
                for (var v : reg.all()) {
                    if (v.name().equals(com.trendpilot.portfolio.StrongVariantBook.BOOK)) continue;
                    books.add(v.book().stats());
                    open.put(v.name(), v.book().positions().size());
                }
            }
        } catch (RuntimeException e) {
            log.warn("快照里的影子变体读不到：{}", e.getMessage());
        }
        s.put("books", books);
        s.put("openPositions", open);
        s.put("tracking", tracking.summary(now));
        s.put("exitReplay", exitReplay.stats(exitReplay.latest()));
        s.put("parallelShadow", parallel.compare());
        s.put("blindReplay7d", blind.summary(now, 7));
        s.put("btcDaily", btcDaily.summary(now));
        s.put("decisions7d", rows("""
                SELECT action, decision, funded, reasons, COUNT(*) AS n, ROUND(AVG(score), 2) AS avg_score
                FROM decision_log WHERE created_at >= :since
                GROUP BY action, decision, funded, reasons ORDER BY n DESC LIMIT 80""", since));
        s.put("exits7d", rows("""
                SELECT book, reason_code, COUNT(*) AS n, ROUND(SUM(pnl_usdt), 2) AS pnl,
                       ROUND(AVG(hold_minutes), 0) AS avg_hold_min, ROUND(AVG(max_net), 2) AS avg_max_net,
                       ROUND(AVG(min_net), 2) AS avg_min_net
                FROM shadow_event WHERE type = 'SELL' AND created_at >= :since
                GROUP BY book, reason_code ORDER BY n DESC""", since));
        s.put("missedReplay7d", rows("""
                SELECT outcome, COUNT(*) AS n, ROUND(AVG(change_pct), 1) AS avg_change
                FROM missed_replay WHERE day >= substr(:since, 1, 10) GROUP BY outcome ORDER BY n DESC""", since));
        return s;
    }

    private List<Map<String, Object>> rows(String sql, String since) {
        return jdbc.sql(sql).param("since", since).query().listOfRows();
    }

    @Scheduled(cron = "0 58 23 * * *", zone = "${trendpilot.zone}")
    public void scheduled() {
        try {
            write(Instant.now());
        } catch (Exception e) {
            log.warn("统计快照写入失败：{}", e.getMessage());
        }
    }

    public Path write(Instant now) throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve(now.atZone(zone).toLocalDate() + ".json");
        json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), snapshot(now));
        String cutoff = now.atZone(zone).toLocalDate().minusDays(KEEP_DAYS) + ".json";
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().compareTo(cutoff) < 0).forEach(f -> f.toFile().delete());
        }
        return file;
    }
}
