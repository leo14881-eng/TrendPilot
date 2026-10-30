package com.trendpilot.btcdaily;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * BTC 每日短线定时：每 15 分钟（K 线收盘后约 1 分钟）检查一次；越南时间 07:01 那次会生成并推送当天计划。
 * 启动 3 分钟后检查一次，并在当前参数版本还没回测过时跑一次历史回测。
 */
@Component
public class BtcDailyJob {

    private static final Logger log = LoggerFactory.getLogger(BtcDailyJob.class);
    static final long STARTUP_DELAY_MS = 3 * 60_000L;

    private final BtcDailyService service;
    private final BtcDailyProperties props;

    private final org.springframework.beans.factory.ObjectProvider<com.trendpilot.lease.ActiveLease> lease;

    public static final String JOB = "BTC_DAILY";
    private final com.trendpilot.job.JobRuns runs;

    public BtcDailyJob(BtcDailyService service, BtcDailyProperties props,
                       org.springframework.beans.factory.ObjectProvider<com.trendpilot.lease.ActiveLease> lease,
                       com.trendpilot.job.JobRuns runs) {
        this.runs = runs;
        this.service = service;
        this.props = props;
        this.lease = lease;
    }

    @Scheduled(cron = "40 1,16,31,46 * * * *", zone = "${trendpilot.zone}")
    public void scheduled() {
        if (!props.enabled()) return;
        Instant started = Instant.now();
        try {
            service.tick(started, canEnter());
            runs.record(JOB, null, started, Instant.now(), "SUCCESS", "N/A", null);
        } catch (Exception e) {
            log.warn("BTC 每日短线检查失败：{}", e.getMessage());
            // 记失败：连续 3 次失败或 45 分钟没成功，每 5 分钟的自检会发 Telegram
            runs.record(JOB, null, started, Instant.now(), "FAILED", "N/A", String.valueOf(e.getMessage()));
        }
    }

    /** 运行租约已确认才开新一轮（与 V2 开新仓同一判断）；没有启用跨机器保护时总是可以。 */
    private boolean canEnter() {
        var l = lease.getIfAvailable();
        return l == null || l.canEnter();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!props.enabled()) return;
        Thread.ofVirtual().name("btc-daily-startup").start(() -> {
            try {
                Thread.sleep(STARTUP_DELAY_MS);
                var l = lease.getIfAvailable();
                if (l != null && !l.active()) return; // 待命机器不记 BTC 账本
                service.tick(Instant.now(), canEnter());
                service.backtestIfNeeded(Instant.now());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("BTC 每日短线启动检查/回测失败：{}", e.getMessage());
            }
        });
    }
}
