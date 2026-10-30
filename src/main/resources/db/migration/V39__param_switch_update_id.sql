-- 回滚指令按 Telegram 消息编号只执行一次：同一条"回滚"消息被重复处理（回复没发出去重试、重启后重新投递）时不再多撤一次
ALTER TABLE param_switch ADD COLUMN update_id INTEGER;
CREATE UNIQUE INDEX param_switch_update_id ON param_switch(update_id) WHERE update_id IS NOT NULL;
