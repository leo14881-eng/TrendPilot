package com.trendpilot.portfolio;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.trendpilot.config.StrategyParams;

/**
 * 强信号对照账本（V5）：与 V2 相同的审核规则，另要求新开仓 1h 相对 BTC 收益不低于强信号门槛。
 * 买入和卖出都按真实盘口模拟成交（含滑点），每币一笔、不加仓、不占 V2 资金。只做同期对比，不影响 V2。
 */
@Repository
public class StrongVariantBook extends Ledger {

    public static final String BOOK = "V5";

    public StrongVariantBook(JdbcClient jdbc, StrategyParams params) {
        super(jdbc, params, BOOK, "v5_position");
    }
}
