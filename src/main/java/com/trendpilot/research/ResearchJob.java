package com.trendpilot.research;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 研究资料刷新：启动 5 分钟后检查一次（避开启动时的行情初始化），之后每小时 :45 检查是否到期（越南时间）。
 * 资料存在 SQLite，未过期不请求；拉取在后台，不占用 Discovery。
 */
@Component
public class ResearchJob {

    private static final Logger log = LoggerFactory.getLogger(ResearchJob.class);

    static final long STARTUP_DELAY_MS = 5 * 60_000L;

    private final ResearchService research;
    private final ResearchProperties props;

    private final org.springframework.beans.factory.ObjectProvider<com.trendpilot.lease.ActiveLease> lease;

    public ResearchJob(ResearchService research, ResearchProperties props,
                       org.springframework.beans.factory.ObjectProvider<com.trendpilot.lease.ActiveLease> lease) {
        this.research = research;
        this.props = props;
        this.lease = lease;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!props.enabled()) return;
        Thread.ofVirtual().name("research-refresh").start(() -> {
            try {
                Thread.sleep(STARTUP_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            var l = lease.getIfAvailable();
            if (l != null && !l.active()) return; // 待命机器不请求外部接口
            refresh();
        });
    }

    @Scheduled(cron = "0 45 * * * *", zone = "${trendpilot.zone}")
    public void scheduled() {
        refresh();
    }

    synchronized void refresh() {
        List<String> done = research.refreshDue(Instant.now());
        if (!done.isEmpty()) log.info("研究资料已刷新：{}", done);
    }
}
