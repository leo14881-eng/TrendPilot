-- 山寨季提醒（2026-11-02，只提醒、不交易）：每天日线收盘后一行指标和状态；只用当时已收盘的日线
CREATE TABLE alt_season_daily (
    day               TEXT PRIMARY KEY,        -- UTC 日期（刚收盘的那一天）
    regime            TEXT,
    pool              INTEGER NOT NULL,        -- 参与计算的币数
    breadth50_pct     REAL,                    -- 池内收盘在 50 日均线上方的比例 %
    breadth200_pct    REAL,                    -- 在 200 日均线上方的比例 %（只算有 200 天数据的币）
    alt30_median_pct  REAL,                    -- 池内 30 日收益中位数 %
    alt_vs_btc30_pct  REAL,                    -- 减 BTC 30 日收益（百分点）
    outperform_days   INTEGER NOT NULL,        -- 连续几天山寨 30 日跑赢 BTC
    ethbtc_vs50_pct   REAL,                    -- ETH/BTC 相对其 50 日均线 %
    ethbtc_vs100_pct  REAL,
    v3_open           INTEGER,                 -- V3 大盘过滤是否打开（BTC 在 50 日线上方且池子过半在 20 日线上方）
    state             TEXT NOT NULL,           -- OFF / EARLY / DATA_MISSING（缺数据不判断，沿用前一天状态）
    created_at        TEXT NOT NULL
);
