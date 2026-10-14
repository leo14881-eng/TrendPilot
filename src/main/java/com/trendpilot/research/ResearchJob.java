package com.trendpilot.research;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 研究资料刷新：启动后一次，之后每小时 :45 检查是否到期（越南时间）。资料拉取在后台，不占用 Discovery。 */
@Component
public class ResearchJob {

    private static final Logger log = LoggerFactory.getLogger(ResearchJob.class);

    private final ResearchService research;
    private final ResearchProperties props;

    public ResearchJob(ResearchService research, ResearchProperties props) {
        this.research = research;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!props.enabled()) return;
        Thread.ofVirtual().name("research-refresh").start(this::refresh);
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
