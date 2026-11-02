package com.trendpilot.job;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.trendpilot.common.CapitalAuthority;
import com.trendpilot.notify.Notifier;

/**
 * 启动自检：如果上次 Monitor 距今超过 15 分钟，提醒"系统曾中断"。
 * 中断期间的交易不补造；之后的定时任务会用最新数据继续。
 * STARTUP 备注记录启动原因（周复盘 2026-11-01 R5）：版本、上次版本、DEPLOY（版本变了）/ RESTART（版本没变）/ FIRST_START，
 * 以及上次是否正常退出（正常关闭时写 lifecycle:stopped_at；上次启动之后没有这条 = 非正常退出，例如被杀、内存不足、断电）。
 */
@Component
public class StartupCheck {

    public static final String JOB = "STARTUP";

    private static final Logger log = LoggerFactory.getLogger(StartupCheck.class);

    static final String STARTED_AT = "lifecycle:started_at";
    static final String STOPPED_AT = "lifecycle:stopped_at";
    static final String VERSION = "lifecycle:version";

    private final JobRuns runs;
    private final Notifier notifier;
    private final AppState state;
    private final java.nio.file.Path versionFile;

    @org.springframework.beans.factory.annotation.Autowired
    public StartupCheck(JobRuns runs, Notifier notifier, AppState state) {
        this(runs, notifier, state, java.nio.file.Path.of("app", "version.txt"));
    }

    StartupCheck(JobRuns runs, Notifier notifier, AppState state, java.nio.file.Path versionFile) {
        this.runs = runs;
        this.notifier = notifier;
        this.state = state;
        this.versionFile = versionFile;
    }

    /** 启动原因备注。prevVersion / lastStart / lastStop 为 null 表示没有记录。 */
    static String reason(String prevVersion, String version, Instant lastStart, Instant lastStop) {
        String why = prevVersion == null ? "FIRST_START" : prevVersion.equals(version) ? "RESTART" : "DEPLOY";
        String exit = lastStart == null ? "UNKNOWN" : lastStop != null && !lastStop.isBefore(lastStart) ? "CLEAN" : "UNCLEAN";
        return "version=" + version + " prev_version=" + (prevVersion == null ? "?" : prevVersion) + " reason=" + why
                + " previous_exit=" + exit;
    }

    /** 部署写入的版本号（提交前 7 位）；读不到为 "?"。 */
    static String readVersion(java.nio.file.Path f) {
        try {
            String v = java.nio.file.Files.readString(f).strip();
            return v.isEmpty() ? "?" : v.substring(0, Math.min(7, v.length()));
        } catch (java.io.IOException | RuntimeException e) {
            return "?";
        }
    }

    private static Instant instant(java.util.Optional<String> v) {
        try {
            return v.map(Instant::parse).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 正常关闭（systemd stop、部署重启）时记一笔，下次启动据此判断上次是否正常退出。 */
    @EventListener(ContextClosedEvent.class)
    public void onClose() {
        try {
            state.put(STOPPED_AT, Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        } catch (RuntimeException e) {
            log.warn("关闭记录写入失败：{}", e.toString());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String gap = runs.lastStarted(MonitorJob.JOB)
                .filter(t -> Duration.between(t, now).toMinutes() > 15)
                .map(t -> "\n上次持仓检查在 " + Duration.between(t, now).toMinutes() + " 分钟前，期间系统中断，不补造中断期间的交易。")
                .orElse("");
        String version = readVersion(versionFile);
        String why;
        try {
            why = reason(state.get(VERSION).orElse(null), version, instant(state.get(STARTED_AT)), instant(state.get(STOPPED_AT)));
            state.put(VERSION, version);
            state.put(STARTED_AT, now.toString());
        } catch (RuntimeException e) {
            why = "version=" + version + " reason=UNKNOWN";
        }
        runs.record(JOB, null, now, now, "SUCCESS", "N/A", "capital_authority=" + CapitalAuthority.CURRENT + " " + why);
        String instance = com.trendpilot.InstanceLock.instanceId();
        log.info("TrendPilot 已启动，capital_authority={}，实例 {}，{}", CapitalAuthority.CURRENT, instance, why);
        notifier.send("startup:" + now, "TrendPilot 已启动（仅影子模拟）\n机器：" + instance + gap);
    }
}
