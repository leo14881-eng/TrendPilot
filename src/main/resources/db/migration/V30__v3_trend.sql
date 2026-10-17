-- V3 趋势跟踪影子账本（2026-10-17 用户要求，先跑 1 个月）：持仓表结构同 V2，另存每笔的止损参数
CREATE TABLE v3_position (
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
);

CREATE TABLE v3_state (
    symbol      TEXT NOT NULL,
    opened_at   TEXT NOT NULL,
    entry_price REAL NOT NULL,
    atr         REAL NOT NULL,
    init_stop   REAL NOT NULL,
    peak_high   REAL NOT NULL,
    last_hour   INTEGER,
    PRIMARY KEY (symbol, opened_at)
);
