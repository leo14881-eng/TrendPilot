-- 强信号对照账本（V5，2026-10-23）：与 V2 相同的审核规则，另要求新开仓 1h 相对 BTC 收益 ≥ trendpilot.variants.strong-min-rel1h。
-- 结构与 V2D15（v2d_position）相同；事件写 shadow_event（book = V5）
CREATE TABLE v5_position (
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
