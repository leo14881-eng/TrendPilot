package com.trendpilot.job;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.trendpilot.notify.Messages;
import com.trendpilot.tracking.ExitReplay;
import com.trendpilot.tracking.TrackingReport;
import com.trendpilot.notify.Notifier;
import com.trendpilot.variant.ParallelExits;

/** 每周日 21:05 的信号跟踪与退出规则报告（Telegram）。达不到展示门槛的部分只写"样本不足"。 */
@Component
public class TrackingWeeklyJob {

    private final TrackingReport report;
    private final ExitReplay replay;
    private final ParallelExits parallel;
    private final Notifier notifier;
    private final ZoneId zone;

    public TrackingWeeklyJob(TrackingReport report, ExitReplay replay, ParallelExits parallel, Notifier notifier,
                             @Value("${trendpilot.zone}") String zone) {
        this.report = report;
        this.replay = replay;
        this.parallel = parallel;
        this.notifier = notifier;
        this.zone = ZoneId.of(zone);
    }

    @Scheduled(cron = "0 5 21 * * SUN", zone = "${trendpilot.zone}")
    public void scheduled() {
        send(Instant.now());
    }

    public String text(Instant now) {
        List<String> lines = new ArrayList<>();
        lines.add("📊 信号跟踪周报（只观察，不影响交易）");
        lines.addAll(report.lines(report.summary(now), true));
        lines.add("");
        lines.addAll(replay.lines(replay.stats(replay.latest()), 3));
        var cmp = parallel.compare();
        if (!cmp.isEmpty()) {
            lines.add("");
            lines.add("🔀 候选退出规则影子并行（与 V2 同一批买入）");
            cmp.forEach(c -> lines.add(c.line()));
        }
        return Messages.lines(lines);
    }

    public boolean send(Instant now) {
        return notifier.send("tracking-weekly:" + now.atZone(zone).toLocalDate(), text(now));
    }
}
