package com.trendpilot.tracking;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.trendpilot.job.JobRuns;

/**
 * 候选退出规则的离线回放（设计文档第 7.3、7.4 节）：在样本自己的 15m K 线副本上逐根模拟，同一批入场逐笔配对比较。
 * <ul>
 * <li>基准 B0【重写定义】：现行利润保护（原始收益 ≥2% 且净利 >0 启动，底线 = max(0.35%, 最高净收益 − 2%)，跌破仍有净利卖出）
 *     + 持有到 72 小时主窗口结束。论点失效和 8% 复核需要 BTC 基准和小时信号，离线回放不模拟。</li>
 * <li>候选 = B0 + 规则；同一根 K 线既触发止损又触发保护时按先止损处理。</li>
 * <li>按 t0 时间前 60% 为校准期、后 40% 为验证期。</li>
 * </ul>
 * 结果只作为建议，不修改任何正式参数。
 */
@Component
public class ExitReplay {

    public static final String JOB = "EXIT_REPLAY";
    public static final String BASELINE = "B0";
    private static final Logger log = LoggerFactory.getLogger(ExitReplay.class);
    static final long Q = 15 * 60_000L;

    private final JdbcClient jdbc;
    private final SignalTracker tracker;
    private final TrackingProperties p;
    private final JobRuns runs;
    private final ZoneId zone;

    public ExitReplay(JdbcClient jdbc, SignalTracker tracker, TrackingProperties p, JobRuns runs,
                      @Value("${trendpilot.zone}") String zone) {
        this.jdbc = jdbc;
        this.tracker = tracker;
        this.p = p;
        this.runs = runs;
        this.zone = ZoneId.of(zone);
    }

    record Ep(long id, String startType, long t0, PathCalculator.Cost cost, double atrPct, String day,
              List<PathCalculator.Bar> bars) {
    }

    /** 一条规则在一个样本上的结果。 */
    public record Trade(String ruleId, long episodeId, String startType, String split, String day, long exitTime,
                        String reason, double exitPrice, double netReturn, int holdingMin, boolean killedBig) {
    }

    @Scheduled(cron = "0 20 0 * * *", zone = "${trendpilot.zone}")
    public void scheduled() {
        Instant started = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        try {
            int n = run(started).size();
            runs.record(JOB, "E-" + started, started, Instant.now(), "SUCCESS", "N/A", n + " 条回放结果");
        } catch (RuntimeException e) {
            log.error("退出规则回放失败", e);
            runs.record(JOB, "E-" + started, started, Instant.now(), "FAILED", "N/A", String.valueOf(e.getMessage()));
        }
    }

    public List<Trade> run(Instant now) {
        String runId = now.atZone(zone).toLocalDate().toString();
        List<Trade> all = new ArrayList<>();
        btcCloses = btcCloses();
        for (String type : List.of("SIGNAL", "V2_ENTRY")) {
            List<Ep> eps = episodes(type);
            int calib = (int) Math.floor(eps.size() * 0.6);
            for (int i = 0; i < eps.size(); i++) {
                Ep e = eps.get(i);
                String split = i < calib ? "CALIB" : "VALID";
                all.add(simulate(e, null, split));
                for (ExitRule r : ExitRule.grid()) all.add(simulate(e, r, split));
            }
        }
        jdbc.sql("DELETE FROM exit_rule_eval WHERE run_id = :r").param("r", runId).update();
        Map<String, String> params = new LinkedHashMap<>();
        params.put(BASELINE, "{}");
        ExitRule.grid().forEach(r -> params.put(r.id(), r.paramsJson()));
        for (Trade t : all) {
            jdbc.sql("""
                    INSERT INTO exit_rule_eval(run_id, rule_id, params_json, episode_id, exit_time, exit_reason, exit_price,
                        net_return, holding_min, killed_big, data_split)
                    VALUES (:run, :rule, :pj, :ep, :t, :reason, :price, :ret, :hold, :kb, :split)
                    """).param("run", runId).param("rule", t.ruleId()).param("pj", params.get(t.ruleId()))
                    .param("ep", t.episodeId()).param("t", t.exitTime()).param("reason", t.reason())
                    .param("price", t.exitPrice()).param("ret", t.netReturn()).param("hold", t.holdingMin())
                    .param("kb", t.killedBig() ? 1 : 0).param("split", t.split()).update();
        }
        return all;
    }

    /** BTC 15m 收盘价（开盘时间 → 收盘价），来自本地 K 线缓存，不发请求。 */
    private java.util.Map<Long, Double> btcCloses = java.util.Map.of();

    private java.util.Map<Long, Double> btcCloses() {
        java.util.Map<Long, Double> m = new java.util.HashMap<>();
        jdbc.sql("SELECT open_time, close FROM kline_15m WHERE venue = 'BINANCE' AND symbol = 'BTCUSDT'")
                .query((rs, i) -> m.put(rs.getLong(1), rs.getDouble(2))).list();
        return m;
    }

    /** 截至这根 K 线收盘，BTC 最近 4 根 15m 的涨跌（%）；缺数据为 NaN。 */
    double btcRet1h(long closeTime) {
        long open = Math.floorDiv(closeTime - 1, Q) * Q;
        Double now = btcCloses.get(open), before = btcCloses.get(open - 4 * Q);
        return now == null || before == null || before <= 0 ? Double.NaN : (now / before - 1) * 100;
    }

    /** 主窗口已经看完的样本，按 t0 排序。 */
    List<Ep> episodes(String type) {
        return jdbc.sql("""
                SELECT e.id, e.start_type, e.t0, e.p0, e.fee_rate, e.spread_bps, e.impact_bps, e.atr_1h_pct
                FROM signal_episode e JOIN signal_outcome o ON o.episode_id = e.id
                WHERE e.start_type = :t AND o.observed_minutes >= :min AND e.status <> 'DATA_GAP' ORDER BY e.t0
                """).param("t", type).param("min", p.mainWindowHours() * 60)
                .query((rs, i) -> {
                    Instant t0 = Instant.parse(rs.getString(3));
                    return new Ep(rs.getLong(1), rs.getString(2), t0.toEpochMilli(),
                            new PathCalculator.Cost(rs.getDouble(4), rs.getDouble(5), rs.getDouble(6) / 10_000,
                                    rs.getDouble(7) / 10_000),
                            rs.getObject(8) == null ? Double.NaN : rs.getDouble(8),
                            t0.atZone(zone).toLocalDate().toString(), null);
                })
                .list().stream()
                .map(e -> new Ep(e.id(), e.startType(), e.t0(), e.cost(), e.atrPct(), e.day(), tracker.bars(e.id())))
                .toList();
    }

    /** 逐根 K 线模拟一条规则（rule 为 null 时是基准 B0）。 */
    Trade simulate(Ep e, ExitRule rule, String split) {
        PathCalculator.Cost c = e.cost();
        long mainEnd = e.t0() + p.mainWindowHours() * 3_600_000L;
        long extEnd = e.t0() + p.extWindowHours() * 3_600_000L;
        boolean armed = false;
        double peakNet = Double.NEGATIVE_INFINITY, floor = 0, maxClose = 0;
        double stop = rule == null ? Double.NaN : rule.stopPrice(c, e.atrPct());
        PathCalculator.Bar last = null;
        String reason = null;
        double exitPrice = 0;
        for (PathCalculator.Bar b : e.bars()) {
            if (b.closeTime() <= e.t0() || b.closeTime() > mainEnd) continue;
            last = b;
            boolean partial = b.closeTime() - Q < e.t0();
            double rC = c.net(b.close());
            peakNet = Math.max(peakNet, rC);
            maxClose = Math.max(maxClose, b.close());
            double hold = (b.closeTime() - e.t0()) / 3_600_000.0;
            // 1. 候选规则（先止损）
            if (rule != null) {
                if (!Double.isNaN(stop) && !partial && b.low() <= stop) {
                    reason = rule.id();
                    exitPrice = Math.min(stop, b.open()); // 跳空按开盘价成交，不假设能在止损价成交
                    break;
                }
                ExitRule.State s = new ExitRule.State(rC, c.raw(b.close()), hold, peakNet,
                        (1 - b.close() / maxClose) * 100, e.atrPct(), btcRet1h(b.closeTime()));
                if (Double.isNaN(stop) && rule.check(s) != null) {
                    reason = rule.id();
                    exitPrice = b.close();
                    break;
                }
            }
            // 2. 现行利润保护（B0）
            if (!armed && c.raw(b.close()) >= 2 && rC > 0) armed = true;
            if (armed) {
                // 与现行规则一致：max(本金 0.35%, 最高净利 − 本金 1%, 最高净利 × 60%)
                floor = Math.max(floor, Math.max(0.35, Math.max(peakNet - 1, peakNet * 0.6)));
                if (rC > 0 && rC < floor) {
                    reason = "PROFIT_PROTECTION";
                    exitPrice = b.close();
                    break;
                }
            }
        }
        if (last == null) {
            return new Trade(rule == null ? BASELINE : rule.id(), e.id(), e.startType(), split, e.day(), e.t0(),
                    "NO_DATA", c.p0(), 0, 0, false);
        }
        if (reason == null) {
            reason = "WINDOW_END";
            exitPrice = last.close();
        }
        long exitTime = last.closeTime(); // 触发的那根（循环在触发处退出）或窗口最后一根
        double net = c.net(exitPrice);
        final long exitAt = exitTime;
        boolean killed = !reason.equals("WINDOW_END") && e.bars().stream()
                .filter(b -> b.closeTime() > exitAt && b.closeTime() <= extEnd)
                .anyMatch(b -> c.net(b.high()) >= 20);
        return new Trade(rule == null ? BASELINE : rule.id(), e.id(), e.startType(), split, e.day(), exitTime, reason,
                exitPrice, net, (int) ((exitTime - e.t0()) / 60_000), killed);
    }

    /** 一条规则在一组样本上的比较指标（7.4 节）。 */
    public record RuleStats(String ruleId, String startType, String split, int n, double mean, double median,
                            double trimmedMean, double winRate, double profitFactor, double p5, double cvar5,
                            double avgHoldHours, double killedBigShare, double portfolioMdd, double pairedDiffMean,
                            double[] pairedDiffCi90) {
    }

    public List<RuleStats> stats(List<Trade> trades) {
        Map<String, Trade> base = new LinkedHashMap<>();
        trades.stream().filter(t -> t.ruleId().equals(BASELINE)).forEach(t -> base.put(t.startType() + t.episodeId(), t));
        Map<String, List<Trade>> groups = new LinkedHashMap<>();
        trades.forEach(t -> groups.computeIfAbsent(t.ruleId() + "|" + t.startType() + "|" + t.split(),
                k -> new ArrayList<>()).add(t));
        List<RuleStats> out = new ArrayList<>();
        groups.forEach((k, list) -> {
            String[] parts = k.split("\\|");
            List<Double> r = list.stream().map(Trade::netReturn).toList();
            List<Double> sorted = r.stream().sorted().toList();
            int tail = Math.max(1, (int) Math.ceil(sorted.size() * 0.05));
            double gains = r.stream().filter(x -> x > 0).mapToDouble(Double::doubleValue).sum();
            double losses = -r.stream().filter(x -> x < 0).mapToDouble(Double::doubleValue).sum();
            // 组合回撤：每笔 1,000U，按退出时间累计
            double eq = 0, peak = 0, mdd = 0;
            for (Trade t : list.stream().sorted(Comparator.comparingLong(Trade::exitTime)).toList()) {
                eq += t.netReturn() * 10;
                peak = Math.max(peak, eq);
                mdd = Math.max(mdd, peak - eq);
            }
            List<double[]> diffs = new ArrayList<>();
            List<String> days = new ArrayList<>();
            for (Trade t : list) {
                Trade b = base.get(t.startType() + t.episodeId());
                if (b == null) continue;
                diffs.add(new double[]{t.netReturn() - b.netReturn()});
                days.add(t.day());
            }
            record D(String day, double v) {
            }
            List<D> ds = new ArrayList<>();
            for (int i = 0; i < diffs.size(); i++) ds.add(new D(days.get(i), diffs.get(i)[0]));
            double diffMean = ds.stream().mapToDouble(D::v).average().orElse(Double.NaN);
            double[] ci = parts[0].equals(BASELINE) ? new double[]{0, 0}
                    : Stats.dayBlockBootstrap(ds, D::day, l -> l.stream().mapToDouble(D::v).average().orElse(Double.NaN),
                    2000, 7);
            out.add(new RuleStats(parts[0], parts[1], parts[2], list.size(),
                    r.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN), Stats.quantile(r, 0.5),
                    Stats.trimmedMean(r), r.stream().filter(x -> x > 0).count() / (double) r.size(),
                    losses == 0 ? Double.POSITIVE_INFINITY : gains / losses, Stats.quantile(r, 0.05),
                    sorted.subList(0, tail).stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN),
                    list.stream().mapToInt(Trade::holdingMin).average().orElse(0) / 60,
                    list.stream().filter(Trade::killedBig).count() / (double) list.size(), mdd, diffMean, ci));
        });
        return out;
    }

    /** 最近一次回放结果。 */
    public List<Trade> latest() {
        String run = jdbc.sql("SELECT MAX(run_id) FROM exit_rule_eval").query(String.class).optional().orElse(null);
        if (run == null) return List.of();
        return jdbc.sql("""
                SELECT r.rule_id, r.episode_id, e.start_type, r.data_split, e.t0, r.exit_time, r.exit_reason, r.exit_price,
                    r.net_return, r.holding_min, r.killed_big
                FROM exit_rule_eval r JOIN signal_episode e ON e.id = r.episode_id WHERE r.run_id = :r
                """).param("r", run)
                .query((rs, i) -> new Trade(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        Instant.parse(rs.getString(5)).atZone(zone).toLocalDate().toString(), rs.getLong(6),
                        rs.getString(7), rs.getDouble(8), rs.getDouble(9), rs.getInt(10), rs.getInt(11) == 1))
                .list();
    }

    /** 报告文本：验证期内相对 B0 配对差值最好的几条，附采纳门槛提示。 */
    public List<String> lines(List<RuleStats> stats, int top) {
        List<String> lines = new ArrayList<>();
        for (String type : List.of("SIGNAL", "V2_ENTRY")) {
            List<RuleStats> valid = stats.stream().filter(s -> s.startType().equals(type) && s.split().equals("VALID"))
                    .toList();
            RuleStats b0 = valid.stream().filter(s -> s.ruleId().equals(BASELINE)).findFirst().orElse(null);
            String title = type.equals("SIGNAL") ? "全部 EARLY" : "V2 实际买入";
            int need = type.equals("SIGNAL") ? 100 : 60;
            if (b0 == null || b0.n() < need) {
                lines.add(String.format(Locale.ROOT, "🧮 退出规则回放（%s）：验证期 %d 个样本，未达采纳门槛 %d 个，暂不下结论",
                        title, b0 == null ? 0 : b0.n(), need));
                continue;
            }
            lines.add(String.format(Locale.ROOT, "🧮 退出规则回放（%s，验证期 %d 个）：B0 每笔均值 %.2f%%，中位 %.2f%%，CVaR5 %.2f%%",
                    title, b0.n(), b0.mean(), b0.median(), b0.cvar5()));
            valid.stream().filter(s -> !s.ruleId().equals(BASELINE))
                    .sorted(Comparator.comparingDouble((RuleStats s) -> -s.pairedDiffCi90()[0])).limit(top)
                    .forEach(s -> lines.add(String.format(Locale.ROOT,
                            "· %s：比 B0 每笔 %+.2f%%（90%% 区间 [%.2f, %.2f]），CVaR5 %.2f%%，误杀大涨 %.0f%%",
                            s.ruleId(), s.pairedDiffMean(), s.pairedDiffCi90()[0], s.pairedDiffCi90()[1], s.cvar5(),
                            s.killedBigShare() * 100)));
        }
        lines.add("注：回放只作参考，正式 V1/V2 规则不变；采纳需满足设计文档 8.1 全部条件并由你确认。");
        return lines;
    }
}
