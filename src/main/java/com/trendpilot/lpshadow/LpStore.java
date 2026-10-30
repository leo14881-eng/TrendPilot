package com.trendpilot.lpshadow;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** LP 影子盘的表（lp_*），不读写任何其他账本的表。 */
@Component
public class LpStore {

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public LpStore(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    // ---------- 池子 ----------

    public void savePool(String key, String chain, int fee, PoolInfo info, String status, String note, Instant now) {
        jdbc.sql("""
                INSERT INTO lp_pool(pool, chain, fee, address, token0, token1, dec0, dec1, weth_is_token0, tick_spacing, status, note, checked_at)
                VALUES (:k, :c, :f, :a, :t0, :t1, :d0, :d1, :w, :ts, :s, :n, :now)
                ON CONFLICT(pool) DO UPDATE SET chain = excluded.chain, fee = excluded.fee, address = excluded.address,
                    token0 = excluded.token0, token1 = excluded.token1, dec0 = excluded.dec0, dec1 = excluded.dec1,
                    weth_is_token0 = excluded.weth_is_token0, tick_spacing = excluded.tick_spacing, status = excluded.status,
                    note = excluded.note, checked_at = excluded.checked_at
                """).param("k", key).param("c", chain).param("f", fee)
                .param("a", info == null ? null : info.address()).param("t0", info == null ? null : info.token0())
                .param("t1", info == null ? null : info.token1()).param("d0", info == null ? null : info.dec0())
                .param("d1", info == null ? null : info.dec1())
                .param("w", info == null ? null : info.wethIsToken0() ? 1 : 0)
                .param("ts", info == null ? null : info.tickSpacing()).param("s", status).param("n", note)
                .param("now", now.toString()).update();
    }

    public List<PoolInfo> activePools() {
        return jdbc.sql("""
                SELECT pool, chain, fee, address, token0, token1, dec0, dec1, weth_is_token0, tick_spacing
                FROM lp_pool WHERE status = 'ACTIVE' ORDER BY pool
                """).query((rs, n) -> new PoolInfo(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getInt(9) == 1, rs.getInt(10))).list();
    }

    // ---------- 快照 ----------

    /** 一分钟的快照一次写入（一个批量语句，只占用数据库连接很短时间）。 */
    public void insertSnapshots(List<Snapshot> rows) {
        if (rows.isEmpty()) return;
        List<Object[]> args = rows.stream().map(s -> new Object[] {s.ts(), s.pool(), str(s.sqrtPriceX96()), s.tick(),
                str(s.liquidity()), str(s.fg0()), str(s.fg1()), str(s.gasPriceWei()), s.perpMark(), s.fundingRate(), s.status(),
                s.note()}).toList();
        template.batchUpdate("""
                INSERT OR IGNORE INTO lp_pool_snapshot(ts, pool, sqrt_price_x96, tick, liquidity, fg0, fg1, gas_price_wei,
                    perp_mark, funding_rate, status, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, args);
    }

    /** [fromTs, toTs) 内某池子的快照，按时间升序。 */
    public List<Snapshot> snapshots(String pool, long fromTs, long toTs) {
        return jdbc.sql("""
                SELECT ts, pool, sqrt_price_x96, tick, liquidity, fg0, fg1, gas_price_wei, perp_mark, funding_rate, status, note
                FROM lp_pool_snapshot WHERE pool = :p AND ts >= :a AND ts < :b ORDER BY ts
                """).param("p", pool).param("a", fromTs).param("b", toTs)
                .query((rs, n) -> {
                    Object tick = rs.getObject(4);
                    return new Snapshot(rs.getLong(1), rs.getString(2), big(rs.getString(3)),
                            tick == null ? null : ((Number) tick).intValue(), big(rs.getString(5)), big(rs.getString(6)),
                            big(rs.getString(7)), big(rs.getString(8)), dbl(rs.getObject(9)), dbl(rs.getObject(10)),
                            rs.getString(11), rs.getString(12));
                })
                .list();
    }

    /** [fromTs, toTs) 内每个池子 OK 快照数（池子数据齐全）和 OK 且有永续报价的快照数。 */
    public Map<String, long[]> okCounts(long fromTs, long toTs) {
        Map<String, long[]> out = new TreeMap<>();
        jdbc.sql("""
                SELECT pool, SUM(status = 'OK'), SUM(status = 'OK' AND perp_mark IS NOT NULL AND funding_rate IS NOT NULL)
                FROM lp_pool_snapshot WHERE ts >= :a AND ts < :b GROUP BY pool
                """).param("a", fromTs).param("b", toTs)
                .query(rs -> {
                    out.put(rs.getString(1), new long[] {rs.getLong(2), rs.getLong(3)});
                });
        return out;
    }

    /** 删除 ts 之前的原始快照（日报汇总之后调用，保留最近 90 天）。 */
    public int pruneSnapshots(long beforeTs) {
        return jdbc.sql("DELETE FROM lp_pool_snapshot WHERE ts < :t").param("t", beforeTs).update();
    }

    // ---------- TVL ----------

    public boolean hasTvl(String pool, long hourTs) {
        return jdbc.sql("SELECT COUNT(*) FROM lp_pool_tvl WHERE pool = :p AND ts = :t").param("p", pool).param("t", hourTs)
                .query(Long.class).single() > 0;
    }

    public void insertTvl(String pool, long hourTs, BigInteger bal0, BigInteger bal1, BigInteger sqrtPriceX96, BigInteger liquidity,
                          double tvlUsdc, double m) {
        jdbc.sql("""
                INSERT OR IGNORE INTO lp_pool_tvl(ts, pool, bal0, bal1, sqrt_price_x96, liquidity, tvl_usdc, m)
                VALUES (:t, :p, :b0, :b1, :sp, :l, :tvl, :m)
                """).param("t", hourTs).param("p", pool).param("b0", bal0.toString()).param("b1", bal1.toString())
                .param("sp", sqrtPriceX96.toString()).param("l", liquidity.toString()).param("tvl", tvlUsdc).param("m", m).update();
    }

    /** [fromTs, toTs) 内每个池子的平均 m、平均 TVL 和样本数。 */
    public Map<String, double[]> mAvg(long fromTs, long toTs) {
        Map<String, double[]> out = new TreeMap<>();
        jdbc.sql("SELECT pool, AVG(m), AVG(tvl_usdc), COUNT(*) FROM lp_pool_tvl WHERE ts >= :a AND ts < :b GROUP BY pool")
                .param("a", fromTs).param("b", toTs)
                .query(rs -> {
                    out.put(rs.getString(1), new double[] {rs.getDouble(2), rs.getDouble(3), rs.getLong(4)});
                });
        return out;
    }

    static String str(BigInteger v) {
        return v == null ? null : v.toString();
    }

    static BigInteger big(String s) {
        return s == null ? null : new BigInteger(s);
    }

    static Double dbl(Object o) {
        return o == null ? null : ((Number) o).doubleValue();
    }
}
