-- BTC 每日短线：独立影子账本（不和 V1/V2 混用）
CREATE TABLE btc_daily_plan (
    day        TEXT PRIMARY KEY,   -- UTC 日（越南时间当天 07:00 起）
    version    TEXT NOT NULL,
    open       REAL NOT NULL,
    atr        REAL NOT NULL,
    ema        REAL NOT NULL,
    prev_close REAL NOT NULL,
    strong     INTEGER NOT NULL,
    carried    INTEGER NOT NULL,
    tp_pct     REAL NOT NULL,
    levels     TEXT NOT NULL,      -- index:price:usdt;...
    created_at TEXT NOT NULL
);

CREATE TABLE btc_daily_state (
    id                    INTEGER PRIMARY KEY CHECK (id = 1),
    qty                   REAL NOT NULL,
    cost                  REAL NOT NULL,
    buys                  INTEGER NOT NULL,
    last_buy_price        REAL NOT NULL,
    opened_day            TEXT,
    realized              REAL NOT NULL,
    rounds                INTEGER NOT NULL,
    wins                  INTEGER NOT NULL,
    last_exit_day         TEXT,
    round_max_float_loss  REAL NOT NULL,
    max_float_loss        REAL NOT NULL,
    last_bar_open         INTEGER,     -- 已处理到的 15m K 线开盘时间
    updated_at            TEXT NOT NULL
);

CREATE TABLE btc_daily_trade (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    day        TEXT NOT NULL,
    bar_open   INTEGER NOT NULL,
    side       TEXT NOT NULL,
    level      INTEGER NOT NULL,
    price      REAL NOT NULL,
    qty        REAL NOT NULL,
    usdt       REAL NOT NULL,
    fee        REAL NOT NULL,
    pnl        REAL NOT NULL,
    reason     TEXT NOT NULL,
    version    TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (bar_open, side, level)
);

CREATE TABLE btc_daily_backtest (
    version TEXT PRIMARY KEY,
    run_at  TEXT NOT NULL,
    result  TEXT NOT NULL        -- JSON
);
