package com.trendpilot.portfolio;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.market.ExecutionSimulator.Fill;

/**
 * V2 影子账本：事件只追加（shadow_event），持仓表保存当前状态。
 * 所有写入都是模拟记录，capital_authority = NONE_SHADOW_ONLY。
 */
@Repository
public class ShadowBook {

    public static final String BOOK = "V2";

    private final JdbcClient jdbc;
    private final StrategyParams params;

    public ShadowBook(JdbcClient jdbc, StrategyParams params) {
        this.jdbc = jdbc;
        this.params = params;
    }

    public List<Position> positions() {
        return jdbc.sql("SELECT * FROM position ORDER BY opened_at").query(ShadowBook::mapPosition).list();
    }

    public Optional<Position> position(String symbol) {
        return jdbc.sql("SELECT * FROM position WHERE symbol = :s").param("s", symbol)
                .query(ShadowBook::mapPosition).optional();
    }

    public double deployedCost() {
        return positions().stream().mapToDouble(p -> p.costBasis().doubleValue()).sum();
    }

    public double realizedPnl() {
        return jdbc.sql("SELECT pnl_usdt FROM shadow_event WHERE type = 'SELL' AND pnl_usdt IS NOT NULL")
                .query(String.class).list().stream().mapToDouble(Double::parseDouble).sum();
    }

    public int lossExitsSince(Instant since) {
        return (int) jdbc.sql("SELECT pnl_usdt FROM shadow_event WHERE type = 'SELL' AND created_at >= :t")
                .param("t", since.toString()).query(String.class).list().stream()
                .filter(v -> v != null && Double.parseDouble(v) < 0).count();
    }

    public Optional<Instant> lastExit(String symbol) {
        return jdbc.sql("SELECT MAX(created_at) FROM shadow_event WHERE type = 'SELL' AND symbol = :s")
                .param("s", symbol).query(String.class).optional().map(Instant::parse);
    }

    @Transactional
    public Position open(String symbol, Fill fill, String generation, Instant now) {
        Position p = new Position(symbol, fill.qty(), fill.totalCost(), fill.notional(), 1, fill.avgPrice(), now,
                generation, now, false, BigDecimal.ZERO, BigDecimal.ZERO, 0, false);
        insertPosition(p, now);
        event(symbol, "BUY", fill, null, "CAPITAL_REVIEW_BUY", generation, now);
        return p;
    }

    @Transactional
    public Position add(Position cur, Fill fill, String generation, Instant now) {
        // 加仓后利润保护重新基准化
        Position p = new Position(cur.symbol(), cur.qty().add(fill.qty()), cur.costBasis().add(fill.totalCost()),
                cur.entryNotional().add(fill.notional()), cur.tranches() + 1, fill.avgPrice(), now, generation,
                cur.openedAt(), false, BigDecimal.ZERO, BigDecimal.ZERO, 0, false);
        jdbc.sql("DELETE FROM position WHERE symbol = :s").param("s", cur.symbol()).update();
        insertPosition(p, now);
        event(cur.symbol(), "ADD", fill, null, "CAPITAL_REVIEW_ADD", generation, now);
        return p;
    }

    @Transactional
    public void close(Position cur, Fill fill, BigDecimal pnl, String reason, String generation, Instant now) {
        jdbc.sql("DELETE FROM position WHERE symbol = :s").param("s", cur.symbol()).update();
        event(cur.symbol(), "SELL", fill, pnl, reason, generation, now);
    }

    public void update(Position p, Instant now) {
        jdbc.sql("""
                UPDATE position SET protect_armed = :armed, protect_floor = :floor, peak_net = :peak,
                    weak_streak = :streak, loss_recovery = :recovery, updated_at = :now WHERE symbol = :s
                """)
                .param("armed", p.protectArmed() ? 1 : 0).param("floor", p.protectFloor().toPlainString())
                .param("peak", p.peakNet().toPlainString()).param("streak", p.weakStreak())
                .param("recovery", p.lossRecovery() ? 1 : 0).param("now", now.toString()).param("s", p.symbol())
                .update();
    }

    /** 状态类事件（PROTECT_ARM 等），不涉及成交。 */
    public void note(String symbol, String type, String generation, Instant now) {
        event(symbol, type, null, null, type, generation, now);
    }

    /** 记录 EARLY 首次出现价格；超过 7 天未再出现则重新计。 */
    public BigDecimal touchEarlySeen(String symbol, double price, Instant now) {
        Optional<String[]> row = jdbc.sql("SELECT first_price, last_seen_at FROM early_seen WHERE symbol = :s")
                .param("s", symbol).query((rs, i) -> new String[]{rs.getString(1), rs.getString(2)}).optional();
        boolean reset = row.isEmpty() || Duration.between(Instant.parse(row.get()[1]), now).toDays() >= 7;
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

    public Optional<BigDecimal> earlySeenPrice(String symbol) {
        return jdbc.sql("SELECT first_price FROM early_seen WHERE symbol = :s").param("s", symbol)
                .query(String.class).optional().map(BigDecimal::new);
    }

    private void insertPosition(Position p, Instant now) {
        jdbc.sql("""
                INSERT INTO position(symbol, qty, cost_basis, entry_notional, tranches, last_tranche_price,
                    last_signal_at, last_generation, opened_at, protect_armed, protect_floor, peak_net,
                    weak_streak, loss_recovery, updated_at)
                VALUES (:s, :qty, :cost, :notional, :tr, :ltp, :lsa, :gen, :opened, :armed, :floor, :peak,
                    :streak, :recovery, :now)
                """)
                .param("s", p.symbol()).param("qty", p.qty().toPlainString())
                .param("cost", p.costBasis().toPlainString()).param("notional", p.entryNotional().toPlainString())
                .param("tr", p.tranches()).param("ltp", p.lastTranchePrice().toPlainString())
                .param("lsa", p.lastSignalAt().toString()).param("gen", p.lastGeneration())
                .param("opened", p.openedAt().toString()).param("armed", p.protectArmed() ? 1 : 0)
                .param("floor", p.protectFloor().toPlainString()).param("peak", p.peakNet().toPlainString())
                .param("streak", p.weakStreak()).param("recovery", p.lossRecovery() ? 1 : 0)
                .param("now", now.toString()).update();
    }

    private void event(String symbol, String type, Fill fill, BigDecimal pnl, String reason, String generation,
                       Instant now) {
        jdbc.sql("""
                INSERT INTO shadow_event(event_id, book, symbol, type, quantity, price, notional_usdt, fee_usdt,
                    pnl_usdt, reason_code, generation, params_version, created_at)
                VALUES (:id, :book, :s, :type, :qty, :price, :notional, :fee, :pnl, :reason, :gen, :ver, :now)
                """)
                .param("id", UUID.randomUUID().toString()).param("book", BOOK).param("s", symbol).param("type", type)
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
        return new Position(rs.getString("symbol"), new BigDecimal(rs.getString("qty")),
                new BigDecimal(rs.getString("cost_basis")), new BigDecimal(rs.getString("entry_notional")),
                rs.getInt("tranches"), new BigDecimal(rs.getString("last_tranche_price")),
                Instant.parse(rs.getString("last_signal_at")), rs.getString("last_generation"),
                Instant.parse(rs.getString("opened_at")), rs.getInt("protect_armed") == 1,
                new BigDecimal(rs.getString("protect_floor")), new BigDecimal(rs.getString("peak_net")),
                rs.getInt("weak_streak"), rs.getInt("loss_recovery") == 1);
    }
}
