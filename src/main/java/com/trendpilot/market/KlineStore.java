package com.trendpilot.market;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 15m K 线的 SQLite 缓存，只保存已收盘的 K 线。 */
@Repository
public class KlineStore {

    static final Duration RETENTION = Duration.ofDays(3);

    private final JdbcClient jdbc;

    public KlineStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Long latestOpenTime(String symbol) {
        return jdbc.sql("SELECT MAX(open_time) FROM kline_15m WHERE symbol = :s").param("s", symbol)
                .query(Long.class).optional().orElse(null);
    }

    /** open_time 在 [from, to] 内的 K 线，按时间升序。 */
    public List<Kline> range(String symbol, long from, long to) {
        return jdbc.sql("""
                SELECT open_time, open, high, low, close, quote_volume, close_time FROM kline_15m
                WHERE symbol = :s AND open_time BETWEEN :f AND :t ORDER BY open_time
                """)
                .param("s", symbol).param("f", from).param("t", to)
                .query((rs, i) -> new Kline(rs.getLong(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                        rs.getDouble(5), rs.getDouble(6), rs.getLong(7)))
                .list();
    }

    @Transactional
    public void upsert(String symbol, List<Kline> closed) {
        for (Kline k : closed) {
            jdbc.sql("""
                    INSERT OR REPLACE INTO kline_15m(symbol, open_time, open, high, low, close, quote_volume, close_time)
                    VALUES (:s, :ot, :o, :h, :l, :c, :qv, :ct)
                    """)
                    .param("s", symbol).param("ot", k.openTime()).param("o", k.open()).param("h", k.high())
                    .param("l", k.low()).param("c", k.close()).param("qv", k.quoteVolume()).param("ct", k.closeTime())
                    .update();
        }
    }

    public int prune(Instant now) {
        return jdbc.sql("DELETE FROM kline_15m WHERE open_time < :t")
                .param("t", now.minus(RETENTION).toEpochMilli()).update();
    }
}
