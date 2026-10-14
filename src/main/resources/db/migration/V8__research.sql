-- 研究资料缓存：身份（CoinGecko 交易所交易对 → coin_id）、市值与供给、DefiLlama 价值捕获
CREATE TABLE coin_identity (
    venue       TEXT NOT NULL,
    symbol      TEXT NOT NULL,
    coin_id     TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    PRIMARY KEY (venue, symbol, coin_id)
);

CREATE TABLE coin_market (
    coin_id     TEXT PRIMARY KEY,
    symbol      TEXT,
    market_cap  REAL,
    fdv         REAL,
    circulating REAL,
    total       REAL,
    max_supply  REAL,
    updated_at  TEXT NOT NULL
);

CREATE TABLE protocol_value (
    gecko_id    TEXT PRIMARY KEY,
    name        TEXT,
    category    TEXT,
    tvl         REAL,
    fees_24h    REAL,
    revenue_24h REAL,
    updated_at  TEXT NOT NULL
);

-- 每轮研究结论；capital_ready 永远为 0（长期研究不直接给买单）
CREATE TABLE research_record (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    generation   TEXT NOT NULL,
    venue        TEXT NOT NULL,
    symbol       TEXT NOT NULL,
    source       TEXT NOT NULL,      -- ROTATION / EARLY / MOVER
    coin_id      TEXT,
    identity     TEXT NOT NULL,      -- VERIFIED / AMBIGUOUS / UNVERIFIED / DATA_UNAVAILABLE
    market_cap   REAL,
    fdv          REAL,
    circ_ratio   REAL,
    tvl          REAL,
    fees_24h     REAL,
    revenue_24h  REAL,
    labels       TEXT,
    gaps         TEXT,
    verdict      TEXT NOT NULL,      -- USER_REVIEW_REQUIRED / RESEARCH_GAPS / IDENTITY_PROBLEM
    capital_ready INTEGER NOT NULL DEFAULT 0,
    created_at   TEXT NOT NULL
);
CREATE INDEX idx_research_symbol ON research_record(symbol, created_at);
