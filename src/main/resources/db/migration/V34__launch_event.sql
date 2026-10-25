-- 秒级启动探测（2026-10-26，只观察记录，不买卖）：WebSocket 15m K 线每 1～2 秒的实时推送里，
-- 5 分钟涨幅和成交额放大同时达到最低一档时记一条；后来 1h / 4h / 24h 的最高涨幅和收益由每小时的补算填上。
CREATE TABLE launch_event (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    venue           TEXT    NOT NULL,
    symbol          TEXT    NOT NULL,
    at              TEXT    NOT NULL,
    price           REAL    NOT NULL,
    ret1m           REAL,
    ret5m           REAL    NOT NULL,
    ret15m          REAL,
    vol_ratio       REAL    NOT NULL,     -- 最近 5 分钟成交额 / 之前 55 分钟每 5 分钟平均
    had_signal      INTEGER NOT NULL,     -- 之前 2 小时 Discovery 是否已有这个币的 EARLY 记录
    signal_after_min REAL,                -- 之后 24 小时内 Discovery 第一次记到 EARLY 比启动晚多少分钟（没有为空）
    max_up_1h REAL, max_up_4h REAL, max_up_24h REAL,
    ret_1h REAL, ret_4h REAL, ret_24h REAL,
    base_symbol     TEXT,                 -- 随机基准：同交易所、同时刻随机一个有 K 线的币
    base_ret_24h    REAL,
    base_max_up_24h REAL,
    outcome         TEXT    NOT NULL DEFAULT 'PENDING'   -- PENDING / DONE / MISSING（K 线不够，不补造）
);
CREATE INDEX idx_launch_event_at ON launch_event(at);
CREATE INDEX idx_launch_event_outcome ON launch_event(outcome);
