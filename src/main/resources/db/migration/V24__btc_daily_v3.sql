-- BTC 每日短线 v3：计划记录策略、行情、1h ATR、加仓下限；每轮和持仓记录开仓行情
ALTER TABLE btc_daily_plan ADD COLUMN strategy TEXT NOT NULL DEFAULT 'V2';
ALTER TABLE btc_daily_plan ADD COLUMN regime TEXT NOT NULL DEFAULT '';
ALTER TABLE btc_daily_plan ADD COLUMN hour_atr REAL NOT NULL DEFAULT 0;
ALTER TABLE btc_daily_plan ADD COLUMN add_floor REAL NOT NULL DEFAULT 0;
ALTER TABLE btc_daily_round ADD COLUMN regime TEXT NOT NULL DEFAULT '';
ALTER TABLE btc_daily_state ADD COLUMN open_regime TEXT NOT NULL DEFAULT '';
