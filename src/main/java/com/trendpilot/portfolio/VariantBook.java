package com.trendpilot.portfolio;

import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import com.trendpilot.config.StrategyParams;
import com.trendpilot.market.ExecutionSimulator.Fill;
import com.trendpilot.market.Venue;

/**
 * 配置化影子变体的账本（V6、V7…）：每个变体一张自己的持仓表（结构与 v5_position 相同），事件写 shadow_event（book = 变体名）。
 * 与 V2 相同的审核和退出规则，只叠加变体自己的参数改动；每币一笔、不加仓、不占 V2 资金。不是 Spring bean，由 VariantRegistry 创建。
 */
public class VariantBook extends Ledger {

    /** 不是 Spring bean，Ledger 上的 @Transactional 不生效：开仓 / 加仓 / 平仓用它包成一个事务（null = 测试里不用事务）。 */
    private final TransactionTemplate tx;

    public VariantBook(JdbcClient jdbc, StrategyParams params, String book, String table) {
        this(jdbc, params, book, table, null);
    }

    public VariantBook(JdbcClient jdbc, StrategyParams params, String book, String table, TransactionTemplate tx) {
        super(jdbc, params, book, table);
        this.tx = tx;
    }

    @Override
    public Position open(Venue venue, String symbol, Fill fill, String generation, Instant now) {
        return tx == null ? super.open(venue, symbol, fill, generation, now)
                : tx.execute(st -> super.open(venue, symbol, fill, generation, now));
    }

    @Override
    public Position add(Position cur, Fill fill, String generation, Instant now) {
        return tx == null ? super.add(cur, fill, generation, now) : tx.execute(st -> super.add(cur, fill, generation, now));
    }

    @Override
    public void close(Position cur, Fill fill, java.math.BigDecimal pnl, String reason, String generation, Instant now) {
        if (tx == null) {
            super.close(cur, fill, pnl, reason, generation, now);
            return;
        }
        tx.executeWithoutResult(st -> super.close(cur, fill, pnl, reason, generation, now));
    }

    /** 建持仓表（已存在就不动，已有数据保留）。表名只能来自 VariantRegistry 校验过的变体名。 */
    public static void ensureTable(JdbcClient jdbc, String table) {
        if (!table.matches("^[a-z][a-z0-9_]{1,30}$")) throw new IllegalArgumentException("持仓表名不合法：" + table);
        jdbc.sql("CREATE TABLE IF NOT EXISTS " + table + """
                 (
                    symbol              TEXT PRIMARY KEY,
                    venue               TEXT    NOT NULL DEFAULT 'BINANCE',
                    qty                 TEXT    NOT NULL,
                    cost_basis          TEXT    NOT NULL,
                    entry_notional      TEXT    NOT NULL,
                    tranches            INTEGER NOT NULL,
                    last_tranche_price  TEXT    NOT NULL,
                    last_signal_at      TEXT    NOT NULL,
                    last_generation     TEXT    NOT NULL,
                    opened_at           TEXT    NOT NULL,
                    protect_armed       INTEGER NOT NULL DEFAULT 0,
                    protect_floor       TEXT    NOT NULL DEFAULT '0',
                    peak_net            TEXT    NOT NULL DEFAULT '0',
                    weak_streak         INTEGER NOT NULL DEFAULT 0,
                    loss_recovery       INTEGER NOT NULL DEFAULT 0,
                    updated_at          TEXT    NOT NULL,
                    mark_net            TEXT    NOT NULL DEFAULT '0',
                    max_net             REAL,
                    min_net             REAL,
                    first_profit_at     TEXT,
                    last_obs_hour       INTEGER,
                    underwater_since    TEXT
                )""").update();
    }
}
