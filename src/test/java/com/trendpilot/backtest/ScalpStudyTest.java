package com.trendpilot.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/** 超短线检验的成交口径：止损按止损价（跳空按开盘价），保护线用上一分钟为止的最高价，同一分钟先算止损。 */
class ScalpStudyTest {

    static final ScalpStudy.Exit X = new ScalpStudy.Exit("t", 0.02, 0.01, 0.005);

    static Series bars(double[][] ohlc) {
        Series s = new Series("T", 0, ohlc.length);
        for (int i = 0; i < ohlc.length; i++) s.set(i, ohlc[i][0], ohlc[i][1], ohlc[i][2], ohlc[i][3], 1000);
        return s;
    }

    static double net(double sell) {
        return (sell * (1 - ScalpStudy.SLIP) * (1 - ScalpStudy.FEE) / (100 * (1 + ScalpStudy.SLIP) * (1 + ScalpStudy.FEE)) - 1) * 100;
    }

    @Test
    void stopFillsAtStopPriceOrGapOpen() {
        double[] a = ScalpStudy.trade(bars(new double[][]{{100, 100.5, 99.5, 100}, {100, 100, 97, 97.5}}), 0, X, ScalpStudy.SLIP);
        assertThat(a[0]).isCloseTo(net(98), within(1e-6));
        double[] g = ScalpStudy.trade(bars(new double[][]{{100, 100.5, 99.5, 100}, {96, 96.5, 95, 96}}), 0, X, ScalpStudy.SLIP);
        assertThat(g[0]).isCloseTo(net(96), within(1e-6));
    }

    @Test
    void trailingUsesPriorPeakAndStopComesFirst() {
        // 第 1 分钟涨到 101.5 启动保护；第 2 分钟回落到 100.9（101.5×0.995=100.99）以下 → 按 100.9925 卖
        double[] a = ScalpStudy.trade(bars(new double[][]{{100, 100.2, 99.9, 100}, {100, 101.5, 100.5, 101.2}, {101.2, 101.3, 100.9, 101}}),
                0, X, ScalpStudy.SLIP);
        assertThat(a[0]).isCloseTo(net(101.5 * 0.995), within(1e-6));
        // 同一分钟既创新高又跌破止损：按止损算
        double[] b = ScalpStudy.trade(bars(new double[][]{{100, 103, 97, 99}}), 0, X, ScalpStudy.SLIP);
        assertThat(b[0]).isCloseTo(net(98), within(1e-6));
    }
}
