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
 * @param firstOpenPct  &gt; 0 时第 1 档固定挂在 开盘价 × (1 − firstOpenPct%)，强弱日一样（用户 2026-10-20 指定 0.1）；0 = 按 kStrong / kWeak × ATR
 * @param carryTpPct    跨天未卖的仓位改用的止盈幅度（%），尽快回本离场，不割肉
 * @param slippageBps   模拟成交的滑点
 * @param backtestDays  上线回测覆盖的天数
 * @param filters       市场与宏观过滤（只影响是否开新一轮、开多大，不改变不止损）
 * @param stopCandidatesPct 候选止损（相对均价 %）：实盘不止损，只记录"如果止损会怎样"供分析
 * @param exit          卖出方式（实盘 TRAIL，回测确认不比 FIXED 差才生效；回测同时比较 FIXED / TRAIL / STOP）
 */
@ConfigurationProperties(prefix = "trendpilot.btc-daily")
public record BtcDailyProperties(boolean enabled, String version, String symbol, double capUsdt, String capUntil,
                                 List<Double> tranches, double kStrong, double kWeak, double stepAtr,
                                 double tpAtrMult, double tpMinPct, double carryTpPct, double feeRate,
                                 double slippageBps, int atrDays, int emaDays, int backtestDays, Filters filters,
                                 List<Double> stopCandidatesPct, Exit exit, String strategy, V3 v3,
                                 double firstOpenPct) {

    /**
     * v3 策略（2026-10-15 用户批准）：先按日线判断行情，再决定挂不挂单；卖出分批 + 跟踪，不止损。
     * <ul>
     * <li>上升（昨收 > EMA20 > EMA50）：开盘前在下方支撑挂三档（1h EMA20、前一日 VWAP、前一日低点，
     *     不够的按 stepAtr 往下补，相邻至少 minGapAtr × ATR），满额。</li>
     * <li>震荡：只挂更深的三档（开盘 − sideAtr × ATR 起），金额 × sideMultiplier。</li>
     * <li>下跌（昨收 < EMA20 < EMA50 且 EMA20 走低）：不挂单。议息 / CPI / ETF 大幅流出等过滤照旧。</li>
     * <li>加仓只在趋势还在时：上升行情档位须在日线 EMA20 上方，震荡须在 EMA50 上方；跨天的仓位不再加仓。</li>
     * <li>卖出：涨到均价 + partialAtPct% 先卖 partialPct%；同时启动跟踪，剩下的从最高价回落
     *     trailHourAtr × 1h ATR 再卖，最低按均价 + floorPct%（不卖亏）。不强制当天平仓，跟踪期间不加仓。</li>
     * <li>被套（跨天）：不止损、不加仓、不开新一轮；回到均价 + floorPct% 后启动同样的跟踪。</li>
     * </ul>
     */
    public record V3(double partialAtPct, double partialPct, double trailHourAtr, double floorPct, int hourEmaBars,
                     int hourAtrBars, double sideAtr, double sideMultiplier, double minGapAtr, double firstFallbackAtr,
                     String guard, int guardDays, double guardStopPct) {

        /**
         * guard：被套时的离场保护（用户未批准前实盘为 NONE，即不止损；其余只在回测里对比）。
         * TREND 行情转为下跌当天开盘卖；TIME 持有满 guardDays 天卖；STOP 跌到均价 − guardStopPct% 卖。
         */
        public V3 {
            if (guard == null || guard.isBlank()) guard = "NONE";
            if (guardDays <= 0) guardDays = 10;
            if (guardStopPct <= 0) guardStopPct = 8;
        }

        public static V3 defaults() {
            return new V3(1.5, 33.33, 1.5, 0.3, 20, 14, 1.5, 0.5, 0.3, 0.25, "NONE", 10, 8);
        }

        public V3 withGuard(String g) {
            return new V3(partialAtPct, partialPct, trailHourAtr, floorPct, hourEmaBars, hourAtrBars, sideAtr,
                    sideMultiplier, minGapAtr, firstFallbackAtr, g, guardDays, guardStopPct);
        }
    }

    /** 同一套参数，换 V3 的被套保护（回测对比用）。 */
    public BtcDailyProperties withGuard(String g) {
        return new BtcDailyProperties(enabled, version, symbol, capUsdt, capUntil, tranches, kStrong, kWeak, stepAtr,
                tpAtrMult, tpMinPct, carryTpPct, feeRate, slippageBps, atrDays, emaDays, backtestDays, filters,
                stopCandidatesPct, exit, strategy, v3.withGuard(g), firstOpenPct);
    }

    public boolean isV3() {
        return "V3".equals(strategy);
    }

    /** 同一套参数，换策略（V2 / V3）。 */
    public BtcDailyProperties withStrategy(String s) {
        return new BtcDailyProperties(enabled, version, symbol, capUsdt, capUntil, tranches, kStrong, kWeak, stepAtr,
                tpAtrMult, tpMinPct, carryTpPct, feeRate, slippageBps, atrDays, emaDays, backtestDays, filters,
                stopCandidatesPct, exit, s, v3, firstOpenPct);
    }

    /**
     * 卖出方式。
     * <ul>
     * <li>FIXED：当日仓位涨到均价 + 止盈幅度全卖；跨天仓位涨到均价 + carryTpPct 全卖（现行规则）。</li>
     * <li>TRAIL：涨到 FIXED 的止盈价（当日仓位用当日止盈线，跨天仓位用均价 + carryTpPct）后不立即卖，跟踪最高价，
     *     回落到 max(最高价 − trailAtr × ATR, 原止盈价) 时全卖：保底就是原止盈价，再往上让盈利多跑。不止损。</li>
     * <li>PARTIAL：到了止盈价先卖 partialPct% 锁定利润，剩下的跟踪最高价，回落 partialTrailAtr × ATR 再卖，
     *     最低按原止盈价；跟踪期间不加仓，剩余仓位可跨天继续跑。</li>
     * <li>STOP：现行规则 + 跌到均价 × (1 − hardStopPct%) 全部止损（只用于回测对比；用户硬规则绝不卖亏，实盘的卖出都要求有盈利）。</li>
     * <li>TRAIL_B（方案 B，用户 2026-10-20 选定）：涨到均价 + activatePct% 后启动跟踪，从买入以来最高价回落 giveAtr × 日线 ATR
     *     全部卖出；持有满 timeoutDays 天仍没卖，含费成本 + floorPct% 以上就卖。任何情况下成交价（扣滑点后）
     *     ≥ 含费均价 × (1 + 手续费 + floorPct%)，绝不卖亏。跟踪期间照常加仓；不再有"跨天降为 carryTpPct"。
     *     超时后的目标 = 含费均价 × (1 + 手续费 + timeoutTargetPct%)（默认等于 floorPct）；timeoutDays &lt; 0 时不降目标。</li>
     * <li>AUTO：上线回测从 candidates 里选"不比 FIXED 差"且总盈亏最高的一种；都不达标就用 FIXED。</li>
     * </ul>
     *
     * @param requireBacktest 为 true 时，mode 不是 FIXED 也要先等本版本回测确认"不比 FIXED 差"才实盘生效，
     *                        之前和不达标时都按 FIXED 运行（回滚：把 mode 改回 FIXED 并改版本号）
     */
    public record Exit(String mode, double trailAtr, double hardStopPct, boolean requireBacktest, double partialPct,
                       double partialTrailAtr, List<String> candidates, double activatePct, double giveAtr,
                       int timeoutDays, double floorPct, double timeoutTargetPct) {

        public Exit {
            if (mode == null || mode.isBlank()) mode = "FIXED";
            if (activatePct <= 0) activatePct = 3.0;
            if (giveAtr <= 0) giveAtr = 3.0;
            if (timeoutDays == 0) timeoutDays = 30; // 负数 = 不降目标，一直只按跟踪止盈卖
            if (floorPct <= 0) floorPct = 0.3;
            if (timeoutTargetPct < floorPct) timeoutTargetPct = floorPct;
            if (partialPct <= 0 || partialPct >= 100) partialPct = 50;
            if (partialTrailAtr <= 0) partialTrailAtr = 1.0;
            if (candidates == null || candidates.isEmpty()) candidates = List.of("TRAIL", "PARTIAL");
        }

        public static Exit defaults() {
            return new Exit("AUTO", 0.5, 8, true, 50, 1.0, List.of("TRAIL", "PARTIAL"), 3.0, 3.0, 30, 0.3, 0.3);
        }

        public Exit withMode(String m) {
            return new Exit(m, trailAtr, hardStopPct, requireBacktest, partialPct, partialTrailAtr, candidates,
                    activatePct, giveAtr, timeoutDays, floorPct, timeoutTargetPct);
        }

        /** 同一套参数，换超时规则：days 天未卖后，含费成本 + targetPct% 以上就卖（days &lt; 0 = 不降目标）。 */
        public Exit withTimeout(int days, double targetPct) {
            return new Exit(mode, trailAtr, hardStopPct, requireBacktest, partialPct, partialTrailAtr, candidates,
                    activatePct, giveAtr, days, floorPct, targetPct);
        }

        public boolean trailB() {
            return "TRAIL_B".equals(mode);
        }
    }

    /** 同一套参数，换第 1 档深度（开盘 −pct%）。 */
    public BtcDailyProperties withFirstOpenPct(double pct) {
        return new BtcDailyProperties(enabled, version, symbol, capUsdt, capUntil, tranches, kStrong, kWeak, stepAtr,
                tpAtrMult, tpMinPct, carryTpPct, feeRate, slippageBps, atrDays, emaDays, backtestDays, filters,
                stopCandidatesPct, exit, strategy, v3, pct);
    }

    /** 同一套参数，换方案 B 的超时规则。 */
    public BtcDailyProperties withExitTimeout(int days, double targetPct) {
        return new BtcDailyProperties(enabled, version, symbol, capUsdt, capUntil, tranches, kStrong, kWeak, stepAtr,
                tpAtrMult, tpMinPct, carryTpPct, feeRate, slippageBps, atrDays, emaDays, backtestDays, filters,
                stopCandidatesPct, exit.withTimeout(days, targetPct), strategy, v3, firstOpenPct);
    }

    /** 同一套参数，换一种卖出方式（回测对比用）。 */
    public BtcDailyProperties withExitMode(String mode) {
        return new BtcDailyProperties(enabled, version, symbol, capUsdt, capUntil, tranches, kStrong, kWeak, stepAtr,
                tpAtrMult, tpMinPct, carryTpPct, feeRate, slippageBps, atrDays, emaDays, backtestDays, filters,
                stopCandidatesPct, exit.withMode(mode), strategy, v3, firstOpenPct);
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
                          double minMultiplier, List<String> fomcDates, List<String> cpiDates, String fredApiKey, String binanceFuturesUrl,
                          String fearGreedUrl, String farsideUrl, String fredUrl) {

        public Filters {
            if (fomcDates == null) fomcDates = List.of();
            if (cpiDates == null) cpiDates = List.of();
            if (binanceFuturesUrl == null || binanceFuturesUrl.isBlank()) binanceFuturesUrl = "https://fapi.binance.com";
            if (fearGreedUrl == null || fearGreedUrl.isBlank()) fearGreedUrl = "https://api.alternative.me";
            if (farsideUrl == null || farsideUrl.isBlank()) farsideUrl = "https://farside.co.uk/btc/";
            if (fredUrl == null || fredUrl.isBlank()) fredUrl = "https://api.stlouisfed.org";
        }

        /** 这一年的 CPI 发布日是否已知（FRED 或配置里的官方日程）。 */
        public boolean cpiCalendarKnown(java.time.LocalDate day) {
            String year = day.getYear() + "-";
            return fredConfigured() || cpiDates.stream().anyMatch(d -> d.startsWith(year));
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
                    List.of(), "", null, null, null, null);
        }
    }

    public BtcDailyProperties {
        if (symbol == null || symbol.isBlank()) symbol = "BTCUSDT";
        if (tranches == null || tranches.isEmpty()) tranches = List.of(3000.0, 3000.0, 4000.0);
        if (filters == null) filters = Filters.defaults();
        if (stopCandidatesPct == null) stopCandidatesPct = List.of(3.0, 5.0, 8.0, 12.0);
        if (exit == null) exit = Exit.defaults();
        if (strategy == null || strategy.isBlank()) strategy = "V2";
        if (v3 == null) v3 = V3.defaults();
        double sum = tranches.stream().mapToDouble(Double::doubleValue).sum();
        if (sum > capUsdt + 1e-6) {
            throw new IllegalArgumentException("BTC 每日短线：档位合计 " + sum + " 超过上限 " + capUsdt);
        }
    }

    /** 测试和回测用的默认参数（与 application.yml 一致）。 */
    public static BtcDailyProperties defaults() {
        return new BtcDailyProperties(true, "btc-v3.2-2026-10-15", "BTCUSDT", 10_000, "2026-11-14",
                List.of(3000.0, 3000.0, 4000.0), 0.25, 0.6, 0.5, 0.5, 0.8, 0.3, 0.001, 2, 14, 20, 365,
                Filters.defaults(), List.of(3.0, 5.0, 8.0, 12.0), Exit.defaults().withMode("FIXED"), "AUTO", V3.defaults(), 0);
    }
}
