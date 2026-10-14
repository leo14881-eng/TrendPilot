-- 每笔持仓的最大浮盈、最大浮亏（按全数量模拟卖出的可退出净利，单位 U）与首次转盈利时间；
-- 卖出时写入事件，供以后用数据决定是否需要止损或时间止损
ALTER TABLE position ADD COLUMN max_net REAL;
ALTER TABLE position ADD COLUMN min_net REAL;
ALTER TABLE position ADD COLUMN first_profit_at TEXT;
ALTER TABLE v1_position ADD COLUMN max_net REAL;
ALTER TABLE v1_position ADD COLUMN min_net REAL;
ALTER TABLE v1_position ADD COLUMN first_profit_at TEXT;
ALTER TABLE shadow_event ADD COLUMN max_net REAL;
ALTER TABLE shadow_event ADD COLUMN min_net REAL;
ALTER TABLE shadow_event ADD COLUMN minutes_to_profit INTEGER;
