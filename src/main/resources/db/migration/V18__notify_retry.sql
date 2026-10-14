-- 通知失败重试：记录尝试次数和已经发出的分段数（超过 4000 字的消息分段发送）
ALTER TABLE notification_outbox ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE notification_outbox ADD COLUMN sent_parts INTEGER NOT NULL DEFAULT 0;
