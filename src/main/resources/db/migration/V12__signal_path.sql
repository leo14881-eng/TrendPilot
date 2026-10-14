-- EARLY 信号之后 24 小时的价格路径（每个信号段的第一个 EARLY），只用已缓存的 15m K 线
CREATE TABLE signal_path (
    signal_id          INTEGER PRIMARY KEY,
    venue              TEXT NOT NULL,
    symbol             TEXT NOT NULL,
    score              REAL,
    signal_at          TEXT NOT NULL,
    price              REAL NOT NULL,
    minutes_to_profit  INTEGER,      -- 最高价首次超过 信号价 + 往返手续费
    minutes_to_10      INTEGER,      -- 首次涨到 +10%
    minutes_to_20      INTEGER,      -- 首次涨到 +20%
    peak_pct           REAL NOT NULL,-- 24h 内最高价涨幅
    minutes_to_peak    INTEGER NOT NULL,
    drawdown_before_peak_pct REAL NOT NULL, -- 到达高点之前的最低价跌幅（≤0）
    return_24h_pct     REAL NOT NULL,
    created_at         TEXT NOT NULL
);
CREATE INDEX idx_signal_path_at ON signal_path(signal_at);
