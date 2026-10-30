-- LP 影子盘每个策略在越南日期最后一分钟的状态（处理新一天第一条快照前写入）：
-- 日报只用目标日期内的分钟，停机跨过零点、重启后先处理了新一天的分钟也不会混进去
CREATE TABLE lp_day_close (
    date        TEXT    NOT NULL,
    strategy    TEXT    NOT NULL,
    pool        TEXT    NOT NULL,
    state_json  TEXT    NOT NULL,
    PRIMARY KEY (date, strategy)
);
