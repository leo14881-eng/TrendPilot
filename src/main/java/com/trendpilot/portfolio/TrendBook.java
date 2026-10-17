package com.trendpilot.portfolio;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.trendpilot.config.StrategyParams;

/**
 * V3 趋势跟踪影子账本（规则见 com.trendpilot.v3.TrendRules）：大盘多头时买创 20 日新高的山寨，ATR 止损 + 跟踪止损，
 * 没有过早的利润保护。按真实盘口模拟成交，独立资金 20,000 U，不占 V2 资金、不影响 V2。只做对比观察。
 */
@Repository
public class TrendBook extends Ledger {

    public static final String BOOK = "V3";

    public TrendBook(JdbcClient jdbc, StrategyParams params) {
        super(jdbc, params, BOOK, "v3_position");
    }
}
