-- V1 宽口径观察账本：结构与 V2 持仓相同，没有资金池；事件同样写 shadow_event（book = V1）
CREATE TABLE v1_position (
    symbol              TEXT PRIMARY KEY,
    venue               TEXT    NOT NULL DEFAULT 'BINANCE',
    qty                 TEXT    NOT NULL,
    cost_basis          TEXT    NOT NULL,   -- 含买入手续费
    entry_notional      TEXT    NOT NULL,   -- 不含手续费
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
    updated_at          TEXT    NOT NULL
);

