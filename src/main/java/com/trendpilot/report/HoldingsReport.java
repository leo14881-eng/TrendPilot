package com.trendpilot.report;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.trendpilot.btcdaily.BtcDailyService;
import com.trendpilot.config.StrategyParams;
import com.trendpilot.job.AppState;
import com.trendpilot.portfolio.Ledger;
import com.trendpilot.portfolio.ObservationBook;
import com.trendpilot.portfolio.Position;
import com.trendpilot.portfolio.ShadowBook;

/**
 * V2 日报（Markdown）：概览、持仓明细、当天盈利明细（平仓和买入）、资金与风控状态；附 BTC 每日短线和 V1 汇总。
 * 浮动盈亏用 Monitor 最近一次按全数量盘口模拟卖出的可退出净利（已扣手续费和滑点）；现价取最新一根 15m K 线收盘价。
 * 每晚 00:00（越南时间）生成刚结束那一天的日报：写入 data/reports/v2-daily-yyyy-MM-dd.md，并发 Telegram（过长自动分段）。
 * 随时 GET /api/holdings 取当天到现在的版本，GET /api/v2-daily?day=yyyy-MM-dd 取某天已保存的日报。
 */
@Component
public class HoldingsReport {

    private static final Logger log = LoggerFactory.getLogger(HoldingsReport.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final ShadowBook v2;
    private final ObservationBook v1;
    private final BtcDailyService btc;
    private final StrategyParams params;
    private final AppState state;
    private final JdbcClient jdbc;
    private final ZoneId zone;
    private final Path dir;
    private final com.trendpilot.notify.Notifier notifier;

    public HoldingsReport(ShadowBook v2, ObservationBook v1, BtcDailyService btc, StrategyParams params, AppState state,
                          JdbcClient jdbc, @Value("${trendpilot.zone}") String zone,
                          @Value("${trendpilot.data-dir:./data}") String dataDir,
                          com.trendpilot.notify.Notifier notifier) {
        this.notifier = notifier;
        this.v2 = v2;
        this.v1 = v1;
        this.btc = btc;
        this.params = params;
        this.state = state;
        this.jdbc = jdbc;
        this.zone = ZoneId.of(zone);
        this.dir = Path.of(dataDir, "reports");
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "${trendpilot.zone}")
    public void scheduled() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        java.time.LocalDate day = now.minusSeconds(60).atZone(zone).toLocalDate(); // 刚结束的那一天
        try {
            String md = markdown(day, now);
            write(day, md);
            notifier.send("v2-daily:" + day, telegram(md));
        } catch (Exception e) {
            log.warn("V2 日报生成失败：{}", e.getMessage());
        }
    }

    public Path write(java.time.LocalDate day, String md) throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve("v2-daily-" + day + ".md");
        Files.writeString(file, md, StandardCharsets.UTF_8);
        return file;
    }

    /** 已保存的某天日报；没有时返回空。 */
    public Optional<String> saved(java.time.LocalDate day) {
        Path file = dir.resolve("v2-daily-" + day + ".md");
        try {
            return Files.exists(file) ? Optional.of(Files.readString(file, StandardCharsets.UTF_8)) : Optional.empty();
        } catch (java.io.IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Telegram 版：Markdown 表格在 Telegram 里不好看，换成每行一条；分段由 Notifier 负责。
     * 表格行 "| a | b | c |" 变成 "a · b · c"，分隔行去掉。
     */
    public static String telegram(String md) {
        List<String> out = new ArrayList<>();
        for (String line : md.split("\n")) {
            if (line.startsWith("|---") || line.startsWith("| |---") || line.matches("^\\|[-:| ]+\\|$")) continue;
            if (line.startsWith("|")) {
                String row = String.join(" · ", java.util.Arrays.stream(line.substring(1, line.length() - 1).split("\\|"))
                        .map(String::trim).filter(c -> !c.isEmpty()).toList());
                out.add(row);
            } else if (line.startsWith("## ")) {
                out.add("【" + line.substring(3).trim() + "】");
            } else if (line.startsWith("# ")) {
                out.add("📊 " + line.substring(2).trim());
            } else {
                out.add(line);
            }
        }
        return String.join("\n", out).replaceAll("\n{3,}", "\n\n").trim();
    }

    /** 一行持仓的展示数据。 */
    record Row(Position p, double price, double net, double cost) {
        double pct() {
            return cost == 0 ? 0 : net / cost * 100;
        }
    }

    /** 当天（越南时间）从 0 点到现在的版本。 */
    public String markdown(Instant now) {
        return markdown(now.atZone(zone).toLocalDate(), now);
    }

    /** day 那一天的日报：持仓和资金是 now 时刻的状态，盈利明细统计 day 当天（越南时间 0 点到 24 点，不晚于 now）。 */
    public String markdown(java.time.LocalDate day, Instant now) {
        ZonedDateTime local = now.atZone(zone);
        Instant dayStart = day.atStartOfDay(zone).toInstant();
        Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
        if (dayEnd.isAfter(now)) dayEnd = now.plusSeconds(1);
        List<String> out = new ArrayList<>();
        out.add("# TrendPilot V2 日报 " + day);
        out.add("");
        out.add("生成于 " + TIME.format(local) + " 越南时间。全部为影子模拟记录，没有真实下单。浮动盈亏是按全数量盘口模拟卖出、"
                + "扣掉手续费和滑点后的可退出净利；现价是最新一根 15 分钟 K 线收盘价。");
        out.add("");

        List<Row> rows = rows(v2);
        double cost = rows.stream().mapToDouble(Row::cost).sum();
        double net = rows.stream().mapToDouble(Row::net).sum();
        long losers = rows.stream().filter(r -> r.net() < 0).count();
        double realized = v2.realizedPnl();
        List<Sell> sells = sells(ShadowBook.BOOK, dayStart, dayEnd);
        double today = sells.stream().mapToDouble(Sell::pnl).sum();
        long wins = sells.stream().filter(x -> x.pnl() > 0).count();

        // ---------- 一、概览 ----------
        out.add("## 一、概览");
        out.add("");
        out.add(String.format(Locale.ROOT, "- **合计（已实现 + 浮动）%s U**%s", signed(realized + net),
                dayChange(ShadowBook.BOOK, dayStart, realized + net)));
        out.add(String.format(Locale.ROOT, "- 当天已实现 %s U（平仓 %d 笔，盈利 %d 笔）；累计已实现 %s U", signed(today), sells.size(),
                wins, signed(realized)));
        out.add(String.format(Locale.ROOT, "- 持仓 %d 个，成本 %s U，浮动 %s U（%s），%d 个浮亏、%d 个浮盈", rows.size(), money(cost),
                signed(net), pct(cost == 0 ? 0 : net / cost * 100), losers, rows.size() - losers));
        out.add("- 说明：现行规则不止损，只有赚钱或利润保护时才卖，亏损仓一直拿着，所以平仓几乎全是盈利，"
                + "单看已实现会偏乐观；判断好坏请看合计。");
        out.add("");

        // ---------- 二、持仓明细 ----------
        out.add("## 二、持仓明细");
        out.add("");
        if (rows.isEmpty()) {
            out.add("当前空仓。");
        } else {
            out.add("| 代码 | 交易所 | 数量 | 成本 | 均价 | 现价 | 浮动盈亏 | 收益率 | 档数 | 持有 | 入场结构 |");
            out.add("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|");
            rows.stream().sorted(Comparator.comparingDouble(Row::pct)).forEach(r -> out.add(String.format(Locale.ROOT,
                    "| %s | %s | %s | %s | %s | %s | %s | %s | %d | %s | %s |",
                    r.p().symbol().replace("USDT", ""), r.p().venue(), num(r.p().qty().doubleValue()), money(r.cost()),
                    num(r.p().avgEntryPrice().doubleValue()), Double.isNaN(r.price()) ? "-" : num(r.price()),
                    signed(r.net()), pct(r.pct()), r.p().tranches(), hours(r.p().openedAt(), now),
                    structure(ShadowBook.BOOK, r.p()) + (r.p().protectArmed() ? "；利润保护中" : ""))));
        }
        out.add("");

        // ---------- 三、盈利明细 ----------
        out.add("## 三、盈利明细（当天）");
        out.add("");
        if (sells.isEmpty()) {
            out.add("当天没有平仓。");
        } else {
            out.add("| 代码 | 交易所 | 卖出时间 | 持有 | 成本 | 净盈亏 | 收益率 | 卖出原因 |");
            out.add("|---|---|---|---:|---:|---:|---:|---|");
            sells.forEach(x -> out.add(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s | %s | %s |",
                    x.symbol().replace("USDT", ""), x.venue(), HHMM.format(x.at().atZone(zone)),
                    x.holdMinutes() == null ? "-" : hours(x.at().minusSeconds(x.holdMinutes() * 60L), x.at()),
                    Double.isNaN(x.cost()) ? "-" : money(x.cost()), signed(x.pnl()),
                    Double.isNaN(x.cost()) || x.cost() == 0 ? "-" : pct(x.pnl() / x.cost() * 100), exitLabel(x.reason()))));
        }
        out.add("");
        List<String> buys = buys(ShadowBook.BOOK, dayStart, dayEnd);
        out.add(buys.isEmpty() ? "当天没有买入。" : "当天买入 " + buys.size() + " 笔：" + String.join("，", buys) + "。");
        out.add("");

        // ---------- 四、资金情况 ----------
        String regime = state.get("regime").orElse("?");
        StrategyParams.Capital c = params.capital();
        boolean strong = "STRONG".equals(regime);
        double pool = strong ? c.normalPool() + c.strategicReserve() : c.normalPool();
        double limit = Math.min(pool, c.hardCap() * c.deployPct(regime));
        double deductions = Math.min(0, realized) + Math.min(0, net);
        double free = limit - cost + deductions;
        out.add("## 四、资金情况");
        out.add("");
        out.add(String.format(Locale.ROOT, "- 行情状态 %s：可用上限 %s U = min(资金池 %s, 硬顶 %s × %.0f%%)", regime, money(limit),
                money(pool), money(c.hardCap()), c.deployPct(regime) * 100));
        out.add(String.format(Locale.ROOT, "- 战略储备 %s U：%s", money(c.strategicReserve()),
                strong ? "STRONG 行情，已计入资金池" : "只在 STRONG 行情启用，现在未动用"));
        out.add(String.format(Locale.ROOT, "- 已占用 %s U（%d 个持仓），还可用约 %s U%s", money(cost), rows.size(), money(Math.max(0, free)),
                deductions < 0 ? String.format(Locale.ROOT, "（已扣累计亏损和浮亏 %s U）", signed(deductions)) : ""));
        out.add("- 风控：" + riskLine());
        out.add("");
        // ---------- BTC 每日短线 ----------
        out.add("## 五、BTC 每日短线（影子）");
        out.add("");
        try {
            Map<String, Object> s = btc.summary(now);
            @SuppressWarnings("unchecked")
            Map<String, Object> pos = (Map<String, Object>) s.get("position");
            boolean holding = Boolean.TRUE.equals(pos.get("holding"));
            out.add(String.format(Locale.ROOT, "策略 %s（%s）。%s已实现累计 %s U，完成 %s 轮，盈利 %s 轮。",
                    s.get("strategy"), s.get("version"),
                    holding ? String.format(Locale.ROOT, "持仓 %s BTC，成本 %s U，均价 %s，浮动 %s U，已买 %s 笔。",
                            pos.get("qty"), pos.get("cost"), pos.get("avgCost"),
                            pos.getOrDefault("unrealized", "-"), pos.get("buys"))
                            : "当前空仓。",
                    s.get("realized"), s.get("rounds"), s.get("wins")));
        } catch (RuntimeException e) {
            out.add("暂时读不到：" + e.getMessage());
        }
        out.add("");

        // ---------- V1 ----------
        List<Row> v1rows = rows(v1);
        double v1cost = v1rows.stream().mapToDouble(Row::cost).sum();
        double v1net = v1rows.stream().mapToDouble(Row::net).sum();
        long v1losers = v1rows.stream().filter(r -> r.net() < 0).count();
        out.add("## 六、V1 宽口径观察（对照用，按参考价记账）");
        out.add("");
        out.add(String.format(Locale.ROOT, "**合计（已实现 + 浮动）%s U**%s。%d 个仓位，总成本 %s U，浮动净盈亏 %s U（%s），%d 个浮亏、%d 个浮盈。已实现累计 %s U，当天 %s U。",
                signed(v1.realizedPnl() + v1net), dayChange(ObservationBook.BOOK, dayStart, v1.realizedPnl() + v1net),
                v1rows.size(), money(v1cost), signed(v1net), pct(v1cost == 0 ? 0 : v1net / v1cost * 100), v1losers,
                v1rows.size() - v1losers, signed(v1.realizedPnl()),
                signed(sells(ObservationBook.BOOK, dayStart, dayEnd).stream().mapToDouble(Sell::pnl).sum())));
        out.add("");
        if (!v1rows.isEmpty()) {
            List<Row> sorted = v1rows.stream().sorted(Comparator.comparingDouble(Row::pct)).toList();
            out.add("| | 代码 | 交易所 | 成本 | 浮动盈亏 | 收益率 | 持有 |");
            out.add("|---|---|---|---:|---:|---:|---:|");
            List<Row> best = sorted.reversed().stream().limit(5).toList();
            List<Row> worst = sorted.stream().filter(r -> !best.contains(r)).limit(5).toList();
            best.forEach(r -> out.add(v1Line("最好", r, now)));
            worst.forEach(r -> out.add(v1Line("最差", r, now)));
            out.add("");
        }
        return String.join("\n", out) + "\n";
    }

    /** "，当天 ±x U"：合计相对当天 0 点（越南时间）前最后一个净值点的变化；没有当天之前的记录时不显示。 */
    private String dayChange(String book, Instant dayStart, double total) {
        Optional<Double> start = jdbc.sql("""
                SELECT realized + unrealized FROM equity_curve WHERE book = :b AND at <= :t ORDER BY at DESC, id DESC LIMIT 1
                """).param("b", book).param("t", dayStart.toString()).query(Double.class).optional();
        return start.map(v -> String.format(Locale.ROOT, "，当天 %s U", signed(total - v))).orElse("");
    }

    private static String v1Line(String tag, Row r, Instant now) {
        return String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s | %s |", tag, r.p().symbol().replace("USDT", ""),
                r.p().venue(), money(r.cost()), signed(r.net()), pct(r.pct()), hours(r.p().openedAt(), now));
    }

    private List<Row> rows(Ledger book) {
        return book.positions().stream().map(p -> new Row(p, lastClose(p), book.markNet(p.symbol()),
                p.costBasis().doubleValue())).toList();
    }

    private double lastClose(Position p) {
        return jdbc.sql("SELECT close FROM kline_15m WHERE venue = :v AND symbol = :s ORDER BY open_time DESC LIMIT 1")
                .param("v", p.venue().name()).param("s", p.symbol()).query(Double.class).optional().orElse(Double.NaN);
    }

    /** 入场结构：开仓那一轮的命中条件和评分。 */
    private String structure(String book, Position p) {
        Optional<String> gen = jdbc.sql("""
                SELECT generation FROM shadow_event WHERE book = :b AND symbol = :s AND type = 'BUY' AND created_at <= :t
                ORDER BY id DESC LIMIT 1
                """).param("b", book).param("s", p.symbol()).param("t", p.openedAt().plusSeconds(1).toString())
                .query(String.class).optional();
        if (gen.isEmpty()) return "-";
        return jdbc.sql("SELECT hits, score FROM signal_log WHERE generation = :g AND symbol = :s AND venue = :v ORDER BY id DESC LIMIT 1")
                .param("g", gen.get()).param("s", p.symbol()).param("v", p.venue().name())
                .query((rs, i) -> label(rs.getString("hits")) + String.format(Locale.ROOT, "（评分 %.1f）", rs.getDouble("score")))
                .optional().orElse("-");
    }

    static String label(String hits) {
        if (hits == null || hits.isBlank()) return "-";
        List<String> out = new ArrayList<>();
        for (String h : hits.split(",")) {
            out.add(switch (h.trim()) {
                case "REL_1H" -> "1h 强于 BTC";
                case "REL_4H" -> "4h 强于 BTC";
                case "ACCEL" -> "加速";
                case "VOLUME" -> "放量";
                case "COMPRESSION" -> "盘整突破";
                case "MOMENTUM" -> "动量";
                default -> h.trim();
            });
        }
        return String.join(" + ", out);
    }

    /** 一笔平仓。 */
    record Sell(String symbol, String venue, Instant at, double pnl, double cost, Integer holdMinutes, String reason) {
    }

    private List<Sell> sells(String book, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT symbol, venue, created_at, pnl_usdt, cost_usdt, hold_minutes, reason_code FROM shadow_event
                WHERE book = :b AND type = 'SELL' AND pnl_usdt IS NOT NULL AND created_at >= :f AND created_at < :t ORDER BY id
                """).param("b", book).param("f", from.toString()).param("t", to.toString())
                .query((rs, i) -> new Sell(rs.getString(1), rs.getString(2), Instant.parse(rs.getString(3)),
                        Double.parseDouble(rs.getString(4)), rs.getString(5) == null ? Double.NaN : Double.parseDouble(rs.getString(5)),
                        (Integer) rs.getObject(6), rs.getString(7))).list();
    }

    private List<String> buys(String book, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT symbol, type, created_at, notional_usdt FROM shadow_event
                WHERE book = :b AND type IN ('BUY', 'ADD') AND created_at >= :f AND created_at < :t ORDER BY id
                """).param("b", book).param("f", from.toString()).param("t", to.toString())
                .query((rs, i) -> String.format(Locale.ROOT, "%s %s%s", HHMM.format(Instant.parse(rs.getString(3)).atZone(zone)),
                        rs.getString(1).replace("USDT", ""), "ADD".equals(rs.getString(2)) ? "（加仓）" : "")).list();
    }

    /** 风控状态：崩盘熔断、恢复期、最近一轮 Discovery 的系统拦截原因。 */
    private String riskLine() {
        List<String> parts = new ArrayList<>();
        state.get("risk:altCrashSince").filter(v -> !v.isBlank())
                .ifPresent(v -> parts.add("山寨崩盘熔断中（" + TIME.format(Instant.parse(v).atZone(zone)) + " 起），停止新买入和加仓"));
        if ("true".equals(state.get("risk:recovering").orElse("false"))) parts.add("风险解除后的恢复期（先观察、再半额）");
        jdbc.sql("""
                SELECT reasons FROM decision_log WHERE decision = 'SYSTEM_BLOCKED'
                AND generation = (SELECT generation FROM decision_log ORDER BY id DESC LIMIT 1) LIMIT 1
                """).query(String.class).optional().filter(r -> !r.isBlank())
                .ifPresent(r -> parts.add("最近一轮扫描被系统拦截：" + r));
        return parts.isEmpty() ? "正常，没有熔断、冻结或恢复期" : String.join("；", parts);
    }

    static String exitLabel(String reason) {
        if (reason == null) return "-";
        return switch (reason) {
            case "PROFIT_PROTECTION" -> "利润保护";
            case "THESIS_DECAY" -> "上涨逻辑失效";
            case "TARGET_REVIEW_EXIT" -> "8% 复核卖出";
            case "HARD_INVALID_NOT_TRADING" -> "停止交易/下架";
            case "HARD_INVALID_IDENTITY" -> "黑名单";
            case "HARD_INVALID_LIQUIDITY" -> "流动性异常";
            case "DECAY_STOP" -> "上涨逻辑失效，反弹高点止损";
            case "TIME_SETTLE" -> "到期结算（V1 观察满 72 小时）";
            default -> reason;
        };
    }

    private static String hours(Instant from, Instant now) {
        double h = (now.getEpochSecond() - from.getEpochSecond()) / 3600.0;
        return h >= 48 ? String.format(Locale.ROOT, "%.1f 天", h / 24) : String.format(Locale.ROOT, "%.1f 小时", h);
    }

    static String money(double v) {
        return String.format(Locale.ROOT, "%,.2f", v);
    }

    static String signed(double v) {
        return String.format(Locale.ROOT, "%+,.2f", v);
    }

    static String pct(double v) {
        return String.format(Locale.ROOT, "%+.2f%%", v);
    }

    /** 价格和数量：保留 6 位有效数字，不用科学计数法。 */
    static String num(double v) {
        if (v == 0) return "0";
        return new java.math.BigDecimal(v).round(new java.math.MathContext(6)).stripTrailingZeros().toPlainString();
    }
}
