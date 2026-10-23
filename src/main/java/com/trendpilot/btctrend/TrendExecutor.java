package com.trendpilot.btctrend;

/**
 * 成交执行。当前只有影子实现（按 D+1 开盘价、0.1% 手续费记账，永远成功）。
 * 实盘阶段（用户另行明确同意后）再加真实下单实现：限价 Maker、30 分钟重挂、2 小时市价补完、失败重试和余额不足告警。
 */
public interface TrendExecutor {

    record Fill(double price, double usdt, double btc, double fee) {
    }

    String mode();

    /** 用全部 usdt 买 BTC。 */
    Fill buy(double usdt, double price);

    /** 卖出全部 btc。 */
    Fill sell(double btc, double price);

    /** 影子执行：与参考实现 trend_ref.py 的记账完全一致。 */
    TrendExecutor SHADOW = new TrendExecutor() {
        @Override
        public String mode() {
            return "SHADOW";
        }

        @Override
        public Fill buy(double usdt, double price) {
            double btc = usdt / price * (1 - MaTrendRules.FEE);
            return new Fill(price, usdt, btc, usdt * MaTrendRules.FEE);
        }

        @Override
        public Fill sell(double btc, double price) {
            double gross = btc * price;
            return new Fill(price, gross * (1 - MaTrendRules.FEE), btc, gross * MaTrendRules.FEE);
        }
    };
}
