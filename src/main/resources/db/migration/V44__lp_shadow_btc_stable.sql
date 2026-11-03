-- LP 影子盘新增 BTC 池和稳定币池（2026-11-03）：只加列，不改动已有池子的数据和计时
ALTER TABLE lp_pool ADD COLUMN asset TEXT;              -- ETH / BTC / STABLE；旧行为空 = ETH
ALTER TABLE lp_pool ADD COLUMN tvl_usd REAL;            -- 上线前规模检查时的链上 TVL（USDC）
ALTER TABLE lp_pool ADD COLUMN volume24h_usd REAL;      -- 同上，24 小时成交量（GeckoTerminal 公开接口）
ALTER TABLE lp_pool_snapshot ADD COLUMN gas_token_usd REAL;  -- 非 ETH 池子：付 gas 的 ETH 价格（ETHUSDT 永续标记价）
