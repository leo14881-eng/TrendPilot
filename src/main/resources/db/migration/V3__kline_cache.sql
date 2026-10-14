-- 15 分钟 K 线本地缓存（只存已收盘的），1h K 线由它合成；只增量拉取新 K 线
CREATE TABLE kline_15m (
    symbol        TEXT    NOT NULL,
    open_time     INTEGER NOT NULL,
    open          REAL    NOT NULL,
    high          REAL    NOT NULL,
    low           REAL    NOT NULL,
    close         REAL    NOT NULL,
    quote_volume  REAL    NOT NULL,
    close_time    INTEGER NOT NULL,
    PRIMARY KEY (symbol, open_time)
);
