package com.trendpilot.market;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 15m K 线的 SQLite 缓存（按交易所区分），只保存已收盘的 K 线；REST 和 WebSocket 都写这里。 */
@Repository
public class KlineStore {

    static final Duration RETENTION = Duration.ofDays(9); // 信号跟踪 7 天窗口 + 信号前 1 天 + 1 天余量

    private final JdbcClient jdbc;

    public KlineStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Long latestOpenTime(Venue venue, String symbol) {
        return jdbc.sql("SELECT MAX(open_time) FROM kline_15m WHERE venue = :v AND symbol = :s")
                .param("v", venue.name()).param("s", symbol)
                .query(Long.class).optional().orElse(null);
    }

    /** 最近有 K 线写入的标的（启动时据此恢复 WebSocket 订阅）。 */
    public java.util.Set<String> recentSymbols(Venue venue, Instant since) {
        return new java.util.HashSet<>(jdbc.sql("SELECT DISTINCT symbol FROM kline_15m WHERE venue = :v AND open_time >= :t")
                .param("v", venue.name()).param("t", since.toEpochMilli()).query(String.class).list());
    }

    /** open_time 在 [from, to] 内的 K 线，按时间升序。 */
    public List<Kline> range(Venue venue, String symbol, long from, long to) {
        return jdbc.sql("""
                SELECT open_time, open, high, low, close, quote_volume, close_time FROM kline_15m
                WHERE venue = :v AND symbol = :s AND open_time BETWEEN :f AND :t ORDER BY open_time
                """)
                .param("v", venue.name()).param("s", symbol).param("f", from).param("t", to)
                .query((rs, i) -> new Kline(rs.getLong(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                        rs.getDouble(5), rs.getDouble(6), rs.getLong(7)))
                .list();
    }

    @Transactional
    public void upsert(Venue venue, String symbol, List<Kline> closed) {
        for (Kline k : closed) {
            jdbc.sql("""
                    INSERT OR REPLACE INTO kline_15m(venue, symbol, open_time, open, high, low, close, quote_volume, close_time)
                    VALUES (:v, :s, :ot, :o, :h, :l, :c, :qv, :ct)
                    """)
                    .param("v", venue.name()).param("s", symbol).param("ot", k.openTime()).param("o", k.open()).param("h", k.high())
                    .param("l", k.low()).param("c", k.close()).param("qv", k.quoteVolume()).param("ct", k.closeTime())
                    .update();
        }
    }

    public int prune(Instant now) {
        return jdbc.sql("DELETE FROM kline_15m WHERE open_time < :t")
                .param("t", now.minus(RETENTION).toEpochMilli()).update();
    }
}
