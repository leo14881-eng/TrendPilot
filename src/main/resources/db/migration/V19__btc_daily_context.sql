-- BTC 每日短线：市场与宏观过滤
ALTER TABLE btc_daily_plan ADD COLUMN skip_new INTEGER NOT NULL DEFAULT 0;
ALTER TABLE btc_daily_plan ADD COLUMN multiplier REAL NOT NULL DEFAULT 1;
ALTER TABLE btc_daily_plan ADD COLUMN reasons TEXT NOT NULL DEFAULT '';

-- 按日的外部数据（资金费率、恐惧贪婪、ETF 流、FRED 序列、事件日），实盘与回测共用
CREATE TABLE btc_daily_series (
    source TEXT NOT NULL,
    day    TEXT NOT NULL,
    value  REAL NOT NULL,
    PRIMARY KEY (source, day)
);

-- 各数据源最近一次请求（成功才算刷新；失败后退避，避免反复请求）
CREATE TABLE btc_daily_fetch (
    source     TEXT PRIMARY KEY,
    last_ok    TEXT,
    last_fail  TEXT,
    error      TEXT
);
