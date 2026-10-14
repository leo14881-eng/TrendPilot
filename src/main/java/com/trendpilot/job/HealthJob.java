package com.trendpilot.job;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.trendpilot.health.ApiStats;
import com.trendpilot.market.Venue;
import com.trendpilot.notify.Messages;
import com.trendpilot.notify.Notifier;
import com.trendpilot.portfolio.ObservationBook;
import com.trendpilot.portfolio.Position;
import com.trendpilot.portfolio.ShadowBook;
import com.trendpilot.tracking.TrackingReport;

/**
 * 自检（每小时 :07 / :37）与每日汇总（每天 21:00），时间均为越南时间。
 * 自检项：Monitor / Discovery 是否按时跑、是否连续失败；WebSocket K 线是否停更；各接口最近 1 小时是否被限流或大量失败；
 * 昨天的统计快照是否写了。每日汇总另附 24 小时运行情况（启动次数、任务次数和失败、接口请求量、Telegram 失败、K 线新鲜度）。
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
    private final TrackingReport tracking;
    private final JdbcClient jdbc;
    private final ApiStats api;
    private final Path snapshots;

    static final Duration KLINE_STALE = Duration.ofMinutes(45);
    static final int API_LIMITED_PER_HOUR = 3;
    static final int API_ERRORS_PER_HOUR = 5;

    public HealthJob(JobRuns runs, Notifier notifier, ShadowBook book, ObservationBook v1, AppState state,
                     @Value("${trendpilot.zone}") String zone, TrackingReport tracking, JdbcClient jdbc, ApiStats api,
                     @Value("${trendpilot.data-dir:./data}") String dataDir) {
        this.tracking = tracking;
        this.jdbc = jdbc;
        this.api = api;
        this.snapshots = Path.of(dataDir, "snapshots");
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
        for (String job : List.of(MonitorJob.JOB, DiscoveryJob.JOB)) {
            List<String> recent = jdbc.sql("""
                    SELECT program_status FROM job_run WHERE job = :j AND started_at <= :now
                    ORDER BY started_at DESC LIMIT 3
                    """).param("j", job).param("now", now.toString()).query(String.class).list();
            if (recent.size() == 3 && recent.stream().allMatch("FAILED"::equals)) {
                out.add(new Problem(job + ":failing", job + " 最近 3 次都失败"));
            }
        }
        for (Map.Entry<Venue, Instant> e : klineLatest(now).entrySet()) {
            long minutes = Duration.between(e.getValue(), now).toMinutes();
            if (minutes > KLINE_STALE.toMinutes()) {
                out.add(new Problem("kline:" + e.getKey(), e.getKey() + " K 线 " + minutes + " 分钟没有更新（实时行情可能断了）"));
            }
        }
        api.since(Duration.ofHours(1), now).forEach((host, c) -> {
            if (c.limited() >= API_LIMITED_PER_HOUR) {
                out.add(new Problem("api-limited:" + host, host + " 最近 1 小时被限流 " + c.limited() + " 次（共 " + c.requests() + " 次请求）"));
            } else if (c.errors() >= API_ERRORS_PER_HOUR && c.errors() * 3 >= c.requests()) {
                out.add(new Problem("api-errors:" + host, host + " 最近 1 小时失败 " + c.errors() + " 次（共 " + c.requests() + " 次请求）"));
            }
        });
        // 快照每天 23:58 写；过了 00:30 还没有昨天的就提醒
        var local = now.atZone(zone);
        if (startup != null && Duration.between(startup, now).toHours() >= 24 && local.toLocalTime().isAfter(java.time.LocalTime.of(0, 30))) {
            Path file = snapshots.resolve(local.toLocalDate().minusDays(1) + ".json");
            if (!Files.exists(file)) out.add(new Problem("snapshot", "昨天的统计快照没有写出：" + file.getFileName()));
        }
        return out;
    }

    /** 最近 24 小时内有 K 线的交易所，各自最新一根 K 线的收盘时间。 */
    Map<Venue, Instant> klineLatest(Instant now) {
        Map<Venue, Instant> out = new java.util.EnumMap<>(Venue.class);
        jdbc.sql("""
                SELECT venue, MAX(close_time) AS t FROM kline_15m
                WHERE open_time >= :from AND open_time <= :now GROUP BY venue
                """).param("from", now.minus(Duration.ofHours(24)).toEpochMilli()).param("now", now.toEpochMilli())
                .query((rs, i) -> Map.entry(rs.getString("venue"), rs.getLong("t"))).list()
                .forEach(e -> {
                    try {
                        out.put(Venue.valueOf(e.getKey()), Instant.ofEpochMilli(Math.min(e.getValue(), now.toEpochMilli())));
                    } catch (IllegalArgumentException ignored) {
                        // 未知交易所名，跳过
                    }
                });
        return out;
    }

    /** 每日汇总里的 24 小时运行情况。 */
    List<String> healthLines(Instant now) {
        Instant from = now.minus(Duration.ofHours(24));
        List<String> lines = new ArrayList<>();
        lines.add("🩺 运行情况（24 小时）");
        Instant startup = runs.lastStarted(StartupCheck.JOB).orElse(null);
        long starts = jdbc.sql("SELECT COUNT(*) FROM job_run WHERE job = :j AND started_at >= :f")
                .param("j", StartupCheck.JOB).param("f", from.toString()).query(Long.class).single();
        lines.add(String.format(Locale.ROOT, "服务已连续运行 %s，24 小时内启动 %d 次",
                startup == null ? "?" : String.format(Locale.ROOT, "%.1f 小时", Duration.between(startup, now).toMinutes() / 60.0), starts));
        for (String job : List.of(MonitorJob.JOB, MonitorJob.FAST_JOB, DiscoveryJob.JOB)) {
            Map<String, Object> r = jdbc.sql("""
                    SELECT COUNT(*) AS n, COALESCE(SUM(program_status = 'FAILED'), 0) AS f,
                           COALESCE(SUM(data_status NOT IN ('COMPLETE', 'N/A')), 0) AS d
                    FROM job_run WHERE job = :j AND started_at >= :f
                    """).param("j", job).param("f", from.toString()).query().singleRow();
            lines.add(String.format(Locale.ROOT, "%s：运行 %s 次，失败 %s 次，数据不完整 %s 次", job, r.get("n"), r.get("f"), r.get("d")));
        }
        Map<Venue, Instant> k = klineLatest(now);
        lines.add(k.isEmpty() ? "K 线：24 小时内没有数据" : "K 线最新：" + String.join("，", k.entrySet().stream()
                .map(e -> e.getKey() + " " + Duration.between(e.getValue(), now).toMinutes() + " 分钟前").toList()));
        Map<String, ApiStats.Counts> calls = api.since(Duration.ofHours(24), now);
        lines.add(calls.isEmpty() ? "接口请求：无（统计从本次启动开始）" : "接口请求（从本次启动或 24 小时内）：");
        calls.forEach((host, c) -> lines.add(String.format(Locale.ROOT, "· %s %d 次，限流 %d，失败 %d", host, c.requests(), c.limited(), c.errors())));
        long tgFailed = jdbc.sql("""
                SELECT COUNT(*) FROM notification_outbox WHERE status = 'FAILED' AND created_at >= :f AND created_at < :n
                """).param("f", from.toString()).param("n", now.minus(Duration.ofMinutes(10)).toString()).query(Long.class).single();
        lines.add("Telegram 未发出：" + tgFailed + " 条");
        return lines;
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
        lines.add("");
        try {
            lines.addAll(tracking.lines(tracking.summary(now), false));
        } catch (RuntimeException e) {
            lines.add("信号跟踪统计暂不可用：" + e.getMessage());
        }
        lines.add("");
        lines.addAll(healthLines(now));
        List<String> problems = problems(now).stream().map(Problem::text).toList();
        lines.add(problems.isEmpty() ? "自检：全部正常" : "自检异常：" + String.join("；", problems));
        notifier.send("daily:" + now.atZone(zone).toLocalDate(), Messages.lines(lines));
    }
}
