package com.trendpilot.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PumpStudyTest {

    private static Series series(double[][] ohlc) {
        Series s = new Series("XUSDT", 0, ohlc.length);
        for (int i = 0; i < ohlc.length; i++) s.set(i, ohlc[i][0], ohlc[i][1], ohlc[i][2], ohlc[i][3], 1000);
        return s;
    }

    @Test
    void trailingStopSellsAtTrailLevelAfterPeak() {
        // 买在 100，涨到 120 后回落：回落 5% 的线是 114
        Series s = series(new double[][]{{100, 105, 99, 104}, {104, 120, 103, 118}, {118, 119, 110, 111}});
        double px = PumpStudy.exit(s, 0, new PumpStudy.Exit("t", 96, 5, 0, 0), 100, 3);
        assertThat(px).isEqualTo(114.0);
    }

    @Test
    void bracketHitsStopBeforeTakeInSameBar() {
        // 同一根 K 线先跌到止损再涨到止盈：按 开→低→高→收，先止损
        Series s = series(new double[][]{{100, 106, 96, 101}});
        double px = PumpStudy.exit(s, 0, new PumpStudy.Exit("b", 96, 0, 5, 3), 100, 1);
        assertThat(px).isEqualTo(97.0);
    }

    @Test
    void fixedHoldSellsAtLastClose() {
        Series s = series(new double[][]{{100, 101, 99, 100}, {100, 103, 99, 102}, {102, 110, 101, 108}});
        double px = PumpStudy.exit(s, 0, new PumpStudy.Exit("h", 2, 0, 0, 0), 100, 3);
        assertThat(px).isEqualTo(102.0);
    }
}
