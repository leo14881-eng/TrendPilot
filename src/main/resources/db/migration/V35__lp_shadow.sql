-- LP 影子盘（Uniswap V3 做市 + 合约对冲，只读影子，见 docs/规则说明.md "LP 影子盘"）：独立的表，不和其他账本共用
-- 池子：启动时由 Factory.getPool 解析并校验；status = ACTIVE / REJECTED（校验不通过，停用）/ PENDING（还没解析成功）
CREATE TABLE lp_pool (
    pool         TEXT    PRIMARY KEY,     -- base-030 / base-005 / arb-005
    chain        TEXT    NOT NULL,
    fee          INTEGER NOT NULL,        -- 500 = 0.05%
    address      TEXT,
    token0       TEXT,
    token1       TEXT,
    dec0         INTEGER,
    dec1         INTEGER,
    weth_is_token0 INTEGER,
    tick_spacing INTEGER,
    status       TEXT    NOT NULL,
    note         TEXT,
    checked_at   TEXT    NOT NULL
);

-- 每分钟一行 / 池子。uint256 / uint160 / uint128 按十进制字符串存；status = OK / GAP（note 写原因）
CREATE TABLE lp_pool_snapshot (
    ts             INTEGER NOT NULL,      -- 采样分钟，UTC epoch 秒
    pool           TEXT    NOT NULL,
    sqrt_price_x96 TEXT,
    tick           INTEGER,
    liquidity      TEXT,
    fg0            TEXT,
    fg1            TEXT,
    gas_price_wei  TEXT,
    perp_mark      REAL,                  -- Binance ETHUSDT 永续标记价格
    funding_rate   REAL,                  -- 同上，lastFundingRate（小数）
    status         TEXT    NOT NULL,
    note           TEXT,
    PRIMARY KEY (pool, ts)
);
CREATE INDEX lp_pool_snapshot_ts ON lp_pool_snapshot(ts);

-- 每小时一行 / 池子：两个代币的 balanceOf(pool)，用于 TVL 和集中度 m_avg = L_active × 2√P / TVL
CREATE TABLE lp_pool_tvl (
    ts             INTEGER NOT NULL,      -- 整点，UTC epoch 秒
    pool           TEXT    NOT NULL,
    bal0           TEXT    NOT NULL,
    bal1           TEXT    NOT NULL,
    sqrt_price_x96 TEXT    NOT NULL,
    liquidity      TEXT    NOT NULL,
    tvl_usdc       REAL    NOT NULL,
    m              REAL    NOT NULL,
    PRIMARY KEY (pool, ts)
);
