package com.trendpilot.portfolio;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.market.ExecutionSimulator.Fill;
import com.trendpilot.market.Venue;

/**
 * 影子账本：事件只追加（shadow_event，按 book 区分 V1/V2），持仓表保存当前状态。
 * 所有写入都是模拟记录，capital_authority = NONE_SHADOW_ONLY。
 */
public abstract class Ledger {

    protected final JdbcClient jdbc;
    protected final StrategyParams params;
    private final String book;
    private final String table;

    protected Ledger(JdbcClient jdbc, StrategyParams params, String book, String table) {
        this.jdbc = jdbc;
        this.params = params;
        this.book = book;
        this.table = table;
    }

    public String book() {
        return book;
    }

    public List<Position> positions() {
        return jdbc.sql("SELECT * FROM " + table + " ORDER BY opened_at").query(Ledger::mapPosition).list();
    }

    public Optional<Position> position(String symbol) {
        return jdbc.sql("SELECT * FROM " + table + " WHERE symbol = :s").param("s", symbol)
                .query(Ledger::mapPosition).optional();
    }

    public double deployedCost() {
        return positions().stream().mapToDouble(p -> p.costBasis().doubleValue()).sum();
    }

    public double realizedPnl() {
        return jdbc.sql("SELECT pnl_usdt FROM shadow_event WHERE book = :b AND type = 'SELL' AND pnl_usdt IS NOT NULL")
                .param("b", book)
                .query(String.class).list().stream().mapToDouble(Double::parseDouble).sum();
    }

    /** 当前持仓按最近一次全数量模拟卖出的浮动盈亏合计。 */
    public double unrealizedPnl() {
        return jdbc.sql("SELECT mark_net FROM " + table).query(String.class).list().stream()
                .mapToDouble(Double::parseDouble).sum();
    }

    /** 浮亏超过成本 pct% 的持仓个数。 */
    public int underwaterCount(double pct) {
        return (int) positions().stream().filter(p -> markNet(p.symbol()) <= -p.costBasis().doubleValue() * pct / 100)
                .count();
    }

    public double markNet(String symbol) {
        return jdbc.sql("SELECT mark_net FROM " + table + " WHERE symbol = :s").param("s", symbol)
                .query(String.class).optional().map(Double::parseDouble).orElse(0.0);
    }

    /** 本持仓上一次计入论点观察的小时 K 线 openTime；只有出现新的小时 K 线才算新的有效观察。 */
    public boolean isNewObservation(String symbol, long hourOpenTime) {
        Long last = jdbc.sql("SELECT last_obs_hour FROM " + table + " WHERE symbol = :s").param("s", symbol)
                .query((rs, i) -> rs.getObject(1) == null ? null : rs.getLong(1)).optional().orElse(null);
        return last == null || hourOpenTime > last;
    }

    public void markObserved(String symbol, long hourOpenTime) {
        jdbc.sql("UPDATE " + table + " SET last_obs_hour = :h WHERE symbol = :s").param("h", hourOpenTime)
                .param("s", symbol).update();
    }

    /** 记录一次权益（已实现 + 浮动），最大回撤按这条曲线算。 */
    public void recordEquity(Instant now) {
        jdbc.sql("INSERT INTO equity_curve(book, at, realized, unrealized) VALUES (:b, :t, :r, :u)")
                .param("b", book).param("t", now.toString()).param("r", realizedPnl()).param("u", unrealizedPnl())
                .update();
    }

    public int lossExitsSince(Instant since) {
        return (int) jdbc.sql("SELECT pnl_usdt FROM shadow_event WHERE book = :b AND type = 'SELL' AND created_at >= :t")
                .param("b", book)
                .param("t", since.toString()).query(String.class).list().stream()
                .filter(v -> v != null && Double.parseDouble(v) < 0).count();
    }

    public Optional<Instant> lastExit(String symbol) {
        return jdbc.sql("SELECT MAX(created_at) FROM shadow_event WHERE book = :b AND type = 'SELL' AND symbol = :s")
                .param("b", book)
                .param("s", symbol).query(String.class).optional().map(Instant::parse);
    }

    @Transactional
    public Position open(Venue venue, String symbol, Fill fill, String generation, Instant now) {
        Position p = new Position(venue, symbol, fill.qty(), fill.totalCost(), fill.notional(), 1, fill.avgPrice(), now,
                generation, now, false, BigDecimal.ZERO, BigDecimal.ZERO, 0, false);
        insertPosition(p, now);
        event(venue, symbol, "BUY", fill, null, book + "_BUY", generation, now);
        return p;
    }

    @Transactional
    public Position add(Position cur, Fill fill, String generation, Instant now) {
        // 加仓后利润保护重新基准化
        Position p = new Position(cur.venue(), cur.symbol(), cur.qty().add(fill.qty()), cur.costBasis().add(fill.totalCost()),
                cur.entryNotional().add(fill.notional()), cur.tranches() + 1, fill.avgPrice(), now, generation,
                cur.openedAt(), false, BigDecimal.ZERO, BigDecimal.ZERO, 0, false);
        Excursion ex = excursion(cur.symbol());
        Long lastObs = jdbc.sql("SELECT last_obs_hour FROM " + table + " WHERE symbol = :s").param("s", cur.symbol())
                .query((rs, i) -> rs.getObject(1) == null ? null : rs.getLong(1)).optional().orElse(null);
        jdbc.sql("DELETE FROM " + table + " WHERE symbol = :s").param("s", cur.symbol()).update();
        insertPosition(p, now);
        jdbc.sql("UPDATE " + table + " SET max_net = :max, min_net = :min, first_profit_at = :fp, last_obs_hour = :h"
                        + " WHERE symbol = :s")
                .param("max", ex.maxNet()).param("min", ex.minNet()).param("fp", ex.firstProfitAt()).param("h", lastObs)
                .param("s", cur.symbol()).update();
        event(cur.venue(), cur.symbol(), "ADD", fill, null, book + "_ADD", generation, now);
        return p;
    }

    @Transactional
    public void close(Position cur, Fill fill, BigDecimal pnl, String reason, String generation, Instant now) {
        Excursion ex = excursion(cur.symbol());
        double finalNet = pnl.doubleValue();
        double maxNet = ex.maxNet() == null ? finalNet : Math.max(ex.maxNet(), finalNet);
        double minNet = ex.minNet() == null ? finalNet : Math.min(ex.minNet(), finalNet);
        Instant firstProfit = ex.firstProfitAt() != null ? Instant.parse(ex.firstProfitAt()) : finalNet > 0 ? now : null;
        jdbc.sql("DELETE FROM " + table + " WHERE symbol = :s").param("s", cur.symbol()).update();
        event(cur.venue(), cur.symbol(), "SELL", fill, pnl, reason, generation, now);
        jdbc.sql("""
                UPDATE shadow_event SET cost_usdt = :cost, hold_minutes = :hold, max_net = :max, min_net = :min,
                    minutes_to_profit = :mtp
                WHERE id = (SELECT MAX(id) FROM shadow_event WHERE book = :b AND symbol = :s AND type = 'SELL')
                """)
                .param("cost", cur.costBasis().toPlainString())
                .param("hold", Duration.between(cur.openedAt(), now).toMinutes())
                .param("max", maxNet).param("min", minNet)
                .param("mtp", firstProfit == null ? null : Duration.between(cur.openedAt(), firstProfit).toMinutes())
                .param("b", book).param("s", cur.symbol()).update();
    }

    /** @param markNet 本轮全数量模拟卖出的可退出净利，即未实现盈亏 */
    public void update(Position p, BigDecimal markNet, Instant now) {
        jdbc.sql("UPDATE " + table + """
                 SET protect_armed = :armed, protect_floor = :floor, peak_net = :peak,
                    weak_streak = :streak, loss_recovery = :recovery, mark_net = :mark, updated_at = :now,
                    max_net = MAX(COALESCE(max_net, :markD), :markD), min_net = MIN(COALESCE(min_net, :markD), :markD),
                    first_profit_at = COALESCE(first_profit_at, CASE WHEN :markD > 0 THEN :now END)
                 WHERE symbol = :s
                """)
                .param("mark", markNet.toPlainString()).param("markD", markNet.doubleValue())
                .param("armed", p.protectArmed() ? 1 : 0).param("floor", p.protectFloor().toPlainString())
                .param("peak", p.peakNet().toPlainString()).param("streak", p.weakStreak())
                .param("recovery", p.lossRecovery() ? 1 : 0).param("now", now.toString()).param("s", p.symbol())
                .update();
    }

    /** 持仓期间的最大可退出净利、最小可退出净利（U）与首次转盈利时间；还没观察过时为 null。 */
    record Excursion(Double maxNet, Double minNet, String firstProfitAt) {
    }

    private Excursion excursion(String symbol) {
        return jdbc.sql("SELECT max_net, min_net, first_profit_at FROM " + table + " WHERE symbol = :s")
                .param("s", symbol)
                .query((rs, i) -> new Excursion(rs.getObject(1) == null ? null : rs.getDouble(1),
                        rs.getObject(2) == null ? null : rs.getDouble(2), rs.getString(3)))
                .optional().orElse(new Excursion(null, null, null));
    }

    /** 账本统计，用于 V1 / V2 收益对比。 */
    public BookStats stats() {
        List<Object[]> sells = jdbc.sql("""
                SELECT pnl_usdt, cost_usdt, hold_minutes, max_net, min_net, minutes_to_profit FROM shadow_event
                WHERE book = :b AND type = 'SELL' AND pnl_usdt IS NOT NULL ORDER BY id
                """).param("b", book)
                .query((rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getObject(3), rs.getObject(4),
                        rs.getObject(5), rs.getObject(6)}).list();
        int wins = 0;
        double realized = 0, peak = 0, maxDrawdown = 0, retSum = 0, holdSum = 0;
        int retCount = 0, holdCount = 0, excursionCount = 0, profitCount = 0;
        double maxUpSum = 0, maxDownSum = 0, toProfitSum = 0;
        for (Object[] r : sells) {
            double pnl = Double.parseDouble((String) r[0]);
            if (pnl > 0) wins++;
            realized += pnl;
            peak = Math.max(peak, realized);
            maxDrawdown = Math.max(maxDrawdown, peak - realized);
            if (r[1] != null) {
                retSum += pnl / Double.parseDouble((String) r[1]) * 100;
                retCount++;
            }
            if (r[1] != null && r[3] != null && r[4] != null) {
                double cost = Double.parseDouble((String) r[1]);
                maxUpSum += ((Number) r[3]).doubleValue() / cost * 100;
                maxDownSum += ((Number) r[4]).doubleValue() / cost * 100;
                excursionCount++;
            }
            if (r[5] != null) {
                toProfitSum += ((Number) r[5]).doubleValue();
                profitCount++;
            }
            if (r[2] != null) {
                holdSum += ((Number) r[2]).doubleValue();
                holdCount++;
            }
        }
        List<Position> open = positions();
        double unrealized = unrealizedPnl();
        // 最大回撤按含浮亏的权益曲线（每次 Monitor 记录一点）；当前时刻也算一点
        List<Double> equity = new ArrayList<>(jdbc.sql("SELECT realized + unrealized FROM equity_curve WHERE book = :b ORDER BY id")
                .param("b", book).query(Double.class).list());
        equity.add(realized + unrealized);
        double equityPeak = 0;
        for (double v : equity) {
            equityPeak = Math.max(equityPeak, v);
            maxDrawdown = Math.max(maxDrawdown, equityPeak - v);
        }
        int entries = jdbc.sql("SELECT COUNT(*) FROM shadow_event WHERE book = :b AND type IN ('BUY', 'ADD')")
                .param("b", book).query(Integer.class).single();
        return new BookStats(book, entries, sells.size(), wins, realized, unrealized,
                open.size(), open.stream().mapToDouble(p -> p.costBasis().doubleValue()).sum(),
                retCount == 0 ? Double.NaN : retSum / retCount, holdCount == 0 ? Double.NaN : holdSum / holdCount / 60,
                maxDrawdown, excursionCount == 0 ? Double.NaN : maxUpSum / excursionCount,
                excursionCount == 0 ? Double.NaN : maxDownSum / excursionCount,
                profitCount == 0 ? Double.NaN : toProfitSum / profitCount / 60);
    }

    /** 状态类事件（PROTECT_ARM 等），不涉及成交。 */
    public void note(Position pos, String type, String generation, Instant now) {
        event(pos.venue(), pos.symbol(), type, null, null, type, generation, now);
    }

    /** 记录 EARLY 首次出现价格；超过 7 天未再出现则重新计。 */
    public BigDecimal touchEarlySeen(String symbol, double price, Instant now) {
        Optional<String[]> row = jdbc.sql("SELECT first_price, last_seen_at FROM early_seen WHERE symbol = :s")
                .param("s", symbol).query((rs, i) -> new String[]{rs.getString(1), rs.getString(2)}).optional();
        // 7 天没出现过，或首次价记录早于最近一次卖出：首次价重置为这次 EARLY 的价格
        boolean reset = row.isEmpty() || Duration.between(Instant.parse(row.get()[1]), now).toDays() >= 7
                || lastExit(symbol).filter(e -> e.isAfter(firstSeenAt(symbol))).isPresent();
        if (reset) {
            jdbc.sql("INSERT OR REPLACE INTO early_seen(symbol, first_price, first_seen_at, last_seen_at) VALUES (:s, :p, :t, :t)")
                    .param("s", symbol).param("p", BigDecimal.valueOf(price).toPlainString()).param("t", now.toString())
                    .update();
            return BigDecimal.valueOf(price);
        }
        jdbc.sql("UPDATE early_seen SET last_seen_at = :t WHERE symbol = :s").param("t", now.toString())
                .param("s", symbol).update();
        return new BigDecimal(row.get()[0]);
    }

    private Instant firstSeenAt(String symbol) {
        return jdbc.sql("SELECT first_seen_at FROM early_seen WHERE symbol = :s").param("s", symbol)
                .query(String.class).optional().map(Instant::parse).orElse(Instant.EPOCH);
    }

    /** 某时刻之后（不含）独立 generation 的 EARLY 观察次数。 */
    public int earlyObservationsSince(String symbol, Instant since) {
        return jdbc.sql("SELECT COUNT(DISTINCT generation) FROM signal_log WHERE symbol = :s AND created_at > :t")
                .param("s", symbol).param("t", since.toString()).query(Integer.class).single();
    }

    public Optional<BigDecimal> earlySeenPrice(String symbol) {
        return jdbc.sql("SELECT first_price FROM early_seen WHERE symbol = :s").param("s", symbol)
                .query(String.class).optional().map(BigDecimal::new);
    }

    private void insertPosition(Position p, Instant now) {
        jdbc.sql("INSERT INTO " + table + """
                (venue, symbol, qty, cost_basis, entry_notional, tranches, last_tranche_price,
                    last_signal_at, last_generation, opened_at, protect_armed, protect_floor, peak_net,
                    weak_streak, loss_recovery, updated_at)
                VALUES (:venue, :s, :qty, :cost, :notional, :tr, :ltp, :lsa, :gen, :opened, :armed, :floor, :peak,
                    :streak, :recovery, :now)
                """)
                .param("venue", p.venue().name()).param("s", p.symbol()).param("qty", p.qty().toPlainString())
                .param("cost", p.costBasis().toPlainString()).param("notional", p.entryNotional().toPlainString())
                .param("tr", p.tranches()).param("ltp", p.lastTranchePrice().toPlainString())
                .param("lsa", p.lastSignalAt().toString()).param("gen", p.lastGeneration())
                .param("opened", p.openedAt().toString()).param("armed", p.protectArmed() ? 1 : 0)
                .param("floor", p.protectFloor().toPlainString()).param("peak", p.peakNet().toPlainString())
                .param("streak", p.weakStreak()).param("recovery", p.lossRecovery() ? 1 : 0)
                .param("now", now.toString()).update();
    }

    private void event(Venue venue, String symbol, String type, Fill fill, BigDecimal pnl, String reason, String generation,
                       Instant now) {
        jdbc.sql("""
                INSERT INTO shadow_event(event_id, book, venue, symbol, type, quantity, price, notional_usdt, fee_usdt,
                    pnl_usdt, reason_code, generation, params_version, created_at)
                VALUES (:id, :book, :venue, :s, :type, :qty, :price, :notional, :fee, :pnl, :reason, :gen, :ver, :now)
                """)
                .param("id", UUID.randomUUID().toString()).param("book", book).param("venue", venue.name()).param("s", symbol).param("type", type)
                .param("qty", fill == null ? null : fill.qty().toPlainString())
                .param("price", fill == null ? null : fill.avgPrice().toPlainString())
                .param("notional", fill == null ? null : fill.notional().toPlainString())
                .param("fee", fill == null ? null : fill.fee().toPlainString())
                .param("pnl", pnl == null ? null : pnl.toPlainString())
                .param("reason", reason)
                .param("gen", generation).param("ver", params.version()).param("now", now.toString())
                .update();
    }

    private static Position mapPosition(ResultSet rs, int row) throws SQLException {
        return new Position(Venue.valueOf(rs.getString("venue")), rs.getString("symbol"), new BigDecimal(rs.getString("qty")),
                new BigDecimal(rs.getString("cost_basis")), new BigDecimal(rs.getString("entry_notional")),
                rs.getInt("tranches"), new BigDecimal(rs.getString("last_tranche_price")),
                Instant.parse(rs.getString("last_signal_at")), rs.getString("last_generation"),
                Instant.parse(rs.getString("opened_at")), rs.getInt("protect_armed") == 1,
                new BigDecimal(rs.getString("protect_floor")), new BigDecimal(rs.getString("peak_net")),
                rs.getInt("weak_streak"), rs.getInt("loss_recovery") == 1);
    }
}
