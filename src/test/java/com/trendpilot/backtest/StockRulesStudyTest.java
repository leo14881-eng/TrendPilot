package com.trendpilot.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class StockRulesStudyTest {

    /** 每天一根日线，close 按 path 给出，高低价 ±0.5%，成交额固定 1 亿。 */
    private static Series series(String symbol, double[] path) {
        Series s = new Series(symbol, 0, path.length);
        for (int i = 0; i < path.length; i++) s.set(i, path[i], path[i] * 1.005, path[i] * 0.995, path[i], 100_000_000);
        return s;
    }

    private static double[] flat(int n, double v) {
        double[] p = new double[n];
        java.util.Arrays.fill(p, v);
        return p;
    }

    @Test
    void floorMatchesStockPilotTiers() {
        assertThat(StockRulesStudy.floor(0.9)).isNaN();
        assertThat(StockRulesStudy.floor(1.0)).isEqualTo(0.5);
        assertThat(StockRulesStudy.floor(8.0)).isEqualTo(8 * 0.65);
        assertThat(StockRulesStudy.floor(30.0)).isEqualTo(24.0);
        // 回落到 0 和保护线之间才卖；跌到 0 以下不卖（只盈利卖）
        assertThat(StockRulesStudy.giveback(10, 6)).isTrue();
        assertThat(StockRulesStudy.giveback(10, 7)).isFalse();
        assertThat(StockRulesStudy.giveback(10, -1)).isFalse();
    }

    @Test
    void crashStopOnlyWhenLosingEnough() {
        assertThat(StockRulesStudy.stopReason(-25, 75, null, 0)).isEqualTo("STOP_LOSS_CRASH");
        assertThat(StockRulesStudy.stopReason(-24, 76, null, 0)).isNull();
        assertThat(StockRulesStudy.stopReason(5, 105, null, 0)).isNull();
        // 连续两天在 50 日线下方且亏损超过 max(10%, 3×ATR)
        StockRulesStudy.Stop ctx = new StockRulesStudy.Stop(90, 100, 49 * 100, 1000, 2);
        assertThat(StockRulesStudy.stopReason(-11, 89, ctx, 1000)).isEqualTo("STOP_LOSS_TREND_BREAK");
        assertThat(StockRulesStudy.stopReason(-9, 89, ctx, 1000)).isNull();
        // 比昨收跌 15% 且成交量 3 倍
        assertThat(StockRulesStudy.stopReason(-5, 76, new StockRulesStudy.Stop(90, 80, 49 * 80, 1000, 2), 3000))
                .isEqualTo("STOP_LOSS_CRASH_DAY");
    }

    @Test
    void buysUptrendThenSellsOnGiveback() {
        int n = 120;
        double[] up = new double[n];
        for (int i = 0; i < n; i++) up[i] = i < 100 ? 100 * Math.pow(1.004, i) : 100 * Math.pow(1.004, 100) * (1 - 0.002 * (i - 99));
        Map<String, Series> m = new HashMap<>();
        m.put("BTCUSDT", series("BTCUSDT", flat(n, 100)));
        m.put("ETHUSDT", series("ETHUSDT", flat(n, 100)));
        m.put("XUSDT", series("XUSDT", up));
        StockRulesStudy st = new StockRulesStudy(m, 0, n);
        StockRulesStudy.Result r = st.run(new StockRulesStudy.Variant("t", 20, true, 0), 60);
        assertThat(r.buys()).isGreaterThan(0);
        assertThat(r.closed()).anySatisfy(c -> {
            assertThat(c.symbol()).isEqualTo("XUSDT");
            assertThat(c.reason()).isEqualTo("NET_PROFIT_GIVEBACK");
            assertThat(c.pnl()).isPositive();
        });
    }

    @Test
    void losingPositionHeldWithoutStopsAndCutWithStops() {
        int n = 140;
        double[] p = new double[n];
        // 先稳步上涨触发买入，然后一路跌 40%
        for (int i = 0; i < n; i++) p[i] = i <= 70 ? 100 * Math.pow(1.004, i) : 100 * Math.pow(1.004, 70) * Math.pow(0.993, i - 70);
        Map<String, Series> m = new HashMap<>();
        m.put("BTCUSDT", series("BTCUSDT", flat(n, 100)));
        m.put("ETHUSDT", series("ETHUSDT", flat(n, 100)));
        m.put("XUSDT", series("XUSDT", p));
        StockRulesStudy.Result noStop = new StockRulesStudy(m, 0, n).run(new StockRulesStudy.Variant("无止损", 20, false, 0), 69);
        StockRulesStudy.Result withStop = new StockRulesStudy(m, 0, n).run(new StockRulesStudy.Variant("止损", 20, true, 0), 69);
        assertThat(noStop.closed()).noneMatch(c -> c.pnl() < 0);
        assertThat(noStop.open()).containsKey("XUSDT");
        assertThat(withStop.closed()).anySatisfy(c -> assertThat(c.reason()).startsWith("STOP_LOSS"));
    }

    @Test
    void leveragedTokensExcluded() {
        assertThat(StockRulesStudy.excludedDerivative("BTCUP")).isTrue();
        assertThat(StockRulesStudy.excludedDerivative("ETHDOWN")).isTrue();
        assertThat(StockRulesStudy.excludedDerivative("WBTC")).isTrue();
        assertThat(StockRulesStudy.excludedDerivative("JUP")).isFalse();
        assertThat(StockRulesStudy.excludedDerivative("SOL")).isFalse();
    }
}
