-- 任务运行记录：程序是否成功 与 数据是否完整 分开记录
CREATE TABLE job_run (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    job            TEXT    NOT NULL,
    generation     TEXT,
    started_at     TEXT    NOT NULL,
    finished_at    TEXT,
    program_status TEXT    NOT NULL,   -- SUCCESS / FAILED
    data_status    TEXT    NOT NULL,   -- COMPLETE / PARTIAL_DATA / N/A
    note           TEXT
);

-- V2 影子账本事件，只追加；持仓和资金池由事件计算
CREATE TABLE shadow_event (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id        TEXT    NOT NULL UNIQUE,
    book            TEXT    NOT NULL,  -- V2
    symbol          TEXT    NOT NULL,
    type            TEXT    NOT NULL,  -- BUY / ADD / SELL / PROTECT_ARM ...
    quantity        TEXT,              -- 十进制字符串，避免浮点误差
    price           TEXT,
    notional_usdt   TEXT,
    fee_usdt        TEXT,
    reason_code     TEXT    NOT NULL,
    generation      TEXT,
    params_version  TEXT    NOT NULL,
    created_at      TEXT    NOT NULL
);
CREATE INDEX idx_shadow_event_symbol ON shadow_event(book, symbol);

-- 通知 Outbox：PREPARED 只表示已准备，SENT 才表示已发出
CREATE TABLE notification_outbox (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    event_key   TEXT    NOT NULL UNIQUE,
    channel     TEXT    NOT NULL,
    text        TEXT    NOT NULL,
    status      TEXT    NOT NULL,      -- PREPARED / SENT / FAILED
    last_error  TEXT,
    created_at  TEXT    NOT NULL,
    updated_at  TEXT
);
