-- V1 / V2 收益对比：持仓保存最近一次可退出净利（未实现盈亏），卖出事件保存成本和持有时长
ALTER TABLE position ADD COLUMN mark_net TEXT NOT NULL DEFAULT '0';
ALTER TABLE v1_position ADD COLUMN mark_net TEXT NOT NULL DEFAULT '0';
ALTER TABLE shadow_event ADD COLUMN cost_usdt TEXT;
ALTER TABLE shadow_event ADD COLUMN hold_minutes INTEGER;
