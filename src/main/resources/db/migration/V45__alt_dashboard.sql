-- 山寨季仪表盘（2026-11-08，只显示信息，不交易）：每天一份，越南时间早上随山寨季指标一起生成
CREATE TABLE alt_dashboard_daily (
    day         TEXT PRIMARY KEY,        -- UTC 日期（刚收盘的那一天）
    lit         INTEGER,                 -- 5 个前兆指标亮了几个（缺数据的不算）
    known       INTEGER NOT NULL,        -- 有数据可判断的指标数
    s2          INTEGER,                 -- S2 开关：1 开 / 0 关 / NULL 缺数据
    body        TEXT NOT NULL,           -- 日报这一节的 Markdown 全文
    created_at  TEXT NOT NULL
);

-- DefiLlama 稳定币总供应（所有币种合计美元值），按 UTC 日；每天最多请求 1～2 次
CREATE TABLE stablecoin_supply (
    day         TEXT PRIMARY KEY,        -- UTC 日期（DefiLlama 当天 00:00 的值）
    total_usd   REAL NOT NULL,
    updated_at  TEXT NOT NULL
);

-- CoinGecko 板块分类（只给仪表盘的"最强板块"用，不参与标的排除；排除仍只看 coin_category）
CREATE TABLE coin_sector (
    coin_id     TEXT NOT NULL,
    sector      TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    PRIMARY KEY (coin_id, sector)
);
