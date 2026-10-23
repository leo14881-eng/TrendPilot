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
    private final com.trendpilot.portfolio.DepthVariantBook depthVariant;
    private final com.trendpilot.portfolio.StrongVariantBook strongVariant;
    private final JdbcClient jdbc;
    private final ApiStats api;
    private final Path snapshots;
    private final Path reports;

    static final Duration KLINE_STALE = Duration.ofMinutes(45);
    static final int API_LIMITED_PER_HOUR = 3;
    static final int API_ERRORS_PER_HOUR = 5;

    private final com.trendpilot.market.stream.StreamHub hub;
    private final boolean streamEnabled;
    private final boolean btcEnabled;

    public HealthJob(JobRuns runs, Notifier notifier, ShadowBook book, ObservationBook v1, AppState state,
                     @Value("${trendpilot.zone}") String zone, TrackingReport tracking, JdbcClient jdbc, ApiStats api,
                     @Value("${trendpilot.data-dir:./data}") String dataDir,
                     com.trendpilot.portfolio.DepthVariantBook depthVariant,
                     com.trendpilot.market.stream.StreamHub hub, com.trendpilot.market.stream.StreamProperties streamProps,
                     com.trendpilot.btcdaily.BtcDailyProperties btcProps,
                     com.trendpilot.portfolio.StrongVariantBook strongVariant) {
        this.depthVariant = depthVariant;
        this.strongVariant = strongVariant;
        this.hub = hub;
        this.streamEnabled = streamProps.enabled();
        this.btcEnabled = btcProps.enabled();
        this.tracking = tracking;
        this.jdbc = jdbc;
        this.api = api;
        this.snapshots = Path.of(dataDir, "snapshots");
        this.reports = Path.of(dataDir, "reports");
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

    /** 每 5 分钟检查一次（同一组异常只提醒一次，恢复时再提醒），发现问题最多晚 5 分钟。 */
    @Scheduled(cron = "30 */5 * * * *", zone = "${trendpilot.zone}")
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
        if (btcEnabled) stale(out, com.trendpilot.btcdaily.BtcDailyJob.JOB, startup, now, Duration.ofMinutes(45));
        for (String job : List.of(MonitorJob.JOB, DiscoveryJob.JOB, com.trendpilot.btcdaily.BtcDailyJob.JOB)) {
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
        // FastWatch 靠 WebSocket 最优价：持仓超过 3 分钟没有新报价，快速保护就失效了
        if (streamEnabled && startup != null && Duration.between(startup, now).toMinutes() >= 5) {
            List<String> noQuote = book.positions().stream()
                    .filter(p -> hub.quote(p.venue(), p.symbol(), 180) == null)
                    .map(Position::symbol).sorted().toList();
            if (!noQuote.isEmpty()) {
                out.add(new Problem("quotes:" + String.join(",", noQuote),
                        String.join("、", noQuote) + " 超过 3 分钟没有实时报价（FastWatch 快速保护暂时失效，5 分钟一次的持仓检查照常）"));
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

    /** 每日汇总正文上限（Telegram 单条 4096，扣掉项目名前缀后留余量）。 */
    static final int BRIEF_MAX = 3000;
    /** 正文里最多列出的 V2 持仓 / 被套行数，其余见附件。 */
    static final int BRIEF_MAX_ROWS = 20;

    /** 每日汇总：brief 发 Telegram 正文（只列 V2），full 是完整明细（Markdown，作附件发送并存档）。 */
    public record Digest(String brief, String full) {
    }

    @Scheduled(cron = "0 0 21 * * *", zone = "${trendpilot.zone}")
    public void dailySummary() {
        Instant now = Instant.now();
        java.time.LocalDate day = now.atZone(zone).toLocalDate();
        Digest d = digest(now);
        notifier.send("daily:" + day, d.brief());
        try {
            Files.createDirectories(reports);
            Files.writeString(reports.resolve("daily-summary-" + day + ".md"), d.full());
        } catch (java.io.IOException e) {
            org.slf4j.LoggerFactory.getLogger(HealthJob.class).warn("每日汇总明细写文件失败：{}", e.getMessage());
        }
        notifier.sendDocument("daily-doc:" + day, "TrendPilot-daily-summary-" + day + ".md", d.full(),
                "每日汇总完整明细（含 V1 全部持仓、运行情况）");
    }

    public Digest digest(Instant now) {
        List<Position> positions = book.positions();
        String head = String.format(Locale.ROOT, "持仓 %d 个，占用成本 %.2f U，已实现盈亏 %.2f U",
                positions.size(), book.deployedCost(), book.realizedPnl());
        List<String> v2Rows = positions.stream().map(p -> String.format(Locale.ROOT, "· %s %d 笔，成本 %.2f U%s%s", p.symbol(),
                p.tranches(), p.costBasis().doubleValue(), p.protectArmed() ? "，利润保护中" : "",
                p.lossRecovery() ? "，LOSS_RECOVERY" : "")).toList();
        List<String> v2Under = underwater(book, now);
        List<String> v1Under = underwater(v1, now);
        var v2Stats = book.stats();
        var v1Stats = v1.stats();
        var dStats = depthVariant.stats();
        var sStats = strongVariant.stats();
        String tracking;
        List<String> trackingFull;
        try {
            var summary = this.tracking.summary(now);
            tracking = String.join(" ", this.tracking.lines(summary, false));
            trackingFull = this.tracking.lines(summary, false);
        } catch (RuntimeException e) {
            tracking = "信号跟踪统计暂不可用：" + e.getMessage();
            trackingFull = List.of(tracking);
        }
        List<String> problems = problems(now).stream().map(Problem::text).toList();
        String selfCheck = problems.isEmpty() ? "自检：全部正常" : "自检异常：" + String.join("；", problems);

        // 正文：只列 V2，其余一行汇总
        List<String> b = new ArrayList<>();
        b.add("📊 TrendPilot 每日汇总（影子账本 V2）");
        b.add(head);
        addCapped(b, v2Rows);
        b.add("");
        b.add(v2Under.isEmpty() ? "V2 被套持仓：无" : "V2 被套持仓（浮亏中，" + v2Under.size() + " 个）");
        addCapped(b, v2Under);
        b.add("");
        b.add("📈 累计对比");
        b.add(v2Stats.shortLine());
        b.add(dStats.shortLine() + "（深度门槛放宽，对照用）");
        b.add(sStats.shortLine() + "（强信号买入，对照用）");
        b.add(v1Stats.shortLine() + "（观察账本，按参考价记账）");
        b.add("");
        b.add(tracking);
        b.add(selfCheck);
        b.add("完整明细（V1 全部持仓、被套列表、运行情况）见附件 .md");
        String brief = cap(Messages.lines(b));

        // 完整明细（Markdown）
        List<String> f = new ArrayList<>();
        f.add("# TrendPilot 每日汇总 " + now.atZone(zone).toLocalDate());
        f.add("");
        f.add("## V2 持仓");
        f.add(head);
        f.add("");
        v2Rows.forEach(r -> f.add(md(r)));
        f.add("");
        f.add("## 累计对比");
        f.add("- " + v2Stats.line());
        f.add("- " + v1Stats.line());
        f.add("- " + dStats.line() + "（深度门槛放宽到 15000 时多买的币，对照用）");
        f.add("- " + sStats.line() + "（只买 1h 相对 BTC 明显走强的币，与 V2 同期对比，对照用）");
        f.add("");
        f.add("注：V2 按全数量盘口模拟成交，V1 按参考价 + 手续费记账，V1 收益不能当作可执行收益；最大回撤含浮亏。");
        f.add("");
        f.add("## 被套持仓（浮亏中，" + (v2Under.size() + v1Under.size()) + " 个）");
        f.add("");
        f.add("### V2（" + v2Under.size() + " 个）");
        v2Under.forEach(r -> f.add(md(r)));
        f.add("");
        f.add("### V1（" + v1Under.size() + " 个）");
        v1Under.forEach(r -> f.add(md(r)));
        f.add("");
        f.add("## V1 全部持仓（" + v1.positions().size() + " 个）");
        f.add("");
        for (Position p : v1.positions()) {
            f.add(String.format(Locale.ROOT, "- %s%s 成本 %.2f U，浮动 %.2f U，已持有 %.1f 天", p.symbol(), Messages.venueTag(p.venue()),
                    p.costBasis().doubleValue(), v1.markNet(p.symbol()), Duration.between(p.openedAt(), now).toHours() / 24.0));
        }
        f.add("");
        f.add("## 信号跟踪");
        trackingFull.forEach(f::add);
        f.add("");
        f.add("## 运行情况（24 小时）");
        healthLines(now).stream().skip(1).forEach(r -> f.add(md(r)));
        f.add("");
        f.add(selfCheck);
        return new Digest(brief, String.join("\n", f) + "\n");
    }

    private List<String> underwater(com.trendpilot.portfolio.Ledger ledger, Instant now) {
        List<String> out = new ArrayList<>();
        for (Position p : ledger.positions()) {
            double net = ledger.markNet(p.symbol());
            if (net >= 0) continue;
            out.add(String.format(Locale.ROOT, "· %s%s 浮亏 %.2f U（%.1f%%），已持有 %.1f 天",
                    p.symbol(), Messages.venueTag(p.venue()), net, net / p.costBasis().doubleValue() * 100,
                    Duration.between(p.openedAt(), now).toHours() / 24.0));
        }
        return out;
    }

    private static void addCapped(List<String> out, List<String> rows) {
        rows.stream().limit(BRIEF_MAX_ROWS).forEach(out::add);
        if (rows.size() > BRIEF_MAX_ROWS) out.add("……另有 " + (rows.size() - BRIEF_MAX_ROWS) + " 个见附件");
    }

    static String cap(String text) {
        if (text.length() <= BRIEF_MAX) return text;
        String tail = "\n……完整内容见附件";
        String cut = text.substring(0, BRIEF_MAX - tail.length());
        int nl = cut.lastIndexOf('\n');
        return (nl > 0 ? cut.substring(0, nl) : cut) + tail;
    }

    private static String md(String line) {
        return line.startsWith("· ") ? "- " + line.substring(2) : line;
    }
}
