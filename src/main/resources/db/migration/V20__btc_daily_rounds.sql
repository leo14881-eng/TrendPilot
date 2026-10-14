-- BTC 每日短线：完整记录（每笔成交的当天过滤与时间、每轮持仓时长与最大浮亏/浮盈、候选止损假设结果）
ALTER TABLE btc_daily_trade ADD COLUMN filled_at TEXT;
ALTER TABLE btc_daily_trade ADD COLUMN filters TEXT NOT NULL DEFAULT '';

ALTER TABLE btc_daily_state ADD COLUMN opened_at INTEGER NOT NULL DEFAULT 0;
ALTER TABLE btc_daily_state ADD COLUMN mae_pct REAL NOT NULL DEFAULT 0;
ALTER TABLE btc_daily_state ADD COLUMN mfe_pct REAL NOT NULL DEFAULT 0;
ALTER TABLE btc_daily_state ADD COLUMN open_filters TEXT NOT NULL DEFAULT '';
ALTER TABLE btc_daily_state ADD COLUMN stop_hits TEXT NOT NULL DEFAULT '';

CREATE TABLE btc_daily_round (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    version       TEXT NOT NULL,
    opened_day    TEXT NOT NULL,
    opened_at     TEXT NOT NULL,
    closed_at     TEXT NOT NULL,
    hold_minutes  INTEGER NOT NULL,
    buys          INTEGER NOT NULL,
    cost          REAL NOT NULL,
    proceeds      REAL NOT NULL,
    pnl           REAL NOT NULL,
    pnl_pct       REAL NOT NULL,
    mae_pct       REAL NOT NULL,   -- 持仓期间相对均价的最大不利幅度（%，负数）
    mfe_pct       REAL NOT NULL,   -- 最大有利幅度（%）
    exit_reason   TEXT NOT NULL,
    open_filters  TEXT NOT NULL,   -- 开仓当天的市场 / 宏观过滤
    stop_hits     TEXT NOT NULL,   -- 候选止损首次触发：pct:时间:价格:假设盈亏;...
    UNIQUE (opened_at, closed_at)
);
