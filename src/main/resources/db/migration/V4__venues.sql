-- Bybit 独立池：K 线缓存、持仓、事件、日志都区分交易所
DROP TABLE kline_15m;
CREATE TABLE kline_15m (
    venue         TEXT    NOT NULL,
    symbol        TEXT    NOT NULL,
    open_time     INTEGER NOT NULL,
    open          REAL    NOT NULL,
    high          REAL    NOT NULL,
    low           REAL    NOT NULL,
    close         REAL    NOT NULL,
    quote_volume  REAL    NOT NULL,
    close_time    INTEGER NOT NULL,
    PRIMARY KEY (venue, symbol, open_time)
);

ALTER TABLE position ADD COLUMN venue TEXT NOT NULL DEFAULT 'BINANCE';
ALTER TABLE shadow_event ADD COLUMN venue TEXT NOT NULL DEFAULT 'BINANCE';
ALTER TABLE signal_log ADD COLUMN venue TEXT NOT NULL DEFAULT 'BINANCE';
ALTER TABLE decision_log ADD COLUMN venue TEXT NOT NULL DEFAULT 'BINANCE';
