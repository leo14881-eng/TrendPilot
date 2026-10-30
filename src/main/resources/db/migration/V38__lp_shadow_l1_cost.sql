-- LP 影子盘调仓成本改按实际成本（用户 2026-10-30 批准）：L2 执行费（gasPrice × 60 万 gas）+ L1 数据费（链上只读估算）。
-- 快照加两列；虚拟仓位、事件、日汇总清空，60 个仓位和 3 个基准在同一分钟按新口径重新开始（原始快照保留）
ALTER TABLE lp_pool_snapshot ADD COLUMN l1_fee_wei TEXT;   -- 一次调仓（全部交易）的 L1 数据费估算，wei
ALTER TABLE lp_pool_snapshot ADD COLUMN l1_source TEXT;    -- 用的只读方法：getL1FeeUpperBound / getL1Fee / getPricesInWei / getL1BaseFeeEstimate
DELETE FROM lp_state;
DELETE FROM lp_position;
DELETE FROM lp_event;
DELETE FROM lp_daily;
