-- 信号跟踪与退出策略研究（docs/信号跟踪与退出策略设计.md）。只观察，不影响交易。
DROP TABLE IF EXISTS signal_path;

CREATE TABLE signal_episode (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    venue            TEXT NOT NULL,
    symbol           TEXT NOT NULL,
    start_type       TEXT NOT NULL,          -- SIGNAL / V2_ENTRY
    v2_event_id      INTEGER,
    prior_episode_id INTEGER,
    is_first_episode INTEGER NOT NULL,
    repeat_count     INTEGER NOT NULL DEFAULT 0,
    last_repeat_time TEXT,
    t_sig            TEXT NOT NULL,
    t0               TEXT NOT NULL,
    generation_id    TEXT NOT NULL,
    param_version    TEXT,
    conditions       TEXT,
    hit_count        INTEGER,
    score            REAL,
    rel_1h REAL, rel_4h REAL, accel REAL, volume_ratio REAL, compression REAL, mom_15m REAL,
    change_24h       REAL,
    quote_volume_24h REAL,
    atr_1h_pct       REAL,
    first_early_price REAL,
    chase_pct        REAL,
    regime           TEXT,
    btc_ret_1h REAL, btc_ret_4h REAL, btc_ret_24h REAL, breadth REAL,
    review_result    TEXT,
    review_reasons   TEXT,
    p0               REAL NOT NULL,          -- SIGNAL：t0 卖一价；V2_ENTRY：模拟成交均价
    p0_bid           REAL,
    spread_bps       REAL NOT NULL,
    impact_bps       REAL NOT NULL,
    impact_source    TEXT NOT NULL,          -- DEPTH / MODEL
    fee_rate         REAL NOT NULL,
    roundtrip_cost_bps REAL NOT NULL,
    status           TEXT NOT NULL,          -- TRACKING / COMPLETED / DELISTED / DATA_GAP
    window_end       TEXT NOT NULL,
    last_bar_time    INTEGER,
    gap_bars         INTEGER NOT NULL DEFAULT 0,
    ambiguous_bars   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_episode_symbol ON signal_episode(venue, symbol, start_type, t0);
CREATE INDEX idx_episode_status ON signal_episode(status);

CREATE TABLE signal_track_bar (
    episode_id     INTEGER NOT NULL,
    bar_close_time INTEGER NOT NULL,
    open REAL NOT NULL, high REAL NOT NULL, low REAL NOT NULL, close REAL NOT NULL,
    quote_volume   REAL,
    PRIMARY KEY (episode_id, bar_close_time)
);

CREATE TABLE signal_outcome (
    episode_id        INTEGER PRIMARY KEY,
    observed_minutes  INTEGER NOT NULL,
    calc_version      TEXT NOT NULL,
    computed_at       TEXT NOT NULL,
    metrics           TEXT NOT NULL          -- 第 4 节全部指标（JSON），空值表示未发生（删失）
);

CREATE TABLE signal_pullback (
    episode_id    INTEGER NOT NULL,
    seq           INTEGER NOT NULL,
    peak_time     INTEGER NOT NULL,
    peak_gain_net REAL NOT NULL,
    trough_time   INTEGER NOT NULL,
    depth_pct     REAL NOT NULL,
    recovered     INTEGER NOT NULL,
    recover_time  INTEGER,
    PRIMARY KEY (episode_id, seq)
);

CREATE TABLE exit_rule_eval (
    run_id      TEXT NOT NULL,
    rule_id     TEXT NOT NULL,
    params_json TEXT NOT NULL,
    episode_id  INTEGER NOT NULL,
    exit_time   INTEGER NOT NULL,
    exit_reason TEXT NOT NULL,
    exit_price  REAL NOT NULL,
    net_return  REAL NOT NULL,
    holding_min INTEGER NOT NULL,
    killed_big  INTEGER NOT NULL,
    data_split  TEXT NOT NULL,             -- CALIB / VALID
    PRIMARY KEY (run_id, rule_id, episode_id)
);

-- 候选退出规则的影子并行：复用 V2 的每一笔买入，只有退出不同
CREATE TABLE variant_trade (
    rule_id      TEXT NOT NULL,
    venue        TEXT NOT NULL,
    symbol       TEXT NOT NULL,
    opened_at    TEXT NOT NULL,
    status       TEXT NOT NULL,            -- OPEN / CLOSED
    peak_net_pct REAL NOT NULL DEFAULT 0,
    cost_usdt    REAL,
    exit_reason  TEXT,
    exit_at      TEXT,
    net_pnl      REAL,
    hold_minutes INTEGER,
    b0_pnl       REAL,                     -- 同一笔 V2 的正式结果（V2 卖出时写入），用于配对比较
    b0_cost      REAL,
    PRIMARY KEY (rule_id, venue, symbol, opened_at)
);
