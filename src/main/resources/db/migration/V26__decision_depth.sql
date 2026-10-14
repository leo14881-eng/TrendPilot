-- 审核记录保存 ±2% 买卖盘深度（U），方便回答"哪些币被深度挡住、差多少"
ALTER TABLE decision_log ADD COLUMN bid_depth REAL;
ALTER TABLE decision_log ADD COLUMN ask_depth REAL;
