-- 配置化影子变体（V5、V6、V7…）的审核和成交记录（周复盘 2026-11-01 R2）：
-- kind = REVIEW（每个候选每轮一条：BUY 通过并成交 / BUDGET_WAIT 通过但额度不够 / REJECT 拒绝，reasons 写原因）
--        FILL_BUY / FILL_SELL（每次实际模拟成交一条，slippage_bps = 这笔成交相对最优价的滑点）
-- expected_price = 按审核盘口模拟的成交均价；ref_close = 上一根已收盘 1h K 线收盘价；dev_bps = 两者偏差
CREATE TABLE variant_decision (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    book           TEXT    NOT NULL,
    generation     TEXT    NOT NULL,
    venue          TEXT,
    symbol         TEXT    NOT NULL,
    kind           TEXT    NOT NULL,
    outcome        TEXT    NOT NULL,
    reasons        TEXT    NOT NULL DEFAULT '',
    score          REAL,
    spread_bps     REAL,
    slippage_bps   REAL,
    bid_depth      REAL,
    ask_depth      REAL,
    best_bid       REAL,
    best_ask       REAL,
    book_at        TEXT,
    expected_price REAL,
    ref_close      REAL,
    dev_bps        REAL,
    notional       REAL,
    created_at     TEXT    NOT NULL
);
CREATE INDEX variant_decision_book_at ON variant_decision(book, created_at);
