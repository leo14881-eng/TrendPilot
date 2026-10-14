-- 深度门槛放宽的影子对照账本（V2D15）：只记 V2 仅因盘口深度不足被拒、按 15000 门槛会买入的标的。
-- 结构与 V1 / V2 持仓相同；事件写 shadow_event（book = V2D15）
CREATE TABLE v2d_position (
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
    last_obs_hour       INTEGER
);
