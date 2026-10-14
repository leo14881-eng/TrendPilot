package com.trendpilot.web;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * monitorFailing：最近 3 次 Monitor 全部失败（进程没卡，多半是行情接口问题，重启无用，只提醒）。
 * lastMonitorOk：最近一次成功的 Monitor，自动更新用它确认新版本真的能正常干活。
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
        List<String> recent = jdbc.sql("SELECT program_status FROM job_run WHERE job = :j ORDER BY id DESC LIMIT 3")
                .param("j", MonitorJob.JOB).query(String.class).list();
        boolean monitorFailing = recent.size() == 3 && recent.stream().allMatch("FAILED"::equals);
        Optional<String> lastOk = jdbc.sql("SELECT MAX(started_at) FROM job_run WHERE job = :j AND program_status = 'SUCCESS'")
                .param("j", MonitorJob.JOB).query(String.class).optional();
        return Map.of("ok", !monitorStale && !discoveryStale, "uptimeMinutes", uptime.toMinutes(),
                "lastMonitor", monitor.map(Instant::toString).orElse(""),
                "lastMonitorOk", lastOk.orElse(""),
                "lastDiscovery", discovery.map(Instant::toString).orElse(""),
                "monitorStale", monitorStale, "discoveryStale", discoveryStale, "monitorFailing", monitorFailing);
    }

    private Optional<Instant> last(String job) {
        return jdbc.sql("SELECT MAX(started_at) FROM job_run WHERE job = :j").param("j", job).query(String.class)
                .optional().map(Instant::parse);
    }
}
