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
            long last = rs.getLong("last_bar_open");
            return new State(b, rs.wasNull() ? null : last);
        }).optional().orElseGet(() -> new State(new Book(), null));
    }

    public record State(Book book, Long lastBarOpen) {
    }

    /** 账本、位置和本次成交在同一个事务里写入。 */
    @Transactional
    public void save(Book b, Long lastBarOpen, List<Fill> fills, String version, Instant now) {
        jdbc.sql("""
                INSERT OR REPLACE INTO btc_daily_state(id, qty, cost, buys, last_buy_price, opened_day, realized, rounds,
                    wins, last_exit_day, round_max_float_loss, max_float_loss, last_bar_open, updated_at)
                VALUES (1, :q, :c, :b, :lb, :od, :r, :n, :w, :le, :rm, :m, :bar, :now)
                """)
                .param("q", b.qty).param("c", b.cost).param("b", b.buys).param("lb", b.lastBuyPrice)
                .param("od", str(b.openedDay)).param("r", b.realized).param("n", b.rounds).param("w", b.wins)
                .param("le", str(b.lastExitDay)).param("rm", b.roundMaxFloatLoss).param("m", b.maxFloatLoss)
                .param("bar", lastBarOpen).param("now", now.toString()).update();
        for (Fill f : fills) {
            jdbc.sql("""
                    INSERT OR IGNORE INTO btc_daily_trade(day, bar_open, side, level, price, qty, usdt, fee, pnl, reason, version, created_at)
                    VALUES (:d, :bo, :s, :l, :p, :q, :u, :f, :pnl, :r, :v, :now)
                    """)
                    .param("d", f.day().toString()).param("bo", f.barOpenTime()).param("s", f.side().name())
                    .param("l", f.level()).param("p", f.price()).param("q", f.qty()).param("u", f.usdt())
                    .param("f", f.fee()).param("pnl", f.pnl()).param("r", f.reason()).param("v", version)
                    .param("now", now.toString()).update();
        }
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
                FROM btc_daily_trade WHERE day = :d""").param("d", day.toString()).query().singleRow();
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
