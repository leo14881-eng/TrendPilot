-- BTC 均线趋势（4 条均线 × 4 份，用户 2026-10-23 交接）：影子账本，单独 20,000 U 资金，规则见 docs/BTC均线趋势.md
CREATE TABLE btc_trend_slot (
    sma             INTEGER PRIMARY KEY,     -- 75 / 100 / 125 / 150
    state           TEXT    NOT NULL,        -- USDT / BTC
    usdt            REAL    NOT NULL,
    btc             REAL    NOT NULL,
    cost            REAL    NOT NULL,        -- 持有 BTC 时这一轮买入花的 USDT；持有 USDT 时为 0
    last_signal_day TEXT,                    -- 最近一次已处理的信号日（UTC 日线日期），保证同一天不重复成交
    updated_at      TEXT    NOT NULL
);

CREATE TABLE btc_trend_trade (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    signal_day  TEXT    NOT NULL,
    exec_day    TEXT    NOT NULL,
    sma         INTEGER NOT NULL,
    side        TEXT    NOT NULL,            -- BUY / SELL
    price       REAL    NOT NULL,
    usdt        REAL    NOT NULL,            -- 买入花费 / 卖出到手
    btc         REAL    NOT NULL,
    fee         REAL    NOT NULL,
    mode        TEXT    NOT NULL DEFAULT 'SHADOW',
    created_at  TEXT    NOT NULL,
    UNIQUE (signal_day, sma)
);

-- 账本级别的固定信息：启动日、"一直拿着"基准（启动日把 20,000 U 全部买成 BTC 的数量）
CREATE TABLE btc_trend_meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
