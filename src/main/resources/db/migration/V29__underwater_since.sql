-- 冻结修复（随山寨止损一起上线，2026-10-17 用户批准）：深度浮亏的持仓只在第一次跌破后的冻结窗口内计入亏损冻结
ALTER TABLE position ADD COLUMN underwater_since TEXT;
ALTER TABLE v1_position ADD COLUMN underwater_since TEXT;
ALTER TABLE v2d_position ADD COLUMN underwater_since TEXT;
