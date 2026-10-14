-- 论点观察按小时 K 线代次计数；权益曲线（已实现 + 浮动）用于最大回撤
ALTER TABLE position ADD COLUMN last_obs_hour INTEGER;
ALTER TABLE v1_position ADD COLUMN last_obs_hour INTEGER;
CREATE TABLE equity_curve (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    book       TEXT NOT NULL,
    at         TEXT NOT NULL,
    realized   REAL NOT NULL,
    unrealized REAL NOT NULL
);
CREATE INDEX idx_equity_curve_book ON equity_curve(book, id);
