package com.trendpilot.lpshadow;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LP 影子盘报告：
 * <ul>
 * <li>每日（越南时间 00:00 后第一分钟，处理新一天的快照之前）：写 lp_daily、data/reports/lp-daily-日期.md，
 *     Telegram 一行（前 3、后 3、持有 50/50 基准），然后清理 90 天前的原始快照；</li>
 * <li>每周一（日报之后）：每个策略的日收益 bootstrap 均值和 95% 置信区间、最大回撤、最长回撤天数，
 *     对照第 8 节的决策标准，写 data/reports/lp-weekly-日期.md 并作为文件发到 Telegram。</li>
 * </ul>
 */
final class LpReports {

    private static final Logger log = LoggerFactory.getLogger(LpReports.class);
    static final long KEEP_SNAPSHOT_DAYS = 90;
    static final int BOOTSTRAP = 10_000;
    static final double[] M_ASSUMED = {5, 10, 20};

    /** 发 Telegram 文本（eventKey, text）。 */
    private final BiConsumer<String, String> send;
    /** 发 Telegram 文件（eventKey, fileName, content, caption）。 */
    private final DocSender sendDoc;
    private final LpStore store;
    private final ZoneId zone;
    private final Path dir;

    interface DocSender {
        void send(String eventKey, String fileName, String content, String caption);
    }

    LpReports(LpStore store, ZoneId zone, Path reportsDir, BiConsumer<String, String> send, DocSender sendDoc) {
        this.store = store;
        this.zone = zone;
        this.dir = reportsDir;
        this.send = send;
        this.sendDoc = sendDoc;
    }

    /** 每分钟调用：昨天（越南时间）还没有日报就生成；周一再生成周报。 */
    void maybeRun(Instant now, Map<String, LpBook> books) {
        LocalDate yesterday = now.atZone(zone).toLocalDate().minusDays(1);
        long firstStart = books.values().stream().flatMap(b -> b.states.values().stream()).filter(st -> st.started)
                .mapToLong(st -> st.startTs).min().orElse(Long.MAX_VALUE);
        long yesterdayEnd = yesterday.plusDays(1).atStartOfDay(zone).toEpochSecond();
        if (firstStart >= yesterdayEnd) return;   // 昨天还没开始运行
        if (!store.hasDaily(yesterday.toString())) {
            daily(yesterday, books, now);
            store.pruneSnapshots(now.getEpochSecond() - KEEP_SNAPSHOT_DAYS * 86_400);
        }
        if (now.atZone(zone).getDayOfWeek() == DayOfWeek.MONDAY && !store.hasWeekly(yesterday.toString())) {
            weekly(yesterday, now);
        }
    }

    // ---------------- 每日 ----------------

    record Row(Strategy s, PositionState st, double coverage) {
        double ret() {
            return st.lastValue / Strategy.CAPITAL - 1;
        }
    }

    void daily(LocalDate day, Map<String, LpBook> books, Instant now) {
        long from = day.atStartOfDay(zone).toEpochSecond();
        long to = day.plusDays(1).atStartOfDay(zone).toEpochSecond();
        Map<String, long[]> ok = store.okCounts(from, to);
        Map<String, double[]> m = store.mAvg(from, to);
        Map<String, Long> events = store.eventCounts(from, to);
        List<Row> rows = new ArrayList<>();
        for (LpBook b : books.values()) {
            for (Strategy s : b.strategies) {
                PositionState st = b.states.get(s.name());
                if (!st.started || st.startTs >= to) continue;
                long expected = Math.max(1, (to - Math.max(from, st.startTs)) / 60);
                long[] c = ok.getOrDefault(s.pool(), new long[2]);
                double cov = Math.min(1.0, (s.hedged() ? c[1] : c[0]) / (double) expected);
                rows.add(new Row(s, st, cov));
            }
        }
        if (rows.isEmpty()) return;
        for (Row r : rows) {
            store.saveDaily(day.toString(), r.s().name(), r.st().lastValue, r.st().fees, r.st().lastIl, r.st().gasCost,
                    r.st().swapCost, r.st().lastHedgePnl, r.st().funding, r.coverage());
        }
        String md = dailyMarkdown(day, rows, ok, m, events, from, to);
        write("lp-daily-" + day + ".md", md);
        send.accept("lpshadow:daily:" + day, telegramLine(day, rows));
        log.info("LP 影子盘：{} 日报已生成（{} 个策略）", day, rows.size());
    }

    static double annualized(PositionState st) {
        double days = (st.lastTs - st.startTs) / 86_400.0;
        if (days < 1) return Double.NaN;
        return Math.pow(st.lastValue / Strategy.CAPITAL, 365.0 / days) - 1;
    }

    String dailyMarkdown(LocalDate day, List<Row> rows, Map<String, long[]> ok, Map<String, double[]> m, Map<String, Long> events,
                         long from, long to) {
        StringBuilder b = new StringBuilder();
        b.append("# LP 影子盘日报 ").append(day).append("（越南时间）\n\n");
        PositionState any = rows.getFirst().st();
        b.append("只读影子：每分钟读链上状态模拟做市，不下单、不签名。本金每个策略 10,000 USDC，从 ")
                .append(Instant.ofEpochSecond(any.startTs).atZone(zone).toLocalDate()).append(" 开始。金额单位 USDC，都是累计值。\n\n");
        b.append("## 数据覆盖率（当天）\n\n| 池子 | 池子数据 | 池子 + 永续报价 |\n|---|---:|---:|\n");
        long minutes = (to - from) / 60;
        for (Map.Entry<String, long[]> e : ok.entrySet()) {
            b.append(String.format(Locale.ROOT, "| %s | %.1f%% | %.1f%% |\n", e.getKey(), 100.0 * e.getValue()[0] / minutes,
                    100.0 * e.getValue()[1] / minutes));
        }
        b.append("\n（策略开始当天按开始后的分钟数计算；低于 95% 的天数在第 60 天评估时会被指出。）\n\n");
        Map<String, List<Row>> byPool = new LinkedHashMap<>();
        rows.forEach(r -> byPool.computeIfAbsent(r.s().pool(), k -> new ArrayList<>()).add(r));
        for (Map.Entry<String, List<Row>> e : byPool.entrySet()) {
            b.append("## ").append(e.getKey()).append("\n\n");
            b.append("| 策略 | 价值 | 累计 | 年化 | 手续费 | 无常损失 | gas | 交易成本 | 对冲盈亏 | 资金费 | 调仓 | 风险事件 | 覆盖率 |\n");
            b.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
            List<Row> sorted = new ArrayList<>(e.getValue());
            sorted.sort(Comparator.comparingDouble(Row::ret).reversed());
            for (Row r : sorted) {
                PositionState st = r.st();
                b.append(String.format(Locale.ROOT, "| %s | %,.2f | %+.2f%% | %s | %,.2f | %,.2f | %,.2f | %,.2f | %,.2f | %,.2f | %d | %d | %.1f%% |\n",
                        label(r.s()), st.lastValue, r.ret() * 100, pct(annualized(st)), st.fees, st.lastIl, st.gasCost, st.swapCost,
                        st.lastHedgePnl, st.funding, st.rebalances, st.riskEvents, r.coverage() * 100));
            }
            b.append('\n');
        }
        b.append("说明：交易成本 = 调仓换币（一半仓位 ×（池子费率 + 0.02% 滑点））+ 对冲的 taker 手续费；"
                + "对冲盈亏是空单价格盈亏（不含手续费和资金费）；无常损失是相对\"每段开仓时的币一直拿着\"。\n\n");
        b.append("## 同池集中度 m_avg（当天每小时实测的平均）\n\n| 池子 | m_avg | 平均 TVL | 样本 | 回测假设 |\n|---|---:|---:|---:|---|\n");
        for (Map.Entry<String, double[]> e : m.entrySet()) {
            double v = e.getValue()[0];
            b.append(String.format(Locale.ROOT, "| %s | %.2f | %,.0f | %d | %s |\n", e.getKey(), v, e.getValue()[1],
                    (long) e.getValue()[2], compareM(v)));
        }
        b.append("\nm_avg = L_active × 2√P / TVL（TVL 由池子两个代币的 balanceOf 得出）。m 越大，同池其他 LP 越集中，我们分到的手续费越少。\n\n");
        b.append("## 当天事件\n\n");
        if (events.isEmpty()) b.append("无。\n");
        events.forEach((k, v) -> b.append("- ").append(k).append("：").append(v).append('\n'));
        return b.toString();
    }

    static String compareM(double m) {
        if (Double.isNaN(m)) return "-";
        StringBuilder s = new StringBuilder();
        for (double a : M_ASSUMED) {
            if (s.length() > 0) s.append("，");
            s.append(String.format(Locale.ROOT, "%s %.0f", m >= a ? "≥" : "<", a));
        }
        return s.toString();
    }

    static String label(Strategy s) {
        return switch (s.kind()) {
            case HOLD_5050 -> "基准：持有 50/50";
            case HOLD_USDC -> "基准：只持有 USDC";
            case HOLD_ETH -> "基准：只持有 ETH";
            case LP -> s.name().substring(s.pool().length() + 1);
        };
    }

    static String pct(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%+.1f%%", v * 100);
    }

    /** Telegram 一行：做市策略里累计收益前 3、后 3，以及各池持有 50/50 基准的平均。 */
    static String telegramLine(LocalDate day, List<Row> rows) {
        List<Row> lp = rows.stream().filter(r -> !r.s().benchmark()).sorted(Comparator.comparingDouble(Row::ret).reversed()).toList();
        List<Row> bench = rows.stream().filter(r -> r.s().kind() == Strategy.Kind.HOLD_5050).toList();
        StringBuilder b = new StringBuilder("【LP】").append(day).append(" 前3 ");
        for (int i = 0; i < Math.min(3, lp.size()); i++) b.append(i > 0 ? "，" : "").append(shortRet(lp.get(i)));
        b.append("｜后3 ");
        for (int i = 0; i < Math.min(3, lp.size()); i++) b.append(i > 0 ? "，" : "").append(shortRet(lp.get(lp.size() - 1 - i)));
        double avg = bench.stream().mapToDouble(Row::ret).average().orElse(Double.NaN);
        b.append(String.format(Locale.ROOT, "｜持有50/50 %+.2f%%", avg * 100));
        return b.toString();
    }

    private static String shortRet(Row r) {
        return String.format(Locale.ROOT, "%s %+.2f%%", r.s().name(), r.ret() * 100);
    }

    // ---------------- 每周 ----------------

    record Stats(double mean, double lo, double hi, double maxDd, int longestDdDays, int days) {
    }

    /** 日收益的 bootstrap 均值和 95% 置信区间（固定种子，结果可复现），以及按日末价值算的最大回撤和最长回撤天数。 */
    static Stats stats(double[] values, long seed) {
        int n = values.length;
        double[] r = new double[n];
        double prev = Strategy.CAPITAL;
        for (int i = 0; i < n; i++) {
            r[i] = values[i] / prev - 1;
            prev = values[i];
        }
        double mean = Arrays.stream(r).average().orElse(Double.NaN);
        double lo = Double.NaN, hi = Double.NaN;
        if (n >= 2) {
            Random rnd = new Random(seed);
            double[] means = new double[BOOTSTRAP];
            for (int k = 0; k < BOOTSTRAP; k++) {
                double s = 0;
                for (int i = 0; i < n; i++) s += r[rnd.nextInt(n)];
                means[k] = s / n;
            }
            Arrays.sort(means);
            lo = means[(int) Math.floor(0.025 * (BOOTSTRAP - 1))];
            hi = means[(int) Math.ceil(0.975 * (BOOTSTRAP - 1))];
        }
        double peak = Strategy.CAPITAL, maxDd = 0;
        int run = 0, longest = 0;
        for (double v : values) {
            if (v >= peak) {
                peak = v;
                run = 0;
            } else {
                run++;
                longest = Math.max(longest, run);
                maxDd = Math.max(maxDd, 1 - v / peak);
            }
        }
        return new Stats(mean, lo, hi, maxDd, longest, n);
    }

    void weekly(LocalDate lastDay, Instant now) {
        Map<String, double[]> values = store.dailyValues();
        Map<String, double[]> coverage = store.dailyCoverage();
        if (values.isEmpty()) {
            store.markWeekly(lastDay.toString(), now.getEpochSecond());
            return;
        }
        Map<String, Integer> risk = store.riskEventCounts();
        StringBuilder b = new StringBuilder();
        b.append("# LP 影子盘周报（截至 ").append(lastDay).append("）\n\n");
        b.append("每个策略的日收益（按每天结束时的价值）做 bootstrap（").append(BOOTSTRAP)
                .append(" 次，固定种子），给出均值和 95% 置信区间；回撤按日末价值。年化超额 = 年化收益 − 只持有 USDC（0%）。\n");
        b.append("结论只适用于这里测试过的池子、区间宽度和成本假设。\n\n");
        b.append("| 策略 | 天数 | 日均收益 | 95% 区间 | 年化 | 最大回撤 | 最长回撤（天） | 风险事件 | 平均覆盖率 | 单池标准 |\n");
        b.append("|---|---:|---:|---|---:|---:|---:|---:|---:|---|\n");
        Map<String, Integer> passByShape = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(values.keySet());
        for (String name : names) {
            double[] v = values.get(name);
            Stats s = stats(v, name.hashCode());
            double ann = Math.pow(v[v.length - 1] / Strategy.CAPITAL, 365.0 / v.length) - 1;
            double cov = Arrays.stream(coverage.getOrDefault(name, new double[] {0})).average().orElse(0);
            int risks = risk.getOrDefault(name, 0);
            boolean benchmark = name.endsWith("-hold5050") || name.endsWith("-usdc") || name.endsWith("-eth");
            boolean pass = !benchmark && ann > 0.05 && s.lo() > 0 && s.maxDd() < 0.10 && risks == 0 && cov > 0.95;
            if (!benchmark) {
                String shape = name.substring(name.indexOf("-w"));   // 同一种设置在不同池子
                passByShape.merge(shape, pass ? 1 : 0, Integer::sum);
            }
            b.append(String.format(Locale.ROOT, "| %s | %d | %+.4f%% | %s | %s | %.2f%% | %d | %d | %.1f%% | %s |\n", name, s.days(),
                    s.mean() * 100, Double.isNaN(s.lo()) ? "-" : String.format(Locale.ROOT, "[%+.4f%%, %+.4f%%]", s.lo() * 100, s.hi() * 100),
                    pct(ann), s.maxDd() * 100, s.longestDdDays(), risks, cov * 100, benchmark ? "基准" : pass ? "达标" : "未达标"));
        }
        b.append("\n## 第 8 节决策标准（第 60 天正式评估，这里只是进度）\n\n");
        b.append("单池标准：年化超额 > 5 个百分点、日收益 95% 区间下限 > 0、最大回撤 < 10%、没有爆仓风险事件、覆盖率 > 95%。"
                + "同一种设置要在至少 2 个池子同时达标才算\"可以小资金实盘\"。是否实盘由用户决定，模块本身永远不下单。\n\n");
        List<String> ready = passByShape.entrySet().stream().filter(e -> e.getValue() >= 2).map(Map.Entry::getKey).toList();
        b.append(ready.isEmpty() ? "目前没有设置在 2 个以上池子同时达标。\n" : "目前在 2 个以上池子同时达标的设置：" + String.join("、", ready) + "\n");
        String file = "lp-weekly-" + lastDay + ".md";
        write(file, b.toString());
        store.markWeekly(lastDay.toString(), now.getEpochSecond());
        sendDoc.send("lpshadow:weekly:" + lastDay, file, b.toString(),
                "【LP】周报（截至 " + lastDay + "）：" + (ready.isEmpty() ? "暂无设置在 2 个池子同时达标" : "达标设置 " + String.join("、", ready)));
        log.info("LP 影子盘：周报 {} 已生成", file);
    }

    private void write(String name, String content) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            log.warn("LP 影子盘：报告写入失败 {}：{}", name, e.toString());
        }
    }
}
