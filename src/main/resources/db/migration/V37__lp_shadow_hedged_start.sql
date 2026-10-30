-- LP 影子盘加入调仓、对冲和资金费：清空之前只跑了不对冲仓位的试运行数据，
-- 让 60 个虚拟仓位和 3 个基准在同一分钟重新开始（快照数据保留）
DELETE FROM lp_state;
DELETE FROM lp_position;
DELETE FROM lp_event;
DELETE FROM lp_daily;
