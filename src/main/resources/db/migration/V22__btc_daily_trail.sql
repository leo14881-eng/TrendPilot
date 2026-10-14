-- BTC 每日短线：移动止盈启动后的最高价（0 = 未启动），重启后继续跟踪
ALTER TABLE btc_daily_state ADD COLUMN trail_peak REAL NOT NULL DEFAULT 0;
