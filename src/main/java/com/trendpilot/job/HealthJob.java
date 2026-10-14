package com.trendpilot.job;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.trendpilot.notify.Messages;
import com.trendpilot.notify.Notifier;
import com.trendpilot.portfolio.Position;
import com.trendpilot.portfolio.ShadowBook;

/**
 * 自检（每小时 :07 / :37）与每日汇总（每天 21:00），时间均为越南时间。
 * 发现异常只发提醒，不代表已经恢复。
 */
@Component
public class HealthJob {

    private final JobRuns runs;
    private final Notifier notifier;
    private final ShadowBook book;
    private final ZoneId zone;

    public HealthJob(JobRuns runs, Notifier notifier, ShadowBook book, @Value("${trendpilot.zone}") String zone) {
        this.runs = runs;
        this.notifier = notifier;
        this.book = book;
        this.zone = ZoneId.of(zone);
    }

    @Scheduled(cron = "0 7,37 * * * *", zone = "${trendpilot.zone}")
    public void check() {
        Instant now = Instant.now();
        List<String> problems = problems(now);
        if (!problems.isEmpty()) {
            notifier.send("health:" + now.truncatedTo(ChronoUnit.HOURS), "❗ 自检发现异常\n" + Messages.lines(problems));
        }
    }

    public List<String> problems(Instant now) {
        List<String> out = new ArrayList<>();
        stale(out, MonitorJob.JOB, now, Duration.ofMinutes(15));
        stale(out, DiscoveryJob.JOB, now, Duration.ofMinutes(90));
        return out;
    }

    private void stale(List<String> out, String job, Instant now, Duration max) {
        Instant last = runs.lastStarted(job).orElse(null);
        if (last == null) {
            out.add(job + " 还没有运行记录");
        } else if (Duration.between(last, now).compareTo(max) > 0) {
            out.add(job + " 上次运行在 " + Duration.between(last, now).toMinutes() + " 分钟前");
        }
    }

    @Scheduled(cron = "0 0 21 * * *", zone = "${trendpilot.zone}")
    public void dailySummary() {
        Instant now = Instant.now();
        List<Position> positions = book.positions();
        List<String> lines = new ArrayList<>();
        lines.add("📊 TrendPilot 每日汇总（影子账本 V2）");
        lines.add(String.format(Locale.ROOT, "持仓 %d 个，占用成本 %.2f U，已实现盈亏 %.2f U",
                positions.size(), book.deployedCost(), book.realizedPnl()));
        positions.forEach(p -> lines.add(String.format(Locale.ROOT, "· %s %d 笔，成本 %.2f U%s%s", p.symbol(),
                p.tranches(), p.costBasis().doubleValue(), p.protectArmed() ? "，利润保护中" : "",
                p.lossRecovery() ? "，LOSS_RECOVERY" : "")));
        List<String> problems = problems(now);
        lines.add(problems.isEmpty() ? "任务运行正常" : "异常：" + String.join("；", problems));
        notifier.send("daily:" + now.atZone(zone).toLocalDate(), Messages.lines(lines));
    }
}
