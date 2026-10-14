-- CoinGecko 分类（稳定币、代币化股票等），用于从标的全集中排除
CREATE TABLE coin_category (
    coin_id     TEXT NOT NULL,
    category    TEXT NOT NULL,
    updated_at  TEXT NOT NULL,
    PRIMARY KEY (coin_id, category)
);
