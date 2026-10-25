package com.trendpilot.variant;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配置化影子变体（trendpilot.variants.list）。每个变体 = V2 基准 + 一组参数改动，独立记账、不占 V2 资金、不发开仓 Telegram。
 * <pre>
 * trendpilot.variants.list:
 *   - { name: V6, base: V2, trade-usdt: 1000, overrides: { early-exit-hours: 24, early-exit-max-mfe-pct: 1, early-exit-loss-pct: 4 },
 *       backtest-passed: false, evidence: docs/策略对比记录/2026-10-26-H1H2H4.md }
 * </pre>
 * 其他已有键（depth-min-usdt、strong-trade-usdt、strong-min-rel1h）照旧由各自的类读取。
 */
@ConfigurationProperties(prefix = "trendpilot.variants")
public record VariantsProperties(List<Def> list) {

    public VariantsProperties {
        list = list == null ? List.of() : List.copyOf(list);
    }

    /** 一个变体的配置。base 只能是 V2；tradeUsdt 为空时用 V2 的每笔金额。 */
    public record Def(String name, String base, Double tradeUsdt, Map<String, String> overrides, Boolean backtestPassed,
                      String evidence) {
    }
}
