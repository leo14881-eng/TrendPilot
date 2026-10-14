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

    public BtcDailyJob(BtcDailyService service, BtcDailyProperties props) {
        this.service = service;
        this.props = props;
    }

    @Scheduled(cron = "40 1,16,31,46 * * * *", zone = "${trendpilot.zone}")
    public void scheduled() {
        if (!props.enabled()) return;
        try {
            service.tick(Instant.now());
        } catch (Exception e) {
            log.warn("BTC 每日短线检查失败：{}", e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!props.enabled()) return;
        Thread.ofVirtual().name("btc-daily-startup").start(() -> {
            try {
                Thread.sleep(STARTUP_DELAY_MS);
                service.tick(Instant.now());
                service.backtestIfNeeded(Instant.now());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("BTC 每日短线启动检查/回测失败：{}", e.getMessage());
            }
        });
    }
}
