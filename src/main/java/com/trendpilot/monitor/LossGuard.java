package com.trendpilot.monitor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.trendpilot.portfolio.Position;
import com.trendpilot.variant.AtrStaleExit;

/**
 * 山寨止损（2026-10-17 用户要求："只有 BTC 不止损，其他币种要止损"，山寨可能归零）。V2、V2D15 和 V5 共用，叠加在现行退出规则之上：
 * <ul>
 * <li>止损：买入时日线 ATR14 × 1.5（限制在 6%～25%），按 1h 收盘相对均价确认；−30% 灾难线。</li>
 * <li>3 天不赚钱离场：持有满 72 小时、利润保护没启动、1h 收盘仍亏损 → 等反弹：回本，或反弹后第一根回落的 1h 收盘卖，
 *     最多再等 24 小时。</li>
 * </ul>
 * 判断逻辑与 4 年回测、影子并行 X1 是同一份代码（{@link AtrStaleExit}）。每根新收盘的 1h K 线只判断一次；
 * K 线、盘口或交易状态缺失时不判断（数据缺失只能导致不动作）。卖出按本轮全数量盘口模拟成交。
 * BTC 每日短线不经过这里，仍然不止损。
 */
@Component
public class LossGuard {

    public static final String STOP_LOSS = "STOP_LOSS";
    public static final String STALE_EXIT = "STALE_EXIT";

    private final JdbcClient jdbc;
    private final boolean enabled;
    /** 锁外取好的日线 ATR%（book:venue:symbol → ATR%），新持仓第一次判断时用一次。 */
    private final Map<String, Double> dailyAtr = new ConcurrentHashMap<>();

    public LossGuard(JdbcClient jdbc, @Value("${trendpilot.loss-guard.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 还没有止损距离的持仓：Monitor 在锁外为它们各取一次日线。 */
    public List<Position> needDailyAtr(String book, List<Position> positions) {
        if (!enabled) return List.of();
        return positions.stream().filter(p -> jdbc.sql("""
                SELECT COUNT(*) FROM loss_guard WHERE book = :b AND venue = :v AND symbol = :s AND opened_at = :o
                    AND dist_pct IS NOT NULL
                """).param("b", book).param("v", p.venue().name()).param("s", p.symbol())
                .param("o", p.openedAt().toString()).query(Integer.class).single() == 0).toList();
    }

    public void provideDailyAtr(String book, Position p, double atrPct) {
        if (!Double.isNaN(atrPct)) dailyAtr.put(book + ":" + p.venue() + ":" + p.symbol(), atrPct);
    }

    /**
     * 现行规则本轮不卖时调用（在组合锁内，不联网）。
     *
     * @param d         现行规则的判断（HOLD）；盘口异常、成交不完整时不判断
     * @param hourClose 最近一根已收盘 1h K 线收盘价，缺失为 NaN（不判断）
     * @return 要卖出时的原因（STOP_LOSS / STALE_EXIT），否则 null
     */
    public String check(String book, Position pos, ExitDecision d, double hourClose, long hourOpen, Instant now) {
        if (!enabled || d.sell() || !"HOLD".equals(d.reason()) || d.notes().contains("ABNORMAL_BOOK_HOLD")
                || d.fill() == null || !d.fill().complete() || Double.isNaN(hourClose)) {
            return null;
        }
        String v = pos.venue().name(), sym = pos.symbol(), o = pos.openedAt().toString();
        Double atr = dailyAtr.remove(book + ":" + pos.venue() + ":" + sym);
        Double dist = atr == null ? null : AtrStaleExit.distancePct(atr);
        jdbc.sql("""
                INSERT OR IGNORE INTO loss_guard(book, venue, symbol, opened_at, dist_pct) VALUES (:b, :v, :s, :o, :d)
                """).param("b", book).param("v", v).param("s", sym).param("o", o).param("d", dist).update();
        if (dist != null) {
            jdbc.sql("""
                    UPDATE loss_guard SET dist_pct = :d WHERE book = :b AND venue = :v AND symbol = :s AND opened_at = :o
                        AND dist_pct IS NULL
                    """).param("d", dist).param("b", book).param("v", v).param("s", sym).param("o", o).update();
        }
        AtrStaleExit.State st = jdbc.sql("""
                SELECT dist_pct, wait_since, prev_close, rose, last_hour FROM loss_guard
                WHERE book = :b AND venue = :v AND symbol = :s AND opened_at = :o
                """).param("b", book).param("v", v).param("s", sym).param("o", o)
                .query((rs, i) -> new AtrStaleExit.State((Double) rs.getObject(1),
                        rs.getString(2) == null ? null : Instant.parse(rs.getString(2)), (Double) rs.getObject(3),
                        rs.getInt(4) != 0, rs.getObject(5) == null ? null : rs.getLong(5)))
                .single();
        AtrStaleExit.Step step = AtrStaleExit.step(st, pos.openedAt(), now, d.updated().protectArmed(),
                pos.avgEntryPrice().doubleValue(), hourClose, hourOpen, d.netPnl().doubleValue());
        if (step.exit() != null) {
            return step.exit().startsWith("X1_STALE") ? STALE_EXIT : STOP_LOSS;
        }
        AtrStaleExit.State n = step.next();
        jdbc.sql("""
                UPDATE loss_guard SET wait_since = :ws, prev_close = :pc, rose = :ro, last_hour = :lh
                WHERE book = :b AND venue = :v AND symbol = :s AND opened_at = :o
                """).param("ws", n.waitSince() == null ? null : n.waitSince().toString()).param("pc", n.prevClose())
                .param("ro", n.rose() ? 1 : 0).param("lh", n.lastHour()).param("b", book).param("v", v)
                .param("s", sym).param("o", o).update();
        return null;
    }

    /** 日线 ATR14%（新持仓的止损距离用），失败返回 NaN（按默认距离）。联网，锁外调用。 */
    public static double dailyAtrPct(com.trendpilot.market.VenueMarket m, String symbol, Instant now) {
        try {
            long t = now.toEpochMilli();
            List<double[]> daily = m.source().klines(symbol, "1d", null, 16).stream()
                    .filter(k -> k.closeTime() < t).map(k -> new double[]{k.high(), k.low(), k.close()}).toList();
            double price = daily.isEmpty() ? Double.NaN : daily.getLast()[2];
            return AtrStaleExit.atrDailyPct(daily, price);
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    /** 规则判断为卖出时，把现行规则的判断改成止损卖出（同一份盘口成交）。 */
    public static ExitDecision asExit(ExitDecision d, String reason) {
        return new ExitDecision(d.symbol(), true, reason, d.updated(), d.notes(), d.netPnl(), d.rawReturnPct(), d.fill());
    }
}
