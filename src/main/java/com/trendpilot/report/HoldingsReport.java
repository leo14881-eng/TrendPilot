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
 * 每日持仓明细（Markdown）：V2 全部持仓表、当日和累计已实现、资金占用；BTC 每日短线；V1 只给汇总和前后各 5 个。
 * 浮动盈亏用 Monitor 最近一次按全数量盘口模拟卖出的可退出净利（已扣手续费和滑点）；现价取最新一根 15m K 线收盘价。
 * 每天 08:40（越南时间）写入 data/holdings/holdings-yyyy-MM-dd.md，也可随时 GET /api/holdings 取当前版本。
 */
@Component
public class HoldingsReport {

    private static final Logger log = LoggerFactory.getLogger(HoldingsReport.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ShadowBook v2;
    private final ObservationBook v1;
    private final BtcDailyService btc;
    private final StrategyParams params;
    private final AppState state;
    private final JdbcClient jdbc;
    private final ZoneId zone;
    private final Path dir;

    public HoldingsReport(ShadowBook v2, ObservationBook v1, BtcDailyService btc, StrategyParams params, AppState state,
                          JdbcClient jdbc, @Value("${trendpilot.zone}") String zone,
                          @Value("${trendpilot.data-dir:./data}") String dataDir) {
        this.v2 = v2;
        this.v1 = v1;
        this.btc = btc;
        this.params = params;
        this.state = state;
        this.jdbc = jdbc;
        this.zone = ZoneId.of(zone);
        this.dir = Path.of(dataDir, "holdings");
    }

    @Scheduled(cron = "0 40 8 * * *", zone = "${trendpilot.zone}")
    public void scheduled() {
        try {
            write(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        } catch (Exception e) {
            log.warn("持仓明细生成失败：{}", e.getMessage());
        }
    }

    public Path write(Instant now) throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve("holdings-" + now.atZone(zone).toLocalDate() + ".md");
        Files.writeString(file, markdown(now), StandardCharsets.UTF_8);
        return file;
    }

    /** 一行持仓的展示数据。 */
    record Row(Position p, double price, double net, double cost) {
        double pct() {
            return cost == 0 ? 0 : net / cost * 100;
        }
    }

    public String markdown(Instant now) {
        ZonedDateTime local = now.atZone(zone);
        List<String> out = new ArrayList<>();
        out.add("# TrendPilot 持仓明细（影子账本）");
        out.add("");
        out.add("生成于 " + TIME.format(local) + " 越南时间。浮动盈亏是按全数量盘口模拟卖出、扣掉手续费和滑点后的可退出净利；"
                + "现价是最新一根 15 分钟 K 线收盘价。全部为模拟记录，没有真实下单。");
        out.add("");

        // ---------- V2 ----------
        List<Row> rows = rows(v2);
        double cost = rows.stream().mapToDouble(Row::cost).sum();
        double net = rows.stream().mapToDouble(Row::net).sum();
        long losers = rows.stream().filter(r -> r.net() < 0).count();
        out.add("## V2 正式影子组合");
        out.add("");
        out.add(String.format(Locale.ROOT, "合计：%d 个仓位，总成本 %s U，浮动净盈亏 %s U（%s），%d 个浮亏、%d 个浮盈。",
                rows.size(), money(cost), signed(net), pct(cost == 0 ? 0 : net / cost * 100), losers, rows.size() - losers));
        out.add("");
        double realized = v2.realizedPnl();
        double today = realizedSince(ShadowBook.BOOK, local.toLocalDate().atStartOfDay(zone).toInstant());
        int closedToday = sellsSince(ShadowBook.BOOK, local.toLocalDate().atStartOfDay(zone).toInstant());
        out.add(String.format(Locale.ROOT, "已实现：今天 %s U（平仓 %d 笔），累计 %s U。已实现 + 浮动合计 %s U。",
                signed(today), closedToday, signed(realized), signed(realized + net)));
        out.add("");
        String regime = state.get("regime").orElse("?");
        StrategyParams.Capital c = params.capital();
        double pool = "STRONG".equals(regime) ? c.normalPool() + c.strategicReserve() : c.normalPool();
        double limit = Math.min(pool, c.hardCap() * c.deployPct(regime));
        double free = limit - cost + Math.min(0, realized) + Math.min(0, net);
        out.add(String.format(Locale.ROOT, "资金：行情 %s，可用上限 %s U（硬顶 %s × %.0f%%%s），已占用 %s U，还可用约 %s U。",
                regime, money(limit), money(c.hardCap()), c.deployPct(regime) * 100,
                "STRONG".equals(regime) ? "，含 3000 战略储备" : "，3000 战略储备只在 STRONG 时启用",
                money(cost), money(Math.max(0, free))));
        out.add("");
        if (!rows.isEmpty()) {
            out.add("| 代码 | 交易所 | 数量 | 成本 | 均价 | 现价 | 浮动盈亏 | 收益率 | 档数 | 持有 | 入场结构 |");
            out.add("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|");
            rows.stream().sorted(Comparator.comparingDouble(Row::pct)).forEach(r -> out.add(String.format(Locale.ROOT,
                    "| %s | %s | %s | %s | %s | %s | %s | %s | %d | %s | %s |",
                    r.p().symbol().replace("USDT", ""), r.p().venue(), num(r.p().qty().doubleValue()), money(r.cost()),
                    num(r.p().avgEntryPrice().doubleValue()), Double.isNaN(r.price()) ? "-" : num(r.price()),
                    signed(r.net()), pct(r.pct()), r.p().tranches(), hours(r.p().openedAt(), now),
                    structure(ShadowBook.BOOK, r.p()) + (r.p().protectArmed() ? "；利润保护中" : ""))));
            out.add("");
        }

        // ---------- BTC 每日短线 ----------
        out.add("## BTC 每日短线（影子）");
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
        out.add("## V1 宽口径观察（对照用，按参考价记账）");
        out.add("");
        out.add(String.format(Locale.ROOT, "合计：%d 个仓位，总成本 %s U，浮动净盈亏 %s U（%s），%d 个浮亏、%d 个浮盈。已实现累计 %s U，今天 %s U。",
                v1rows.size(), money(v1cost), signed(v1net), pct(v1cost == 0 ? 0 : v1net / v1cost * 100), v1losers,
                v1rows.size() - v1losers, signed(v1.realizedPnl()),
                signed(realizedSince(ObservationBook.BOOK, local.toLocalDate().atStartOfDay(zone).toInstant()))));
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

    private double realizedSince(String book, Instant since) {
        return jdbc.sql("SELECT pnl_usdt FROM shadow_event WHERE book = :b AND type = 'SELL' AND pnl_usdt IS NOT NULL AND created_at >= :t")
                .param("b", book).param("t", since.toString()).query(String.class).list().stream()
                .mapToDouble(Double::parseDouble).sum();
    }

    private int sellsSince(String book, Instant since) {
        return jdbc.sql("SELECT COUNT(*) FROM shadow_event WHERE book = :b AND type = 'SELL' AND created_at >= :t")
                .param("b", book).param("t", since.toString()).query(Integer.class).single();
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
