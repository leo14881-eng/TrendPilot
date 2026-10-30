-- LP 影子盘账本：虚拟仓位、事件、每个策略的最新状态、每日汇总（只属于 LP 影子盘，不和其他账本共用）
CREATE TABLE lp_position (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    strategy    TEXT    NOT NULL,
    pool        TEXT    NOT NULL,
    tick_lower  INTEGER NOT NULL,
    tick_upper  INTEGER NOT NULL,
    liquidity   REAL    NOT NULL,         -- 虚拟流动性 L（原始单位）
    opened_ts   INTEGER NOT NULL,         -- epoch 秒
    closed_ts   INTEGER
);
CREATE INDEX lp_position_strategy ON lp_position(strategy, opened_ts);

-- 调仓、对冲调整、资金费结算、gap、爆仓风险等
CREATE TABLE lp_event (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    ts          INTEGER NOT NULL,
    strategy    TEXT    NOT NULL,         -- 策略名；池子级别的事件（GAP）用池子名
    type        TEXT    NOT NULL,
    detail_json TEXT
);
CREATE INDEX lp_event_ts ON lp_event(ts);
CREATE INDEX lp_event_type ON lp_event(type, ts);

-- 每个策略 / 基准的最新状态（JSON），每分钟覆盖写；重启后从这里恢复
CREATE TABLE lp_state (
    strategy    TEXT    PRIMARY KEY,
    pool        TEXT    NOT NULL,
    state_json  TEXT    NOT NULL,
    updated_ts  INTEGER NOT NULL
);

-- 每日汇总（越南时间日期），都是截至当天结束的累计值；coverage = 当天有效分钟占比
CREATE TABLE lp_daily (
    date        TEXT    NOT NULL,
    strategy    TEXT    NOT NULL,
    value       REAL    NOT NULL,
    fees        REAL    NOT NULL,
    il          REAL    NOT NULL,
    gas_cost    REAL    NOT NULL,
    swap_cost   REAL    NOT NULL,
    hedge_pnl   REAL    NOT NULL,
    funding     REAL    NOT NULL,
    coverage    REAL    NOT NULL,
    PRIMARY KEY (date, strategy)
);
