-- 配置化影子变体与自动优化（2026-10-26）。
-- 每个变体自己的持仓表 <name>_position 在注册时按 v5_position 的结构建（CREATE TABLE IF NOT EXISTS），V5 继续用原来的 v5_position，数据不动。
-- 事件照常写 shadow_event（book = 变体名），权益写 equity_curve。
CREATE TABLE variant_registry (
    name            TEXT PRIMARY KEY,
    base            TEXT    NOT NULL DEFAULT 'V2',
    position_table  TEXT    NOT NULL,
    overrides_json  TEXT    NOT NULL,          -- 规范写法的参数改动（含 trade-usdt）
    source          TEXT    NOT NULL,          -- CONFIG（application.yml）/ CANDIDATES（每周自动回测）
    backtest_passed INTEGER NOT NULL DEFAULT 0, -- 回测发布前检查是否通过（自动转正的前提之一）
    evidence        TEXT,                       -- 回测证据链接或文档
    status          TEXT    NOT NULL,          -- ACTIVE 正常买卖 / STOPPED 停止开新仓、持仓照常卖 / ARCHIVED 已归档 / PROMOTED 已转正
    started_at      TEXT    NOT NULL,          -- 同期对比的起点
    status_at       TEXT    NOT NULL,
    note            TEXT
);

-- V2 采用的参数集切换记录（自动转正、回滚）。当前参数集同时写在 app_state 'params:V2'。
CREATE TABLE param_switch (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    book        TEXT    NOT NULL,             -- 只会是影子账本（V2）
    kind        TEXT    NOT NULL,             -- PROMOTE / ROLLBACK
    variant     TEXT,
    from_json   TEXT    NOT NULL,
    to_json     TEXT    NOT NULL,
    rolled_back INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT    NOT NULL,
    note        TEXT
);

-- 每天一次的自动转正 / 淘汰评估结果（日报显示最近一次）。
CREATE TABLE variant_eval (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    day         TEXT    NOT NULL,
    variant     TEXT    NOT NULL,
    days_live   REAL    NOT NULL,
    total       REAL,
    v2_total    REAL,
    max_dd      REAL,
    v2_max_dd   REAL,
    per_trade   REAL,
    v2_per_trade REAL,
    closed      INTEGER,
    decision    TEXT    NOT NULL,             -- WAIT / CANDIDATE / PROMOTED / STOP / ARCHIVE / KEEP
    reason      TEXT,
    created_at  TEXT    NOT NULL
);
CREATE INDEX idx_variant_eval_day ON variant_eval(day);
