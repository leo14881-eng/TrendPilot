package com.trendpilot.portfolio;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.trendpilot.config.StrategyParams;

/** V2 影子组合：有 20,000U 硬顶的资金池、每币最多三笔，只有 Capital Review 的 BUY 才能写入。 */
@Repository
public class ShadowBook extends Ledger {

    public static final String BOOK = "V2";

    public ShadowBook(JdbcClient jdbc, StrategyParams params) {
        super(jdbc, params, BOOK, "position");
    }
}
