-- 作废 2026-10-14 上线当天的影子仓位：计划在价格已跌破第 1 档后才生成，第 1 档按开盘价成交，
-- 第 2 档仍用原计划价，两档只差约 212 USDT 就连续加仓（规则缺陷，已修复：加仓至少比上一笔实际成交低 0.5 × ATR）。
-- 成交和轮次保留，只标记作废，不计入统计。
ALTER TABLE btc_daily_trade ADD COLUMN voided INTEGER NOT NULL DEFAULT 0;
ALTER TABLE btc_daily_round ADD COLUMN voided INTEGER NOT NULL DEFAULT 0;

UPDATE btc_daily_trade SET voided = 1, reason = reason || '（已作废：加仓间距缺陷）' WHERE day <= '2026-10-14';
UPDATE btc_daily_round SET voided = 1 WHERE opened_day <= '2026-10-14';

-- 账本归零（上线第一天，之前没有其他有效成交）；保留已处理到的 K 线位置
UPDATE btc_daily_state SET qty = 0, cost = 0, buys = 0, last_buy_price = 0, opened_day = NULL, realized = 0,
    rounds = 0, wins = 0, last_exit_day = '2026-10-14', round_max_float_loss = 0, max_float_loss = 0, opened_at = 0,
    mae_pct = 0, mfe_pct = 0, open_filters = '', stop_hits = ''
WHERE NOT EXISTS (SELECT 1 FROM btc_daily_trade WHERE day > '2026-10-14');
