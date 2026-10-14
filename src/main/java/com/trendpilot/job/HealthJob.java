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
import com.trendpilot.portfolio.ObservationBook;
import com.trendpilot.portfolio.Position;
import com.trendpilot.portfolio.ShadowBook;

/**
 * 自检（每小时 :07 / :37）与每日汇总（每天 21:00），时间均为越南时间。
 * 任务是否超时从"上次运行"和"本次启动"中较晚的时间算起，刚启动时不误报。
 * 同一组异常只提醒一次；异常变化时再提醒，全部消失时发一条恢复通知。
 */
@Component
public class HealthJob {

    static final String STATE_KEY = "health:problems";

    private final JobRuns runs;
    private final Notifier notifier;
    private final ShadowBook book;
    private final ObservationBook v1;
    private final AppState state;
    private final ZoneId zone;

    public HealthJob(JobRuns runs, Notifier notifier, ShadowBook book, ObservationBook v1, AppState state,
                     @Value("${trendpilot.zone}") String zone) {
        this.runs = runs;
        this.notifier = notifier;
        this.book = book;
        this.v1 = v1;
        this.state = state;
        this.zone = ZoneId.of(zone);
    }

    /** 自检中发现的一项异常；key 用于判断"是不是同一个异常"，text 用于展示。 */
    public record Problem(String key, String text) {
    }

    @Scheduled(cron = "0 7,37 * * * *", zone = "${trendpilot.zone}")
    public void check() {
        check(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    /** @return 本次是否发出了提醒（异常或恢复） */
    public boolean check(Instant now) {
        List<Problem> problems = problems(now);
        String current = String.join(",", problems.stream().map(Problem::key).sorted().toList());
        String previous = state.get(STATE_KEY).orElse("");
        if (current.equals(previous)) {
            return false; // 同一组异常已经提醒过，或一直正常
        }
        state.put(STATE_KEY, current);
        String text = problems.isEmpty()
                ? "✅ 自检恢复正常"
                : "❗ 自检发现异常\n" + Messages.lines(problems.stream().map(Problem::text).toList());
        return notifier.send("health:" + now, text);
    }

    public List<Problem> problems(Instant now) {
        Instant startup = runs.lastStarted(StartupCheck.JOB).orElse(null);
        List<Problem> out = new ArrayList<>();
        stale(out, MonitorJob.JOB, startup, now, Duration.ofMinutes(15));
        stale(out, DiscoveryJob.JOB, startup, now, Duration.ofMinutes(90));
        return out;
    }

    private void stale(List<Problem> out, String job, Instant startup, Instant now, Duration max) {
        Instant last = runs.lastStarted(job).orElse(null);
        // 从上次运行和本次启动中较晚的时间算起；启动后还在宽限期内的不算异常
        Instant since = last == null ? startup : (startup == null || last.isAfter(startup) ? last : startup);
        if (since == null || Duration.between(since, now).compareTo(max) <= 0) {
            return;
        }
        long minutes = Duration.between(since, now).toMinutes();
        out.add(new Problem(job, last == null || last.isBefore(since)
                ? job + " 启动 " + minutes + " 分钟了还没有运行"
                : job + " 上次运行在 " + minutes + " 分钟前"));
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
        lines.add("");
        lines.add("📈 V1 / V2 对比（累计）");
        lines.add(book.stats().line());
        lines.add(v1.stats().line());
        lines.add("注：V2 按全数量盘口模拟成交，V1 按参考价 + 手续费记账，V1 收益不能当作可执行收益；最大回撤含浮亏。");
        List<String> under = new ArrayList<>();
        for (var ledger : List.of(book, v1)) {
            for (Position p : ledger.positions()) {
                double net = ledger.markNet(p.symbol());
                if (net >= 0) continue;
                under.add(String.format(Locale.ROOT, "· %s %s%s 浮亏 %.2f U（%.1f%%），已持有 %.1f 天", ledger.book(),
                        p.symbol(), Messages.venueTag(p.venue()), net, net / p.costBasis().doubleValue() * 100,
                        Duration.between(p.openedAt(), now).toHours() / 24.0));
            }
        }
        lines.add("");
        lines.add(under.isEmpty() ? "被套持仓：无" : "被套持仓（浮亏中，" + under.size() + " 个）");
        lines.addAll(under);
        List<String> problems = problems(now).stream().map(Problem::text).toList();
        lines.add(problems.isEmpty() ? "任务运行正常" : "异常：" + String.join("；", problems));
        notifier.send("daily:" + now.atZone(zone).toLocalDate(), Messages.lines(lines));
    }
}
