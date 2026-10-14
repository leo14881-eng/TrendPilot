-- 研究资料单项失败退避，重启后仍然有效，避免启动时集中重试
CREATE TABLE research_backoff (
    item     TEXT PRIMARY KEY,
    failures INTEGER NOT NULL,
    retry_at TEXT NOT NULL
);
