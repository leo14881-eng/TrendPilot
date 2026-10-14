package com.trendpilot.btcdaily;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * BTC 每日短线参数（docs/BTC每日短线评估.md）。只做多现货、不止损、允许分档加仓。
 *
 * @param version       参数版本，改参数时一起改；回测按版本各跑一次
 * @param capUsdt       单轮合计买入上限（含手续费）
 * @param capUntil      当前上限的复评日（到期按数据复评，不自动放大）
 * @param tranches      各档买入金额（含手续费），合计不得超过 capUsdt
 * @param kStrong       偏强（昨收 > EMA）时第 1 档 = 开盘价 − kStrong × ATR
 * @param kWeak         偏弱时第 1 档 = 开盘价 − kWeak × ATR
 * @param stepAtr       相邻两档间距（× ATR）
 * @param tpAtrMult     止盈幅度 = max(tpAtrMult × ATR%, tpMinPct)
 * @param tpMinPct      止盈幅度下限（%）
 * @param carryTpPct    跨天未卖的仓位改用的止盈幅度（%），尽快回本离场，不割肉
 * @param slippageBps   模拟成交的滑点
 * @param backtestDays  上线回测覆盖的天数
 */
@ConfigurationProperties(prefix = "trendpilot.btc-daily")
public record BtcDailyProperties(boolean enabled, String version, String symbol, double capUsdt, String capUntil,
                                 List<Double> tranches, double kStrong, double kWeak, double stepAtr,
                                 double tpAtrMult, double tpMinPct, double carryTpPct, double feeRate,
                                 double slippageBps, int atrDays, int emaDays, int backtestDays) {

    public BtcDailyProperties {
        if (symbol == null || symbol.isBlank()) symbol = "BTCUSDT";
        if (tranches == null || tranches.isEmpty()) tranches = List.of(3000.0, 3000.0, 4000.0);
        double sum = tranches.stream().mapToDouble(Double::doubleValue).sum();
        if (sum > capUsdt + 1e-6) {
            throw new IllegalArgumentException("BTC 每日短线：档位合计 " + sum + " 超过上限 " + capUsdt);
        }
    }

    /** 测试和回测用的默认参数（与 application.yml 一致）。 */
    public static BtcDailyProperties defaults() {
        return new BtcDailyProperties(true, "btc-v1-2026-10-14", "BTCUSDT", 10_000, "2026-11-14",
                List.of(3000.0, 3000.0, 4000.0), 0.25, 0.6, 0.5, 0.5, 0.8, 0.3, 0.001, 2, 14, 20, 365);
    }
}
