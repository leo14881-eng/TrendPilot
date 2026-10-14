package com.trendpilot.job;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.trendpilot.common.CapitalAuthority;
import com.trendpilot.notify.Notifier;

/**
 * 启动自检：如果上次 Monitor 距今超过 15 分钟，提醒"系统曾中断"。
 * 中断期间的交易不补造；之后的定时任务会用最新数据继续。
 */
@Component
public class StartupCheck {

    public static final String JOB = "STARTUP";

    private static final Logger log = LoggerFactory.getLogger(StartupCheck.class);

    private final JobRuns runs;
    private final Notifier notifier;

    public StartupCheck(JobRuns runs, Notifier notifier) {
        this.runs = runs;
        this.notifier = notifier;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String gap = runs.lastStarted(MonitorJob.JOB)
                .filter(t -> Duration.between(t, now).toMinutes() > 15)
                .map(t -> "\n上次持仓检查在 " + Duration.between(t, now).toMinutes() + " 分钟前，期间系统中断，不补造中断期间的交易。")
                .orElse("");
        runs.record(JOB, null, now, now, "SUCCESS", "N/A", "capital_authority=" + CapitalAuthority.CURRENT);
        log.info("TrendPilot 已启动，capital_authority={}", CapitalAuthority.CURRENT);
        notifier.send("startup:" + now, "TrendPilot 已启动（仅影子模拟）" + gap);
    }
}
