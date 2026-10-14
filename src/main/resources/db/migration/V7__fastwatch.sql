-- FastWatch 快速观察：formal_writer=false，只记录观察结果，不写正式组合
CREATE TABLE fastwatch_observation (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    venue       TEXT    NOT NULL,
    symbol      TEXT    NOT NULL,
    kind        TEXT    NOT NULL,   -- WOULD_ARM / WOULD_PROTECT_SELL / GAPPED
    bid         REAL    NOT NULL,
    ask         REAL    NOT NULL,
    spread_bps  REAL    NOT NULL,
    est_net     REAL    NOT NULL,   -- 按最优买价 + 手续费估算的可退出净利（不是全数量盘口）
    raw_ret_pct REAL    NOT NULL,
    created_at  TEXT    NOT NULL
);
CREATE INDEX idx_fastwatch_symbol ON fastwatch_observation(symbol, created_at);
