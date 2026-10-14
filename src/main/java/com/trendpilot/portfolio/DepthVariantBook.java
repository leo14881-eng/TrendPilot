package com.trendpilot.portfolio;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.trendpilot.config.StrategyParams;

/**
 * 深度门槛放宽对照账本（V2D15）：只记 V2 仅因盘口深度不足被拒、但按放宽后的深度门槛会买入的标的。
 * 买入和卖出都按真实盘口模拟成交（含滑点），每币一笔、不加仓、不占 V2 资金。只做对比，不影响 V2。
 */
@Repository
public class DepthVariantBook extends Ledger {

    public static final String BOOK = "V2D15";

    public DepthVariantBook(JdbcClient jdbc, StrategyParams params) {
        super(jdbc, params, BOOK, "v2d_position");
    }
}
