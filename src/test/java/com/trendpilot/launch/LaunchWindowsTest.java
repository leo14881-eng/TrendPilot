package com.trendpilot.launch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 启动探测窗口：攒满 1 小时才判断；涨幅和放量同时达标才记；断线后重新攒；1 小时内同一个币只记一次。时间全部是固定值。 */
class LaunchWindowsTest {

    static final long T0 = 1_761_000_000_000L;

    /** 每 2 秒一次推送：价格 price，本根 15m 累计成交额按每秒 rate U 增长。 */
    static LaunchWindows.Launch feed(LaunchWindows w, long fromSec, long toSec, double price0, double price1, double rate) {
        LaunchWindows.Launch hit = null;
        for (long s = fromSec; s < toSec; s += 2) {
            long ms = T0 + s * 1000;
            long bar = Math.floorDiv(ms, 900_000L) * 900_000L;
            double cum = (ms - bar) / 1000.0 * rate;
            double p = price0 + (price1 - price0) * (s - fromSec) / (double) (toSec - fromSec);
            LaunchWindows.Launch l = w.onTick("BINANCE:ABCUSDT", p, cum, bar, ms);
            if (l != null && hit == null) hit = l;
        }
        return hit;
    }

    @Test
    void detectsPriceJumpWithVolumeOnlyAfterWarmUp() {
        LaunchWindows w = new LaunchWindows(2, 3);
        assertThat(feed(w, 0, 3600, 100, 100, 10)).isNull();
        // 5 分钟涨 4%，成交额 6 倍
        LaunchWindows.Launch l = feed(w, 3600, 3900, 100, 104, 60);
        assertThat(l).isNotNull();
        assertThat(l.ret5mPct()).isBetween(2.0, 4.1);
        assertThat(l.volRatio()).isGreaterThanOrEqualTo(3);
        // 1 小时内不再记
        assertThat(feed(w, 3900, 4200, 104, 110, 120)).isNull();
    }

    @Test
    void priceJumpWithoutVolumeOrTooEarlyIsNotALaunch() {
        LaunchWindows w = new LaunchWindows(2, 3);
        assertThat(feed(w, 0, 1800, 100, 100, 10)).isNull();
        assertThat(feed(w, 1800, 2100, 100, 105, 60)).isNull(); // 还没攒满 1 小时
        LaunchWindows w2 = new LaunchWindows(2, 3);
        feed(w2, 0, 3600, 100, 100, 10);
        assertThat(feed(w2, 3600, 3900, 100, 105, 12)).isNull(); // 没放量
    }

    @Test
    void gapInTheStreamRestartsWarmUp() {
        LaunchWindows w = new LaunchWindows(2, 3);
        feed(w, 0, 3600, 100, 100, 10);
        // 断了 5 分钟后再来：窗口作废，不判断
        assertThat(feed(w, 3900, 4200, 100, 106, 80)).isNull();
    }
}
