-- 上涨逻辑失效时亏损也卖、选反弹高点（2026-10-17 用户要求）：每个 LOSS_RECOVERY 持仓的等待状态
CREATE TABLE decay_wait (
    book        TEXT NOT NULL,
    venue       TEXT NOT NULL,
    symbol      TEXT NOT NULL,
    opened_at   TEXT NOT NULL,
    wait_since  TEXT,
    prev_close  REAL,
    rose        INTEGER NOT NULL DEFAULT 0,
    last_hour   INTEGER,
    PRIMARY KEY (book, venue, symbol, opened_at)
);
