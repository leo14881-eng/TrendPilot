-- 代币解锁（DefiLlama 公开数据集 emissionsIndex，每天刷新）
CREATE TABLE unlock_schedule (
    gecko_id        TEXT PRIMARY KEY,
    next_unlock_at  TEXT,
    next_unlock_pct REAL,
    unlock_30d_pct  REAL NOT NULL,
    circulating     REAL,
    max_supply      REAL,
    updated_at      TEXT NOT NULL
);
