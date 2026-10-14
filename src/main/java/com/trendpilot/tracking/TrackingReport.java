package com.trendpilot.tracking;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 信号跟踪统计（设计文档第 5 节）：分位数、Kaplan–Meier 时间指标、条件概率、分组和样本门槛。
 * 不达展示门槛的部分只写"样本不足"，不给结论。
 */
@Component
public class TrackingReport {

    private final JdbcClient jdbc;
    private final TrackingProperties p;
    private final ZoneId zone;
    private final ObjectMapper json = new ObjectMapper();

    public TrackingReport(JdbcClient jdbc, TrackingProperties p, @Value("${trendpilot.zone}") String zone) {
        this.jdbc = jdbc;
        this.p = p;
        this.zone = ZoneId.of(zone);
    }

    /** 一个样本：信号时可知的特征 + 指标。 */
    public record Sample(long id, String venue, String symbol, String startType, Instant t0, String day, String regime,
                         int hitCount, double score, double change24h, Double chasePct, double quoteVolume,
                         String reviewResult, boolean first, String conditions, int observedMinutes,
                         Map<String, Object> m) {

        Double d(String key) {
            Object v = m.get(key);
            return v instanceof Number n ? n.doubleValue() : null;
        }

        Boolean b(String key) {
            Object v = m.get(key);
            return v instanceof Boolean x ? x : null;
        }
    }

    public List<Sample> samples(String startType) {
        return jdbc.sql("""
                SELECT e.id, e.venue, e.symbol, e.start_type, e.t0, e.regime, e.hit_count, e.score, e.change_24h, e.chase_pct,
                    e.quote_volume_24h, e.review_result, e.is_first_episode, e.conditions, o.observed_minutes, o.metrics
                FROM signal_episode e JOIN signal_outcome o ON o.episode_id = e.id WHERE e.start_type = :t ORDER BY e.t0
                """).param("t", startType)
                .query((rs, i) -> {
                    Instant t0 = Instant.parse(rs.getString(5));
                    Map<String, Object> metrics;
                    try {
                        metrics = json.readValue(rs.getString(16), new TypeReference<Map<String, Object>>() { });
                    } catch (Exception e) {
                        metrics = Map.of();
                    }
                    return new Sample(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), t0,
                            t0.atZone(zone).toLocalDate().toString(), rs.getString(6), rs.getInt(7), rs.getDouble(8),
                            rs.getDouble(9), rs.getObject(10) == null ? null : rs.getDouble(10), rs.getDouble(11),
                            rs.getString(12), rs.getInt(13) == 1, rs.getString(14), rs.getInt(15), metrics);
                })
                .list();
    }

    /** 样本是否达到门槛。 */
    public record Gate(int samples, int symbols, int days, boolean show, boolean decide) {
        String text() {
            return String.format(Locale.ROOT, "%d 个样本、%d 个标的、%d 天", samples, symbols, days);
        }
    }

    public Gate gate(List<Sample> s) {
        int symbols = (int) s.stream().map(x -> x.venue() + ":" + x.symbol()).distinct().count();
        int days = (int) s.stream().map(Sample::day).distinct().count();
        boolean show = s.size() >= p.showMinSamples() && symbols >= p.showMinSymbols() && days >= p.showMinDays();
        long spanDays = s.isEmpty() ? 0 : Duration.between(s.getFirst().t0(), s.getLast().t0()).toDays();
        long regimes = s.stream().map(Sample::regime).filter(Objects::nonNull).distinct().count();
        boolean decide = s.size() >= p.decideMinSamples() && symbols >= p.decideMinSymbols()
                && spanDays >= p.decideMinWeeks() * 7L && regimes >= 2;
        return new Gate(s.size(), symbols, days, show, decide);
    }

    /** 分布摘要。 */
    public record Dist(int n, double p10, double p25, double median, double p75, double p90, double trimmedMean) {
        String text(String unit) {
            return String.format(Locale.ROOT, "中位 %.1f%s（P25 %.1f / P75 %.1f，P10 %.1f / P90 %.1f）", median, unit,
                    p25, p75, p10, p90);
        }
    }

    static Dist dist(List<Double> v) {
        return new Dist(v.size(), Stats.quantile(v, 0.1), Stats.quantile(v, 0.25), Stats.quantile(v, 0.5),
                Stats.quantile(v, 0.75), Stats.quantile(v, 0.9), Stats.trimmedMean(v));
    }

    /** 时间类指标的 Kaplan–Meier 摘要（小时）。 */
    public record TimeToEvent(String metric, int n, double shareBy24h, double shareBy72h, double p25Hours,
                              double medianHours, double p75Hours) {
        String text(String label) {
            return String.format(Locale.ROOT, "%s：24h 内 %.0f%%，72h 内 %.0f%%，中位 %s", label, shareBy24h * 100,
                    shareBy72h * 100, Double.isNaN(medianHours) ? "未过半" : String.format(Locale.ROOT, "%.1f 小时", medianHours));
        }
    }

    TimeToEvent km(List<Sample> s, String metric) {
        List<Stats.Survival> data = s.stream().filter(x -> x.observedMinutes() > 0).map(x -> {
            Double t = x.d(metric);
            return t == null ? new Stats.Survival(x.observedMinutes(), false) : new Stats.Survival(t, true);
        }).toList();
        return new TimeToEvent(metric, data.size(), Stats.kmShareBy(data, 24 * 60), Stats.kmShareBy(data, 72 * 60),
                Stats.kmQuantile(data, 0.25) / 60, Stats.kmQuantile(data, 0.5) / 60, Stats.kmQuantile(data, 0.75) / 60);
    }

    /** 条件概率的一行：条件、样本数、比例。 */
    public record Conditional(String condition, int n, double share, Double medianOutcome) {
    }

    public record Group(String dimension, String value, Gate gate, Double medianRet72h, double big10By72h,
                        Double medianMaeBeforePeak) {
    }

    public record Section(String startType, Gate gate, Map<String, TimeToEvent> times, Map<String, Dist> dists,
                          double[] medianRet72hCi90, List<Conditional> afterPullback, List<Conditional> afterNoProfit,
                          List<Conditional> afterLoss, List<Group> groups) {
    }

    public record Summary(Instant at, Map<String, Integer> status, int newToday, Section signal, Section v2Entry) {
    }

    public Summary summary(Instant now) {
        Map<String, Integer> status = new LinkedHashMap<>();
        jdbc.sql("SELECT start_type || ':' || status, COUNT(*) FROM signal_episode GROUP BY 1")
                .query((rs, i) -> Map.entry(rs.getString(1), rs.getInt(2))).list()
                .forEach(e -> status.put(e.getKey(), e.getValue()));
        String today = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toString();
        int newToday = jdbc.sql("SELECT COUNT(*) FROM signal_episode WHERE t0 >= :t").param("t", today)
                .query(Integer.class).single();
        return new Summary(now, status, newToday, section("SIGNAL"), section("V2_ENTRY"));
    }

    Section section(String type) {
        List<Sample> all = samples(type);
        Gate gate = gate(all);
        if (!gate.show()) {
            return new Section(type, gate, Map.of(), Map.of(), null, List.of(), List.of(), List.of(), List.of());
        }
        Map<String, TimeToEvent> times = new LinkedHashMap<>();
        for (String k : List.of("t_profit", "t_arm", "t_big_5", "t_big_10", "t_big_20", "t_big_30",
                "swing_" + (int) p.swingMainPct() + "_confirm_time", "t_max_72h")) {
            times.put(k, km(all, k));
        }
        // 窗口结束的样本才有完整的路径分布
        List<Sample> done72 = all.stream().filter(x -> x.observedMinutes() >= p.mainWindowHours() * 60).toList();
        Map<String, Dist> dists = new LinkedHashMap<>();
        for (String k : List.of("mfe_72h", "mae_72h", "mae_before_peak", "mae_before_profit", "mdd_from_peak_72h",
                "ret_1h", "ret_4h", "ret_12h", "ret_24h", "ret_48h", "ret_72h", "ret_7d",
                "swing_" + (int) p.swingMainPct() + "_gain", "swing_" + (int) p.swingMainPct() + "_depth")) {
            List<Double> v = (k.startsWith("ret_") ? all : done72).stream().map(x -> x.d(k)).filter(Objects::nonNull).toList();
            if (!v.isEmpty()) dists.put(k, dist(v));
        }
        double[] ci = Stats.dayBlockBootstrap(done72, Sample::day,
                list -> Stats.quantile(list.stream().map(x -> x.d("ret_72h")).filter(Objects::nonNull).toList(), 0.5),
                2000, 42);

        // 回撤 D 后还能创新高（窗口内）：用第一个深度达到 D 的回撤事件
        Map<Long, List<double[]>> pullbacks = new LinkedHashMap<>();
        jdbc.sql("""
                SELECT b.episode_id, b.depth_pct, b.recovered, b.peak_time, b.recover_time FROM signal_pullback b
                JOIN signal_episode e ON e.id = b.episode_id WHERE e.start_type = :t ORDER BY b.episode_id, b.seq
                """).param("t", type)
                .query((rs, i) -> new double[]{rs.getLong(1), rs.getDouble(2), rs.getInt(3), rs.getLong(4),
                        rs.getObject(5) == null ? -1 : rs.getLong(5)})
                .list().forEach(r -> pullbacks.computeIfAbsent((long) r[0], k -> new ArrayList<>()).add(r));
        List<Conditional> afterPullback = new ArrayList<>();
        for (double d : new double[]{5, 10, 15, 20, 25}) {
            int n = 0, rec = 0;
            List<Double> outcome = new ArrayList<>();
            for (Sample x : done72) {
                var hit = pullbacks.getOrDefault(x.id(), List.of()).stream().filter(r -> r[1] >= d).findFirst();
                if (hit.isEmpty()) continue;
                n++;
                if (hit.get()[2] == 1) rec++;
                if (x.d("ret_72h") != null) outcome.add(x.d("ret_72h"));
            }
            afterPullback.add(new Conditional("从高点回撤 " + (int) d + "%", n, n == 0 ? Double.NaN : rec / (double) n,
                    outcome.isEmpty() ? null : Stats.quantile(outcome, 0.5)));
        }
        List<Conditional> afterNoProfit = new ArrayList<>();
        for (int h : new int[]{4, 8, 12, 24, 48}) {
            List<Sample> stuck = done72.stream().filter(x -> x.d("t_profit") == null || x.d("t_profit") > h * 60).toList();
            long later = stuck.stream().filter(x -> x.d("t_profit") != null).count();
            afterNoProfit.add(new Conditional(h + " 小时仍未盈利", stuck.size(),
                    stuck.isEmpty() ? Double.NaN : later / (double) stuck.size(), null));
        }
        List<Conditional> afterLoss = new ArrayList<>();
        for (int l : new int[]{5, 8, 10, 15, 20, 25}) {
            List<Boolean> v = done72.stream().map(x -> x.b("loss_" + l + "_recovered")).filter(Objects::nonNull).toList();
            long ok = v.stream().filter(Boolean::booleanValue).count();
            afterLoss.add(new Conditional("浮亏到 −" + l + "%", v.size(), v.isEmpty() ? Double.NaN : ok / (double) v.size(), null));
        }
        return new Section(type, gate, times, dists, ci, afterPullback, afterNoProfit, afterLoss, groups(all));
    }

    /** 只在信号时可知的变量上分组；不达展示门槛的分组不展示。 */
    List<Group> groups(List<Sample> all) {
        Map<String, Function<Sample, String>> dims = new LinkedHashMap<>();
        dims.put("市场状态", Sample::regime);
        dims.put("命中个数", x -> x.hitCount() >= 4 ? "4+" : String.valueOf(x.hitCount()));
        dims.put("评分", x -> x.score() < 6 ? "<6" : x.score() < 8 ? "6-8" : "≥8");
        dims.put("24h 涨幅", x -> x.change24h() < 8 ? "<8%" : x.change24h() < 20 ? "8-20%" : "≥20%");
        dims.put("追涨", x -> x.chasePct() == null ? null : x.chasePct() <= 12 ? "≤12%" : ">12%");
        List<Double> vols = all.stream().map(Sample::quoteVolume).sorted().toList();
        double t1 = Stats.quantile(vols, 1 / 3.0), t2 = Stats.quantile(vols, 2 / 3.0);
        dims.put("成交额", x -> x.quoteVolume() <= t1 ? "低" : x.quoteVolume() <= t2 ? "中" : "高");
        dims.put("审核结果", Sample::reviewResult);
        dims.put("交易所", Sample::venue);
        dims.put("首次/重复", x -> x.first() ? "首次" : "重复");
        for (String c : List.of("REL_1H", "REL_4H", "ACCEL", "VOLUME", "COMPRESSION", "MOMENTUM")) {
            dims.put(c, x -> x.conditions() != null && List.of(x.conditions().split(",")).contains(c) ? "命中" : "未命中");
        }
        List<Group> out = new ArrayList<>();
        dims.forEach((name, f) -> {
            Map<String, List<Sample>> by = all.stream().filter(x -> f.apply(x) != null)
                    .collect(Collectors.groupingBy(f, LinkedHashMap::new, Collectors.toList()));
            by.forEach((value, list) -> {
                Gate g = gate(list);
                if (!g.show()) return;
                List<Double> ret = list.stream().map(x -> x.d("ret_72h")).filter(Objects::nonNull).toList();
                List<Double> mae = list.stream().filter(x -> x.observedMinutes() >= p.mainWindowHours() * 60)
                        .map(x -> x.d("mae_before_peak")).filter(Objects::nonNull).toList();
                out.add(new Group(name, value, g, ret.isEmpty() ? null : Stats.quantile(ret, 0.5),
                        km(list, "t_big_" + (int) p.bigDefaultPct()).shareBy72h(),
                        mae.isEmpty() ? null : Stats.quantile(mae, 0.5)));
            });
        });
        out.sort(Comparator.comparing(Group::dimension));
        return out;
    }

    /** Telegram 文本（每周报告与日报都用）。 */
    public List<String> lines(Summary s, boolean full) {
        List<String> lines = new ArrayList<>();
        int tracking = s.status().entrySet().stream().filter(e -> e.getKey().endsWith(":TRACKING"))
                .mapToInt(Map.Entry::getValue).sum();
        int done = s.status().entrySet().stream().filter(e -> !e.getKey().endsWith(":TRACKING"))
                .mapToInt(Map.Entry::getValue).sum();
        lines.add(String.format(Locale.ROOT, "信号跟踪：今日新样本 %d，跟踪中 %d，已结束 %d", s.newToday(), tracking, done));
        if (!full) return lines;
        for (Section sec : List.of(s.signal(), s.v2Entry())) {
            String title = sec.startType().equals("SIGNAL") ? "全部 EARLY 信号" : "V2 实际买入";
            if (!sec.gate().show()) {
                lines.add(title + "：样本不足（" + sec.gate().text() + "，展示门槛 " + p.showMinSamples() + " 个 / "
                        + p.showMinSymbols() + " 个标的 / " + p.showMinDays() + " 天）");
                continue;
            }
            lines.add("");
            lines.add("📐 " + title + "（" + sec.gate().text() + (sec.gate().decide() ? "，已达决策门槛）" : "，未达决策门槛，只供参考）"));
            var t = sec.times();
            lines.add(t.get("t_profit").text("扣费后转盈利"));
            lines.add(t.get("t_big_10").text("涨到 +10%"));
            lines.add(t.get("t_big_20").text("涨到 +20%"));
            lines.add(t.get("swing_" + (int) p.swingMainPct() + "_confirm_time").text("确认第一个波段高点（回撤 " + (int) p.swingMainPct() + "%）"));
            Dist peak = sec.dists().get("swing_" + (int) p.swingMainPct() + "_gain");
            if (peak != null) lines.add("波段高点涨幅：" + peak.text("%"));
            Dist maePeak = sec.dists().get("mae_before_peak");
            if (maePeak != null) lines.add("到 72h 最高点前的最大浮亏：" + maePeak.text("%"));
            Dist r72 = sec.dists().get("ret_72h");
            if (r72 != null && sec.medianRet72hCi90() != null) {
                lines.add(String.format(Locale.ROOT, "72h 收益：%s，中位数 90%% 区间 [%.1f, %.1f]", r72.text("%"),
                        sec.medianRet72hCi90()[0], sec.medianRet72hCi90()[1]));
            }
            for (Conditional c : sec.afterPullback()) {
                if (c.n() > 0) lines.add(String.format(Locale.ROOT, "%s 后又创新高：%.0f%%（%d 个）", c.condition(), c.share() * 100, c.n()));
            }
        }
        return lines;
    }
}
