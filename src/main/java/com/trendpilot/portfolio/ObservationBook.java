package com.trendpilot.portfolio;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.trendpilot.config.StrategyParams;

/**
 * V1 宽口径观察账本：EARLY 至少两个条件即可进入，没有资金池和数量上限，每币只有一笔、不加仓。
 * 按参考价 + 手续费模型记账，样本收益不能当作可执行组合收益。
 */
@Repository
public class ObservationBook extends Ledger {

    public static final String BOOK = "V1";

    public ObservationBook(JdbcClient jdbc, StrategyParams params) {
        super(jdbc, params, BOOK, "v1_position");
    }
}
