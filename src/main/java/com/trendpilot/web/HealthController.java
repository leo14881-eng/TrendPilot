package com.trendpilot.web;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.trendpilot.job.DiscoveryJob;
import com.trendpilot.job.MonitorJob;

/**
 * 轻量健康检查（守护脚本每 2 分钟调用）：Monitor 每 5 分钟应跑一次，超过 20 分钟没有记录视为卡死；
 * Discovery 每小时一次，超过 150 分钟视为卡死。刚启动不足 20 分钟不判卡死。
 */
@RestController
public class HealthController {

    static final Duration MONITOR_STALE = Duration.ofMinutes(20);
    static final Duration DISCOVERY_STALE = Duration.ofMinutes(150);

    private final JdbcClient jdbc;

    public HealthController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Instant now = Instant.now();
        Duration uptime = Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime());
        Optional<Instant> monitor = last(MonitorJob.JOB);
        Optional<Instant> discovery = last(DiscoveryJob.JOB);
        boolean warm = uptime.compareTo(MONITOR_STALE) > 0;
        boolean monitorStale = warm && monitor.map(t -> Duration.between(t, now).compareTo(MONITOR_STALE) > 0).orElse(true);
        boolean discoveryStale = uptime.compareTo(DISCOVERY_STALE) > 0
                && discovery.map(t -> Duration.between(t, now).compareTo(DISCOVERY_STALE) > 0).orElse(true);
        return Map.of("ok", !monitorStale && !discoveryStale, "uptimeMinutes", uptime.toMinutes(),
                "lastMonitor", monitor.map(Instant::toString).orElse(""),
                "lastDiscovery", discovery.map(Instant::toString).orElse(""),
                "monitorStale", monitorStale, "discoveryStale", discoveryStale);
    }

    private Optional<Instant> last(String job) {
        return jdbc.sql("SELECT MAX(started_at) FROM job_run WHERE job = :j").param("j", job).query(String.class)
                .optional().map(Instant::parse);
    }
}
