package com.trendpilot.monitor;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.trendpilot.portfolio.Position;

/**
 * 上涨逻辑失效时的亏损离场（{@link DecayBounceExit}）在 V2 / V2D15 上的执行：状态存 decay_wait（重启不丢）。
 * 只在现行规则本轮不卖（HOLD）、盘口正常且能完整成交、交易所在交易、有新收盘的 1h K 线时判断；
 * 任何一项缺失都不动作（数据缺失只能导致不动作）。卖出按本轮全数量盘口模拟成交，原因 DECAY_STOP。
 */
@Component
public class DecayGuard {

    private final JdbcClient jdbc;
    private final boolean enabled;

    public DecayGuard(JdbcClient jdbc, @Value("${trendpilot.decay-stop.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    /** @return 要卖出时的新判断（DECAY_STOP），否则原样返回 d */
    public ExitDecision apply(String book, Position pos, ExitDecision d, boolean trading, double hourClose, long hourOpen,
                              Instant now) {
        if (!enabled || !trading || d.sell() || !"HOLD".equals(d.reason()) || d.notes().contains("ABNORMAL_BOOK_HOLD")
                || d.fill() == null || !d.fill().complete() || Double.isNaN(hourClose)) {
            return d;
        }
        String v = pos.venue().name(), sym = pos.symbol(), o = pos.openedAt().toString();
        DecayBounceExit.State st = jdbc.sql("""
                SELECT wait_since, prev_close, rose, last_hour FROM decay_wait
                WHERE book = :b AND venue = :v AND symbol = :s AND opened_at = :o
                """).param("b", book).param("v", v).param("s", sym).param("o", o)
                .query((rs, i) -> new DecayBounceExit.State(rs.getString(1) == null ? null : Instant.parse(rs.getString(1)),
                        (Double) rs.getObject(2), rs.getInt(3) != 0, rs.getObject(4) == null ? null : rs.getLong(4)))
                .optional().orElse(DecayBounceExit.State.NONE);
        DecayBounceExit.Step step = DecayBounceExit.step(st, d.updated().lossRecovery(), now, hourClose, hourOpen,
                d.netPnl().doubleValue());
        if (step.sell()) {
            return new ExitDecision(d.symbol(), true, DecayBounceExit.REASON, d.updated(), d.notes(), d.netPnl(),
                    d.rawReturnPct(), d.fill());
        }
        DecayBounceExit.State n = step.next();
        jdbc.sql("""
                INSERT INTO decay_wait(book, venue, symbol, opened_at, wait_since, prev_close, rose, last_hour)
                VALUES (:b, :v, :s, :o, :ws, :pc, :ro, :lh)
                ON CONFLICT(book, venue, symbol, opened_at) DO UPDATE SET wait_since = :ws, prev_close = :pc, rose = :ro,
                    last_hour = :lh
                """).param("b", book).param("v", v).param("s", sym).param("o", o)
                .param("ws", n.waitSince() == null ? null : n.waitSince().toString()).param("pc", n.prevClose())
                .param("ro", n.rose() ? 1 : 0).param("lh", n.lastHour()).update();
        return d;
    }
}
