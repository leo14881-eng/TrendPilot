package com.trendpilot.btctrend;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** BTC 均线趋势的独立账本（btc_trend_slot / btc_trend_trade / btc_trend_meta），不和其他账本共用任何表。 */
@Component
public class BtcTrendStore {

    public enum State { USDT, BTC }

    /** 一份资金。cost：持有 BTC 时这一轮买入花的 USDT；持有 USDT 时为 0。 */
    public record Slot(int sma, State state, double usdt, double btc, double cost, String lastSignalDay) {
        public boolean holding() {
            return state == State.BTC;
        }
    }

    public record TradeRow(String signalDay, String execDay, int sma, String side, double price, double usdt, double btc,
                           double fee, String mode) {
    }

    private final JdbcClient jdbc;

    public BtcTrendStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Slot> slots() {
        return jdbc.sql("SELECT sma, state, usdt, btc, cost, last_signal_day FROM btc_trend_slot ORDER BY sma")
                .query((rs, n) -> new Slot(rs.getInt(1), State.valueOf(rs.getString(2)), rs.getDouble(3), rs.getDouble(4),
                        rs.getDouble(5), rs.getString(6)))
                .list();
    }

    /** 第一次运行：4 份都是 USDT，各 CAPITAL / 4。已有就不动。 */
    public void ensureSlots(Instant now) {
        double per = MaTrendRules.CAPITAL / MaTrendRules.MAS.length;
        for (int n : MaTrendRules.MAS) {
            jdbc.sql("""
                    INSERT OR IGNORE INTO btc_trend_slot(sma, state, usdt, btc, cost, last_signal_day, updated_at)
                    VALUES (:n, 'USDT', :u, 0, 0, NULL, :now)
                    """).param("n", n).param("u", per).param("now", now.toString()).update();
        }
    }

    public void save(Slot s, Instant now) {
        jdbc.sql("""
                UPDATE btc_trend_slot SET state = :st, usdt = :u, btc = :b, cost = :c, last_signal_day = :d, updated_at = :now
                WHERE sma = :n
                """).param("st", s.state().name()).param("u", s.usdt()).param("b", s.btc()).param("c", s.cost())
                .param("d", s.lastSignalDay()).param("now", now.toString()).param("n", s.sma()).update();
    }

    /** @return 是否新写入（同一信号日同一份只记一次） */
    public boolean insertTrade(TradeRow t, Instant now) {
        return jdbc.sql("""
                INSERT OR IGNORE INTO btc_trend_trade(signal_day, exec_day, sma, side, price, usdt, btc, fee, mode, created_at)
                VALUES (:sd, :ed, :n, :side, :p, :u, :b, :f, :m, :now)
                """).param("sd", t.signalDay()).param("ed", t.execDay()).param("n", t.sma()).param("side", t.side())
                .param("p", t.price()).param("u", t.usdt()).param("b", t.btc()).param("f", t.fee()).param("m", t.mode())
                .param("now", now.toString()).update() > 0;
    }

    public List<TradeRow> trades() {
        return jdbc.sql("SELECT signal_day, exec_day, sma, side, price, usdt, btc, fee, mode FROM btc_trend_trade ORDER BY id")
                .query((rs, n) -> new TradeRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getDouble(5),
                        rs.getDouble(6), rs.getDouble(7), rs.getDouble(8), rs.getString(9)))
                .list();
    }

    public Optional<String> meta(String key) {
        return jdbc.sql("SELECT value FROM btc_trend_meta WHERE key = :k").param("k", key).query(String.class).optional();
    }

    public void putMeta(String key, String value) {
        jdbc.sql("INSERT INTO btc_trend_meta(key, value) VALUES (:k, :v) ON CONFLICT(key) DO UPDATE SET value = excluded.value")
                .param("k", key).param("v", value).update();
    }

    /** 只在没有时写入（启动日、"一直拿着"基准只记第一次）。 */
    public void putMetaIfAbsent(String key, String value) {
        jdbc.sql("INSERT OR IGNORE INTO btc_trend_meta(key, value) VALUES (:k, :v)").param("k", key).param("v", value).update();
    }
}
