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
 * @param filters       市场与宏观过滤（只影响是否开新一轮、开多大，不改变不止损）
 * @param stopCandidatesPct 候选止损（相对均价 %）：实盘不止损，只记录"如果止损会怎样"供分析
 * @param exit          卖出方式（实盘默认 FIXED；回测同时比较 FIXED / TRAIL / STOP）
 */
@ConfigurationProperties(prefix = "trendpilot.btc-daily")
public record BtcDailyProperties(boolean enabled, String version, String symbol, double capUsdt, String capUntil,
                                 List<Double> tranches, double kStrong, double kWeak, double stepAtr,
                                 double tpAtrMult, double tpMinPct, double carryTpPct, double feeRate,
                                 double slippageBps, int atrDays, int emaDays, int backtestDays, Filters filters,
                                 List<Double> stopCandidatesPct, Exit exit) {

    /**
     * 卖出方式。
     * <ul>
     * <li>FIXED：当日仓位涨到均价 + 止盈幅度全卖；跨天仓位涨到均价 + carryTpPct 全卖（现行规则）。</li>
     * <li>TRAIL：涨到止盈线（跨天仓位用 trailArmPct）后不立即卖，跟踪最高价，回落到
     *     max(最高价 − trailAtr × ATR, 均价 × (1 + trailFloorPct%)) 时全卖，让盈利多跑。</li>
     * <li>STOP：现行规则 + 跌到均价 × (1 − hardStopPct%) 全部止损。</li>
     * </ul>
     */
    public record Exit(String mode, double trailArmPct, double trailAtr, double trailFloorPct, double hardStopPct) {

        public Exit {
            if (mode == null || mode.isBlank()) mode = "FIXED";
        }

        public static Exit defaults() {
            return new Exit("FIXED", 0.8, 0.5, 0.3, 8);
        }

        public Exit withMode(String m) {
            return new Exit(m, trailArmPct, trailAtr, trailFloorPct, hardStopPct);
        }
    }

    /** 同一套参数，换一种卖出方式（回测对比用）。 */
    public BtcDailyProperties withExitMode(String mode) {
        return new BtcDailyProperties(enabled, version, symbol, capUsdt, capUntil, tranches, kStrong, kWeak, stepAtr,
                tpAtrMult, tpMinPct, carryTpPct, feeRate, slippageBps, atrDays, emaDays, backtestDays, filters,
                stopCandidatesPct, exit.withMode(mode));
    }

    /**
     * 市场与宏观过滤。每条"减半"把新一轮的档位金额乘 0.5，可叠加，最低 minMultiplier；"不开新一轮"直接跳过。
     *
     * @param emaLong          下跌趋势：昨收 < EMA20 且 EMA20 < EMA(emaLong) 且 EMA20 比 slopeDays 天前低 → 不开新一轮
     * @param fundingMaxPct    昨天平均资金费率（%/8h）≥ 此值 → 减半
     * @param fearGreedMax     恐惧贪婪指数 ≥ 此值 → 减半
     * @param etfOutflowDayM   最近已公布交易日 ETF 净流出 ≥ 此值（百万美元）→ 减半
     * @param etfOutflow3dM    最近 3 个已公布交易日合计净流出 ≥ 此值 → 不开新一轮
     * @param us10yRisePp      美国 10 年期利率 5 个观测日内上升 ≥ 此值（百分点）→ 减半（需 FRED key）
     * @param dollarRisePct    美元指数 5 个观测日内上涨 ≥ 此值（%）→ 减半（需 FRED key）
     * @param fomcDates        美联储议息决议日（当天不开新一轮）；CPI 发布日由 FRED 获取
     * @param fredApiKey       FRED 免费 key，只放本机 config/application-local.yml
     */
    public record Filters(boolean enabled, int emaLong, int slopeDays, double fundingMaxPct, double fearGreedMax,
                          double etfOutflowDayM, double etfOutflow3dM, double us10yRisePp, double dollarRisePct,
                          double minMultiplier, List<String> fomcDates, String fredApiKey, String binanceFuturesUrl,
                          String fearGreedUrl, String farsideUrl, String fredUrl) {

        public Filters {
            if (fomcDates == null) fomcDates = List.of();
            if (binanceFuturesUrl == null || binanceFuturesUrl.isBlank()) binanceFuturesUrl = "https://fapi.binance.com";
            if (fearGreedUrl == null || fearGreedUrl.isBlank()) fearGreedUrl = "https://api.alternative.me";
            if (farsideUrl == null || farsideUrl.isBlank()) farsideUrl = "https://farside.co.uk/btc/";
            if (fredUrl == null || fredUrl.isBlank()) fredUrl = "https://api.stlouisfed.org";
        }

        public boolean fredConfigured() {
            return fredApiKey != null && !fredApiKey.isBlank();
        }

        public static Filters defaults() {
            return new Filters(true, 50, 5, 0.03, 80, 300, 1000, 0.2, 1.5, 0.25,
                    List.of("2024-01-31", "2024-03-20", "2024-05-01", "2024-06-12", "2024-07-31", "2024-09-18",
                            "2024-11-07", "2024-12-18", "2025-01-29", "2025-03-19", "2025-05-07", "2025-06-18",
                            "2025-07-30", "2025-09-17", "2025-10-29", "2025-12-10", "2026-01-28", "2026-03-18",
                            "2026-04-29", "2026-06-17", "2026-07-29", "2026-09-16", "2026-10-28", "2026-12-09"),
                    "", null, null, null, null);
        }
    }

    public BtcDailyProperties {
        if (symbol == null || symbol.isBlank()) symbol = "BTCUSDT";
        if (tranches == null || tranches.isEmpty()) tranches = List.of(3000.0, 3000.0, 4000.0);
        if (filters == null) filters = Filters.defaults();
        if (stopCandidatesPct == null) stopCandidatesPct = List.of(3.0, 5.0, 8.0, 12.0);
        if (exit == null) exit = Exit.defaults();
        double sum = tranches.stream().mapToDouble(Double::doubleValue).sum();
        if (sum > capUsdt + 1e-6) {
            throw new IllegalArgumentException("BTC 每日短线：档位合计 " + sum + " 超过上限 " + capUsdt);
        }
    }

    /** 测试和回测用的默认参数（与 application.yml 一致）。 */
    public static BtcDailyProperties defaults() {
        return new BtcDailyProperties(true, "btc-v2.1-2026-10-15", "BTCUSDT", 10_000, "2026-11-14",
                List.of(3000.0, 3000.0, 4000.0), 0.25, 0.6, 0.5, 0.5, 0.8, 0.3, 0.001, 2, 14, 20, 365,
                Filters.defaults(), List.of(3.0, 5.0, 8.0, 12.0), Exit.defaults());
    }
}
