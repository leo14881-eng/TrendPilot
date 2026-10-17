-- 山寨止损（2026-10-17 用户要求：BTC 不止损，其他币必须止损）：每个持仓的止损距离和"3 天不赚钱等反弹"状态
CREATE TABLE loss_guard (
    book        TEXT NOT NULL,
    venue       TEXT NOT NULL,
    symbol      TEXT NOT NULL,
    opened_at   TEXT NOT NULL,
    dist_pct    REAL,
    wait_since  TEXT,
    prev_close  REAL,
    rose        INTEGER NOT NULL DEFAULT 0,
    last_hour   INTEGER,
    PRIMARY KEY (book, venue, symbol, opened_at)
);
