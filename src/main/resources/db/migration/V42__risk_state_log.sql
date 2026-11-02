-- 配置化影子变体风控状态的变化历史（周复盘 2026-11-01 R3）：状态（NORMAL / LOSS_FREEZE / LOSS_HALF_BUDGET / RECOVERING /
-- MARKET_BLOCKED）每变一次写一条；old_status 为空 = 第一次记录。当前值仍在 app_state 的 risk:<变体>:risk:status。
CREATE TABLE risk_state_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    book        TEXT NOT NULL,
    old_status  TEXT NOT NULL DEFAULT '',
    new_status  TEXT NOT NULL,
    generation  TEXT,
    at          TEXT NOT NULL
);
CREATE INDEX risk_state_log_book_at ON risk_state_log(book, at);
