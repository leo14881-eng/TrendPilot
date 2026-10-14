ALTER TABLE shadow_event ADD COLUMN pnl_usdt TEXT;   -- 仅 SELL 有值

-- 当前 V2 持仓（可变状态）；历史以 shadow_event 为准
CREATE TABLE position (
    symbol              TEXT PRIMARY KEY,
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

-- 首次进入 EARLY 的价格，用于追涨限制
CREATE TABLE early_seen (
    symbol        TEXT PRIMARY KEY,
    first_price   TEXT NOT NULL,
    first_seen_at TEXT NOT NULL,
    last_seen_at  TEXT NOT NULL
);

CREATE TABLE signal_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    generation  TEXT NOT NULL,
    symbol      TEXT NOT NULL,
    price       REAL,
    score       REAL,
    hits        TEXT,
    rel1h       REAL,
    rel4h       REAL,
    accel       REAL,
    volume_accel REAL,
    compression REAL,
    created_at  TEXT NOT NULL
);
CREATE INDEX idx_signal_log_gen ON signal_log(generation);

CREATE TABLE decision_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    generation  TEXT NOT NULL,
    symbol      TEXT NOT NULL,
    action      TEXT NOT NULL,
    decision    TEXT NOT NULL,
    funded      INTEGER NOT NULL DEFAULT 0,
    score       REAL,
    rr          REAL,
    spread_bps  REAL,
    slippage_bps REAL,
    reasons     TEXT,
    gaps        TEXT,
    params_version TEXT NOT NULL,
    created_at  TEXT NOT NULL
);
CREATE INDEX idx_decision_log_gen ON decision_log(generation);

CREATE TABLE app_state (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
