package com.trendpilot.btcdaily;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** BTC 每日短线的计划、账本、成交、回测结果。 */
@Repository
public class BtcDailyStore {

    private final JdbcClient jdbc;

    public BtcDailyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DailyPlan> plan(LocalDate day) {
        return jdbc.sql("SELECT * FROM btc_daily_plan WHERE day = :d").param("d", day.toString())
                .query((rs, i) -> new DailyPlan(LocalDate.parse(rs.getString("day")), rs.getString("version"),
                        rs.getDouble("open"), rs.getDouble("atr"), rs.getDouble("ema"), rs.getDouble("prev_close"),
                        rs.getInt("strong") == 1, rs.getInt("carried") == 1, rs.getDouble("tp_pct"),
                        parseLevels(rs.getString("levels")), rs.getInt("skip_new") == 1, rs.getDouble("multiplier"),
                        rs.getString("reasons")))
                .optional();
    }

    public void savePlan(DailyPlan p, Instant now) {
        jdbc.sql("""
                INSERT OR IGNORE INTO btc_daily_plan(day, version, open, atr, ema, prev_close, strong, carried, tp_pct, levels,
                    skip_new, multiplier, reasons, created_at)
                VALUES (:d, :v, :o, :a, :e, :pc, :s, :c, :tp, :l, :sk, :m, :r, :now)
                """)
                .param("d", p.day().toString()).param("v", p.version()).param("o", p.open()).param("a", p.atr())
                .param("e", p.ema()).param("pc", p.prevClose()).param("s", p.strong() ? 1 : 0)
                .param("c", p.carried() ? 1 : 0).param("tp", p.tpPct()).param("l", formatLevels(p.levels()))
                .param("sk", p.skipNew() ? 1 : 0).param("m", p.multiplier()).param("r", p.reasons())
                .param("now", now.toString()).update();
    }

    /** 账本和已处理到的 K 线位置；还没有时返回空账本、null 位置。 */
    public State state() {
        return jdbc.sql("SELECT * FROM btc_daily_state WHERE id = 1").query((rs, i) -> {
            Book b = new Book();
            b.qty = rs.getDouble("qty");
            b.cost = rs.getDouble("cost");
            b.buys = rs.getInt("buys");
            b.lastBuyPrice = rs.getDouble("last_buy_price");
            b.openedDay = day(rs.getString("opened_day"));
            b.realized = rs.getDouble("realized");
            b.rounds = rs.getInt("rounds");
            b.wins = rs.getInt("wins");
            b.lastExitDay = day(rs.getString("last_exit_day"));
            b.roundMaxFloatLoss = rs.getDouble("round_max_float_loss");
            b.maxFloatLoss = rs.getDouble("max_float_loss");
            b.openedAt = rs.getLong("opened_at");
            b.maePct = rs.getDouble("mae_pct");
            b.mfePct = rs.getDouble("mfe_pct");
            b.openFilters = rs.getString("open_filters");
            b.stopHits.putAll(parseStops(rs.getString("stop_hits")));
            b.trailPeak = rs.getDouble("trail_peak");
            long last = rs.getLong("last_bar_open");
            return new State(b, rs.wasNull() ? null : last);
        }).optional().orElseGet(() -> new State(new Book(), null));
    }

    public record State(Book book, Long lastBarOpen) {
    }

    /** 账本、位置和本次成交在同一个事务里写入。 */
    @Transactional
    public void save(Book b, Long lastBarOpen, List<Fill> fills, String version, Instant now) {
        save(b, lastBarOpen, fills, version, "", null, now);
    }

    /**
     * @param filters 成交当天计划里的市场 / 宏观过滤说明
     * @param round   本批成交里完成的一轮（没有为 null）
     */
    @Transactional
    public void save(Book b, Long lastBarOpen, List<Fill> fills, String version, String filters, Book.Round round,
                     Instant now) {
        jdbc.sql("""
                INSERT OR REPLACE INTO btc_daily_state(id, qty, cost, buys, last_buy_price, opened_day, realized, rounds,
                    wins, last_exit_day, round_max_float_loss, max_float_loss, last_bar_open, updated_at,
                    opened_at, mae_pct, mfe_pct, open_filters, stop_hits, trail_peak)
                VALUES (1, :q, :c, :b, :lb, :od, :r, :n, :w, :le, :rm, :m, :bar, :now, :oa, :mae, :mfe, :of, :sh, :tp)
                """)
                .param("tp", b.trailPeak)
                .param("oa", b.openedAt).param("mae", b.maePct).param("mfe", b.mfePct)
                .param("of", b.openFilters == null ? "" : b.openFilters).param("sh", formatStops(b.stopHits))
                .param("q", b.qty).param("c", b.cost).param("b", b.buys).param("lb", b.lastBuyPrice)
                .param("od", str(b.openedDay)).param("r", b.realized).param("n", b.rounds).param("w", b.wins)
                .param("le", str(b.lastExitDay)).param("rm", b.roundMaxFloatLoss).param("m", b.maxFloatLoss)
                .param("bar", lastBarOpen).param("now", now.toString()).update();
        for (Fill f : fills) {
            jdbc.sql("""
                    INSERT OR IGNORE INTO btc_daily_trade(day, bar_open, side, level, price, qty, usdt, fee, pnl, reason,
                        version, created_at, filled_at, filters)
                    VALUES (:d, :bo, :s, :l, :p, :q, :u, :f, :pnl, :r, :v, :now, :fa, :flt)
                    """)
                    .param("fa", Instant.ofEpochMilli(f.barOpenTime() + 15 * 60_000L).toString())
                    .param("flt", filters == null ? "" : filters)
                    .param("d", f.day().toString()).param("bo", f.barOpenTime()).param("s", f.side().name())
                    .param("l", f.level()).param("p", f.price()).param("q", f.qty()).param("u", f.usdt())
                    .param("f", f.fee()).param("pnl", f.pnl()).param("r", f.reason()).param("v", version)
                    .param("now", now.toString()).update();
        }
        if (round != null) {
            jdbc.sql("""
                    INSERT OR IGNORE INTO btc_daily_round(version, opened_day, opened_at, closed_at, hold_minutes, buys, cost,
                        proceeds, pnl, pnl_pct, mae_pct, mfe_pct, exit_reason, open_filters, stop_hits)
                    VALUES (:v, :od, :oa, :ca, :hm, :b, :c, :p, :pnl, :pp, :mae, :mfe, :er, :of, :sh)
                    """)
                    .param("v", round.version()).param("od", round.openedDay().toString())
                    .param("oa", Instant.ofEpochMilli(round.openedAt()).toString())
                    .param("ca", Instant.ofEpochMilli(round.closedAt() + 1).toString())
                    .param("hm", round.holdMinutes()).param("b", round.buys()).param("c", round.cost())
                    .param("p", round.proceeds()).param("pnl", round.pnl()).param("pp", round.pnlPct())
                    .param("mae", round.maePct()).param("mfe", round.mfePct()).param("er", round.exitReason())
                    .param("of", round.openFilters() == null ? "" : round.openFilters())
                    .param("sh", formatStops(round.stopHits())).update();
        }
    }

    /** 已完成各轮的汇总，以及"如果用候选止损"的对比（未触发的轮按实际结果；当前持仓按假设止损或浮动盈亏）。 */
    public Map<String, Object> roundStats(Book open, Double lastPrice, List<Double> candidates, double feeRate) {
        Map<String, Object> s = new java.util.LinkedHashMap<>(jdbc.sql("""
                SELECT COUNT(*) AS rounds, COALESCE(ROUND(SUM(pnl), 2), 0) AS pnl,
                       COALESCE(ROUND(AVG(pnl_pct), 3), 0) AS avg_pnl_pct,
                       COALESCE(ROUND(AVG(hold_minutes) / 60.0, 1), 0) AS avg_hold_hours,
                       COALESCE(ROUND(MAX(hold_minutes) / 1440.0, 1), 0) AS max_hold_days,
                       COALESCE(ROUND(MIN(mae_pct), 2), 0) AS worst_mae_pct,
                       COALESCE(ROUND(AVG(mae_pct), 2), 0) AS avg_mae_pct,
                       COALESCE(ROUND(AVG(mfe_pct), 2), 0) AS avg_mfe_pct
                FROM btc_daily_round WHERE voided = 0""").query().singleRow());
        List<Map<String, String>> rows = jdbc.sql("SELECT pnl, stop_hits FROM btc_daily_round WHERE voided = 0")
                .query((rs, i) -> Map.of("pnl", rs.getString(1), "hits", rs.getString(2))).list();
        double openPnl = open.holding() && lastPrice != null ? open.unrealized(lastPrice, feeRate) : 0;
        List<Map<String, Object>> alts = new ArrayList<>();
        double actual = rows.stream().mapToDouble(r -> Double.parseDouble(r.get("pnl"))).sum() + openPnl;
        for (double c : candidates) {
            String key = String.valueOf(c);
            double total = 0;
            int hits = 0;
            for (Map<String, String> r : rows) {
                Book.StopHit h = parseStops(r.get("hits")).get(key);
                if (h != null) hits++;
                total += h != null ? h.pnl() : Double.parseDouble(r.get("pnl"));
            }
            Book.StopHit h = open.holding() ? open.stopHits.get(key) : null;
            if (h != null) hits++;
            total += h != null ? h.pnl() : openPnl;
            alts.add(Map.of("stopPct", c, "hits", hits, "pnl", BtcDailyRules.round2(total)));
        }
        s.put("actualIncludingOpen", BtcDailyRules.round2(actual));
        s.put("stopCandidates", alts);
        return s;
    }

    public List<Map<String, Object>> recentRounds(int limit) {
        return jdbc.sql("SELECT * FROM btc_daily_round ORDER BY id DESC LIMIT :n").param("n", limit).query().listOfRows();
    }

    static String formatStops(Map<String, Book.StopHit> hits) {
        StringBuilder sb = new StringBuilder();
        hits.forEach((k, h) -> {
            if (!sb.isEmpty()) sb.append(';');
            sb.append(k).append(':').append(h.at()).append(':').append(h.price()).append(':').append(h.pnl());
        });
        return sb.toString();
    }

    static Map<String, Book.StopHit> parseStops(String s) {
        Map<String, Book.StopHit> out = new java.util.LinkedHashMap<>();
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(";")) {
            String[] f = part.split(":");
            out.put(f[0], new Book.StopHit(Long.parseLong(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3])));
        }
        return out;
    }

    public List<Map<String, Object>> recentTrades(int limit) {
        return jdbc.sql("SELECT * FROM btc_daily_trade ORDER BY id DESC LIMIT :n").param("n", limit).query().listOfRows();
    }

    /** 某天的已实现盈亏和成交笔数。 */
    public Map<String, Object> daySummary(LocalDate day) {
        return jdbc.sql("""
                SELECT COUNT(*) AS trades, COALESCE(SUM(CASE WHEN side = 'BUY' THEN 1 ELSE 0 END), 0) AS buys,
                       COALESCE(SUM(CASE WHEN side = 'SELL' THEN 1 ELSE 0 END), 0) AS sells,
                       COALESCE(ROUND(SUM(pnl), 2), 0) AS pnl
                FROM btc_daily_trade WHERE day = :d AND voided = 0""").param("d", day.toString()).query().singleRow();
    }

    public Optional<String> backtest(String version) {
        return jdbc.sql("SELECT result FROM btc_daily_backtest WHERE version = :v").param("v", version)
                .query(String.class).optional();
    }

    public void saveBacktest(String version, String resultJson, Instant now) {
        jdbc.sql("INSERT OR REPLACE INTO btc_daily_backtest(version, run_at, result) VALUES (:v, :t, :r)")
                .param("v", version).param("t", now.toString()).param("r", resultJson).update();
    }

    static String formatLevels(List<DailyPlan.Level> levels) {
        StringBuilder sb = new StringBuilder();
        for (DailyPlan.Level l : levels) {
            if (!sb.isEmpty()) sb.append(';');
            sb.append(l.index()).append(':').append(l.price()).append(':').append(l.usdt());
        }
        return sb.toString();
    }

    static List<DailyPlan.Level> parseLevels(String s) {
        List<DailyPlan.Level> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String part : s.split(";")) {
            String[] f = part.split(":");
            out.add(new DailyPlan.Level(Integer.parseInt(f[0]), Double.parseDouble(f[1]), Double.parseDouble(f[2])));
        }
        return out;
    }

    private static LocalDate day(String s) {
        return s == null ? null : LocalDate.parse(s);
    }

    private static String str(LocalDate d) {
        return d == null ? null : d.toString();
    }
}
