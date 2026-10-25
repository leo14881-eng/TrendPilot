package com.trendpilot.variant;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.trendpilot.common.CapitalAuthority;
import com.trendpilot.notify.Notifier;
import com.trendpilot.portfolio.Ledger;
import com.trendpilot.portfolio.ShadowBook;

/**
 * 自动转正与淘汰（每天越南 20:40 算一次，结果写 variant_eval，日报和每日汇总显示）。
 * <p>
 * <b>转正</b>（"回测好 + 实盘影子 30 天好"两道关，用户 2026-10-26）：变体上线满 {@code minDays}（默认 30）天，同期（变体上线以来）
 * 合计（已实现 + 未实现）高于 V2、最大回撤 ≤ V2 × 1.1、每笔期望高于 V2、平仓 ≥ 20 笔，并且回测发布前检查通过，才是转正候选。
 * 候选只发一条 Telegram 并写进日报；开关 {@code autoPromote} 打开（默认）且距上次正式改动满 14 天时，自动把 V2 的参数集换成这个变体的
 * （{@link ParamSets}），用户回复"回滚"恢复上一个。每天最多转正一个。
 * <p>
 * <b>淘汰</b>：满 {@code minDays} 天合计低于 V2、或回撤 &gt; V2 × 1.3 → 停开新仓，持仓按规则卖完后归档；
 * 满 {@code earlyStopDays}（14）天回撤已经 &gt; V2 × 1.5 的提前停开新仓。其余都等满 30 天再判断。
 * <p>
 * <b>安全边界</b>（写死在代码里）：只改 V2 这个影子账本的参数集；永远不改 BTC 每日短线（不止损、不卖亏）、BTC 均线趋势、V1、V3。
 * 以后有真钱账本时，必须用户明确打开 {@code realMoneyAutoPromote}（默认关）才会自动改真钱规则。
 */
@Component
public class AutoPromoter {

    private static final Logger log = LoggerFactory.getLogger(AutoPromoter.class);
    /** 自动转正唯一能改的账本。 */
    public static final String TARGET = ShadowBook.BOOK;

    private final VariantRegistry registry;
    private final ParamSets paramSets;
    private final ShadowBook v2;
    private final JdbcClient jdbc;
    private final Notifier notifier;
    private final OptimizeProperties opt;
    private final ZoneId zone;

    public AutoPromoter(VariantRegistry registry, ParamSets paramSets, ShadowBook v2, JdbcClient jdbc, Notifier notifier,
                        OptimizeProperties opt, @Value("${trendpilot.zone}") String zone) {
        this.registry = registry;
        this.paramSets = paramSets;
        this.v2 = v2;
        this.jdbc = jdbc;
        this.notifier = notifier;
        this.opt = opt;
        this.zone = ZoneId.of(zone);
    }

    /**
     * 能不能自动改这个账本的规则。只有 V2；真钱账本还要用户明确打开 realMoneyAutoPromote。
     * BTC 每日短线、BTC 均线趋势、V1、V3 永远返回 false。
     */
    public static boolean mayAutoChange(String book, boolean realMoney, OptimizeProperties p) {
        if (!TARGET.equals(book)) return false;
        return !realMoney || p.realMoneyAutoPromote();
    }

    /** V2 现在是不是真钱账本（本系统目前只有影子模拟）。 */
    static boolean v2IsRealMoney() {
        return CapitalAuthority.CURRENT != CapitalAuthority.NONE_SHADOW_ONLY;
    }

    /** 同期表现：start 起的合计变化、最大回撤（含浮亏的权益曲线）、平仓笔数、每笔平均。 */
    public record Perf(double total, double maxDd, int closed, double perTrade) {
    }

    Perf perf(Ledger book, Instant start, Instant now) {
        double base = jdbc.sql("SELECT realized + unrealized FROM equity_curve WHERE book = :b AND at <= :t ORDER BY at DESC, id DESC LIMIT 1")
                .param("b", book.book()).param("t", start.toString()).query(Double.class).optional().orElse(0.0);
        List<Double> pts = new ArrayList<>(jdbc.sql("SELECT realized + unrealized FROM equity_curve WHERE book = :b AND at > :t AND at <= :n ORDER BY at, id")
                .param("b", book.book()).param("t", start.toString()).param("n", now.toString()).query(Double.class).list());
        pts.add(book.realizedPnl() + book.unrealizedPnl());
        double peak = base, dd = 0;
        for (double v : pts) {
            peak = Math.max(peak, v);
            dd = Math.max(dd, peak - v);
        }
        List<Double> sells = jdbc.sql("""
                SELECT CAST(pnl_usdt AS REAL) FROM shadow_event WHERE book = :b AND type = 'SELL' AND pnl_usdt IS NOT NULL
                    AND created_at >= :t AND created_at <= :n
                """).param("b", book.book()).param("t", start.toString()).param("n", now.toString()).query(Double.class).list();
        double avg = sells.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        return new Perf(pts.getLast() - base, dd, sells.size(), avg);
    }

    /** 一个变体的评估结论。 */
    public record Eval(String variant, double daysLive, Perf mine, Perf v2, String decision, String reason) {
    }

    @Scheduled(cron = "0 40 20 * * *", zone = "${trendpilot.zone}")
    public void scheduled() {
        try {
            evaluate(Instant.now());
        } catch (RuntimeException e) {
            log.warn("自动转正评估本次没有完成：{}", e.toString());
        }
    }

    /** 评估全部变体，执行停开新仓 / 转正，返回每个变体的结论。 */
    public synchronized List<Eval> evaluate(Instant now) {
        String day = now.atZone(zone).toLocalDate().toString();
        List<Eval> out = new ArrayList<>();
        boolean promotedToday = false;
        List<Eval> candidates = new ArrayList<>();
        for (VariantRegistry.Variant v : registry.all()) {
            if (!v.opensNew()) continue;
            try {
                Eval e = judge(v, now);
                if (e.decision().equals("CANDIDATE")) candidates.add(e);
                out.add(e);
            } catch (RuntimeException ex) {
                log.warn("{} 评估出错，跳过：{}", v.name(), ex.toString());
            }
        }
        // 候选里同期合计最高的一个才可能转正（每天最多一个）
        candidates.sort((a, b) -> Double.compare(b.mine().total(), a.mine().total()));
        List<Eval> finalOut = new ArrayList<>();
        for (Eval e : out) {
            Eval f = e;
            VariantRegistry.Variant v = registry.get(e.variant()).orElseThrow();
            switch (e.decision()) {
                case "STOP" -> {
                    registry.setStatus(v.name(), VariantRegistry.STOPPED, e.reason(), now);
                    notifier.send("optimize:stop:" + v.name(), String.format(Locale.ROOT,
                            "🧪 影子变体 %s 停开新仓：%s。现有持仓按原规则卖完后归档。\n参数：%s", v.name(), e.reason(), v.overrides().describe()));
                }
                case "CANDIDATE" -> {
                    boolean best = !candidates.isEmpty() && candidates.getFirst() == e;
                    boolean allowed = paramSets.changeAllowed(now);
                    boolean may = mayAutoChange(TARGET, v2IsRealMoney(), opt);
                    if (best && !promotedToday && allowed && may && opt.autoPromote()) {
                        paramSets.promote(v.name(), v.overrides(), now, e.reason());
                        registry.setStatus(v.name(), VariantRegistry.PROMOTED, "转正：" + e.reason(), now);
                        promotedToday = true;
                        f = new Eval(e.variant(), e.daysLive(), e.mine(), e.v2(), "PROMOTED", e.reason());
                        notifier.send("optimize:promote:" + v.name(), String.format(Locale.ROOT,
                                "🧪 影子变体 %s 满足转正条件，V2 已改用它的参数（只影响影子账本）：%s\n%s\n回复「回滚」恢复上一个参数集。",
                                v.name(), v.overrides().describe(), e.reason()));
                    } else {
                        String wait = !allowed ? "距上次正式改动不满 " + opt.formalChangeDays() + " 天，最早 "
                                + paramSets.nextChangeAllowed().atZone(zone).toLocalDate() + " 可换"
                                : !best || promotedToday ? "今天已有更好的候选" : !may ? "目标账本不允许自动改" : "自动转正开关已关闭，等用户决定";
                        f = new Eval(e.variant(), e.daysLive(), e.mine(), e.v2(), "CANDIDATE", e.reason() + "；" + wait);
                        notifier.send("optimize:candidate:" + v.name(), String.format(Locale.ROOT,
                                "🧪 转正候选 %s：%s\n%s\n暂不切换：%s。", v.name(), v.overrides().describe(), e.reason(), wait));
                    }
                }
                default -> { }
            }
            record(day, f, now);
            finalOut.add(f);
        }
        return finalOut;
    }

    Eval judge(VariantRegistry.Variant v, Instant now) {
        double days = Duration.between(v.startedAt(), now).toMinutes() / 1440.0;
        Perf mine = perf(v.book(), v.startedAt(), now);
        Perf base = perf(v2, v.startedAt(), now);
        double v2dd = Math.max(base.maxDd(), 1);
        if (days >= opt.earlyStopDays() && days < opt.minDays() && mine.maxDd() > v2dd * opt.earlyStopDdRatio()) {
            return new Eval(v.name(), days, mine, base, "STOP", String.format(Locale.ROOT,
                    "上线 %.0f 天，同期回撤 %.0f U 已超过 V2 的 %.1f 倍（V2 %.0f U）", days, mine.maxDd(), opt.earlyStopDdRatio(), base.maxDd()));
        }
        if (days < opt.minDays()) {
            return new Eval(v.name(), days, mine, base, "WAIT", String.format(Locale.ROOT, "上线 %.1f 天，满 %d 天再评估", days, opt.minDays()));
        }
        if (mine.total() < base.total() || mine.maxDd() > v2dd * opt.retireDdRatio()) {
            return new Eval(v.name(), days, mine, base, "STOP", String.format(Locale.ROOT,
                    "满 %d 天同期合计 %+.0f U（V2 %+.0f U），回撤 %.0f U（V2 %.0f U）", opt.minDays(), mine.total(), base.total(),
                    mine.maxDd(), base.maxDd()));
        }
        List<String> miss = new ArrayList<>();
        if (!(mine.total() > base.total())) miss.add("合计没有高于 V2");
        if (mine.maxDd() > v2dd * opt.promoteDdRatio()) miss.add("回撤超过 V2 × " + opt.promoteDdRatio());
        if (!(mine.perTrade() > (Double.isNaN(base.perTrade()) ? Double.NEGATIVE_INFINITY : base.perTrade()))) miss.add("每笔期望没有高于 V2");
        if (mine.closed() < opt.minClosed()) miss.add("平仓 " + mine.closed() + " 笔，不到 " + opt.minClosed() + " 笔");
        if (!v.backtestPassed()) miss.add("回测发布前检查没有通过");
        String stats = String.format(Locale.ROOT, "满 %.0f 天同期合计 %+.0f / %+.0f U，回撤 %.0f / %.0f U，每笔 %+.2f / %+.2f U，平仓 %d 笔（变体 / V2）",
                days, mine.total(), base.total(), mine.maxDd(), base.maxDd(), mine.perTrade(), base.perTrade(), mine.closed());
        if (miss.isEmpty()) return new Eval(v.name(), days, mine, base, "CANDIDATE", stats);
        return new Eval(v.name(), days, mine, base, "KEEP", stats + "；还不能转正：" + String.join("、", miss));
    }

    private void record(String day, Eval e, Instant now) {
        jdbc.sql("""
                INSERT INTO variant_eval(day, variant, days_live, total, v2_total, max_dd, v2_max_dd, per_trade, v2_per_trade, closed,
                    decision, reason, created_at)
                VALUES (:d, :v, :days, :t, :vt, :dd, :vdd, :pt, :vpt, :c, :dec, :r, :now)
                """).param("d", day).param("v", e.variant()).param("days", e.daysLive()).param("t", e.mine().total())
                .param("vt", e.v2().total()).param("dd", e.mine().maxDd()).param("vdd", e.v2().maxDd())
                .param("pt", nan(e.mine().perTrade())).param("vpt", nan(e.v2().perTrade())).param("c", e.mine().closed())
                .param("dec", e.decision()).param("r", e.reason()).param("now", now.toString()).update();
    }

    private static Double nan(double v) {
        return Double.isNaN(v) ? null : v;
    }

    /** 日报 / 每日汇总用：最近一次评估里每个变体的结论（一行一个）。 */
    public List<String> latestLines() {
        Optional<String> day = jdbc.sql("SELECT MAX(day) FROM variant_eval").query(String.class).optional().filter(s -> s != null);
        if (day.isEmpty()) return List.of();
        return jdbc.sql("SELECT variant, decision, reason FROM variant_eval WHERE day = :d ORDER BY id").param("d", day.get())
                .query((rs, i) -> rs.getString(1) + " " + label(rs.getString(2)) + "：" + rs.getString(3)).list();
    }

    static String label(String decision) {
        return switch (decision) {
            case "WAIT" -> "观察中";
            case "CANDIDATE" -> "转正候选";
            case "PROMOTED" -> "已转正";
            case "STOP" -> "停开新仓";
            case "KEEP" -> "继续观察";
            default -> decision;
        };
    }
}
