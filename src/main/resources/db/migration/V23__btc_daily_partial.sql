-- BTC 每日短线：本轮已部分卖出的成本和到手金额（先卖一半、剩下跟踪时用），整轮结束清零
ALTER TABLE btc_daily_state ADD COLUMN sold_cost REAL NOT NULL DEFAULT 0;
ALTER TABLE btc_daily_state ADD COLUMN sold_proceeds REAL NOT NULL DEFAULT 0;
