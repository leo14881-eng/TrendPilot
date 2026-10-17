-- 候选退出 X1（日线 ATR×1.5 止损 + 3 天仍亏损等反弹高点卖）的影子并行状态
ALTER TABLE variant_trade ADD COLUMN x_dist_pct REAL;
ALTER TABLE variant_trade ADD COLUMN x_wait_since TEXT;
ALTER TABLE variant_trade ADD COLUMN x_prev_close REAL;
ALTER TABLE variant_trade ADD COLUMN x_rose INTEGER NOT NULL DEFAULT 0;
ALTER TABLE variant_trade ADD COLUMN x_last_hour INTEGER;
