package com.trendpilot.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** BTC 主动买卖：占比、CVD、分位只看已收盘数据；数据缺失时开仓过滤一律"不开"。 */
class BtcFlowTest {

    static Series btc(int size, double share) {
        Series s = new Series("BTCUSDT", 0, size);
        for (int i = 0; i < size; i++) s.set(i, 100, 101, 99, 100, 1000, 1000 * share);
        return s;
    }

    @Test
    void shareAndCvdUseOnlyTheWindowEndingAtQ() {
        Series s = btc(40, 0.5);
        for (int i = 36; i < 40; i++) s.set(i, 100, 101, 99, 100, 1000, 800);
        BtcFlow f = new BtcFlow(s);
        assertThat(f.share(35, 1)).isEqualTo(0.5);
        assertThat(f.share(39, 1)).isEqualTo(0.8);
        assertThat(f.cvd(39, 1)).isEqualTo(4 * (800 - 200));
        assertThat(f.cvd(35, 1)).isZero();
        assertThat(f.share(2, 1)).isNaN(); // 窗口不够
    }

    @Test
    void percentileComparesWithPrevious30Days() {
        int hours = 800;
        Series s = btc(hours * 4, 0.5);
        int q = 760 * 4;
        for (int i = q - 3; i <= q; i++) s.set(i, 100, 101, 99, 100, 1000, 700);
        BtcFlow f = new BtcFlow(s);
        assertThat(f.percentile(q, 1)).isEqualTo(100.0);
        assertThat(f.percentile(q + 1, 1)).isNaN(); // 只在整点后第一根算
        assertThat(f.percentile(100 * 4, 1)).isNaN(); // 历史不够 360 个整点
        assertThat(f.gate("pct-min:1:50").test(q)).isTrue();
        assertThat(f.gate("share-min:1:0.52").test(q)).isTrue();
        assertThat(f.gate("share-min:1:0.52").test(q - 8)).isFalse();
    }

    @Test
    void missingDataMeansNoBuy() {
        Series s = new Series("BTCUSDT", 0, 200);
        for (int i = 0; i < 200; i++) s.set(i, 100, 101, 99, 100, 1000); // 旧数据：没有主动买入列
        BtcFlow f = new BtcFlow(s);
        assertThat(f.gate("cvd-pos:4").test(100)).isFalse();
        assertThat(f.gate("share-min:4:0.4").test(100)).isFalse();
        assertThat(f.gate("pct-min:4:0").test(100)).isFalse();
    }

    @Test
    void altBuySurgeNeedsBuyerVolumeShareAndSmallMove() {
        Series s = btc(300, 0.5);
        int q = 250;
        s.set(q, 100, 101, 99, 101, 6000, 4800); // 15m 买入 4800 ≈ 之前平均 500 的 9.6 倍，占比 80%，涨 1%
        MarketData md = null;
        AltFlowStudy st = new AltFlowStudy(md);
        var buy = new AltFlowStudy.Sign("a", "a", 1, 5, 0.7, false);
        var vol = new AltFlowStudy.Sign("v", "v", 1, 5, 0, true);
        assertThat(st.score(s, q, buy)).isGreaterThan(9);
        assertThat(st.score(s, q, vol)).isGreaterThan(5);
        assertThat(st.score(s, q - 1, buy)).isNaN();
        Series sell = btc(300, 0.5);
        sell.set(q, 100, 101, 99, 101, 6000, 1200); // 放量但主要是主动卖出
        assertThat(st.score(sell, q, buy)).isNaN();
        assertThat(st.score(sell, q, vol)).isGreaterThan(5);
        Series up = btc(300, 0.5);
        up.set(q, 100, 105, 99, 104, 6000, 4800); // 已经涨了 4%
        assertThat(st.score(up, q, buy)).isNaN();
    }
}
