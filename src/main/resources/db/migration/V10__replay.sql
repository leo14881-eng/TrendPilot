-- Blind Replay：历史 EARLY 信号的前向收益（只读已缓存的 15m K 线，不补造数据）
CREATE TABLE replay_outcome (
    signal_id   INTEGER NOT NULL,
    horizon_h   INTEGER NOT NULL,
    venue       TEXT NOT NULL,
    symbol      TEXT NOT NULL,
    score       REAL,
    return_pct  REAL NOT NULL,
    max_up_pct  REAL NOT NULL,
    created_at  TEXT NOT NULL,
    PRIMARY KEY (signal_id, horizon_h)
);

-- Missed Replay：每日涨幅榜复盘，何时发现、为何未买
CREATE TABLE missed_replay (
    day              TEXT NOT NULL,
    venue            TEXT NOT NULL,
    symbol           TEXT NOT NULL,
    change_pct       REAL NOT NULL,
    first_early_at   TEXT,
    first_early_price REAL,
    outcome          TEXT NOT NULL,
    reasons          TEXT NOT NULL,
    created_at       TEXT NOT NULL,
    PRIMARY KEY (day, venue, symbol)
);
