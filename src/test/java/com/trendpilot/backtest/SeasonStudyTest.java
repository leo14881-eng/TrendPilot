package com.trendpilot.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SeasonStudyTest {

    /** BTC 横盘；30 个山寨前 200 天每天跌 0.1%，之后每天涨 1%（山寨季）。 */
    private static SeasonStudy synthetic(int days) {
        Map<String, Series> m = new HashMap<>();
        Series btc = new Series("BTCUSDT", 0, days);
        for (int d = 0; d < days; d++) btc.set(d, 100, 100, 100, 100, 1e9);
        m.put("BTCUSDT", btc);
        for (int k = 0; k < 30; k++) {
            Series s = new Series("A" + k + "USDT", 0, days);
            double p = 10;
            for (int d = 0; d < days; d++) {
                if (d >= 200) p *= 1.01;
                else p *= 0.999;
                s.set(d, p, p, p, p, 1e8 - k);
            }
            m.put(s.symbol, s);
        }
        SeasonStudy st = new SeasonStudy(LocalDate.of(2020, 1, 1), days);
        st.init(m);
        st.prepare(Set.of());
        return st;
    }

    @Test
    void leveragedTokensAreExcluded() {
        Set<String> all = Set.of("BTCUSDT", "BTCUPUSDT", "JUPUSDT", "ETHUSDT", "ETHBULLUSDT", "BULLUSDT");
        assertThat(SeasonStudy.leveraged("BTCUPUSDT", all)).isTrue();
        assertThat(SeasonStudy.leveraged("ETHBULLUSDT", all)).isTrue();
        assertThat(SeasonStudy.leveraged("BULLUSDT", all)).isTrue();
        assertThat(SeasonStudy.leveraged("JUPUSDT", all)).isFalse();
    }

    @Test
    void altSeasonIsDetectedAndCapturedAfterOneDayLag() {
        SeasonStudy st = synthetic(400);
        assertThat(st.top[300]).hasSize(30);
        assertThat(st.regime[1][150]).isFalse();
        assertThat(st.regime[1][260]).isTrue();
        assertThat(st.regime[3][300]).isTrue(); // 所有山寨 90 天都跑赢 BTC：山寨季指数 100%
        st.run(100);
        SeasonStudy.Result basket = st.results.get(3); // 判断 1 → 前 50 等权
        double[] nav = basket.nav();
        // 信号晚一天，开启后才开始赚：最后净值远高于 1，但低于从第 200 天就持有的 1.01^199
        assertThat(nav[399]).isGreaterThan(3.0).isLessThan(Math.pow(1.01, 199));
        // 开启之前一直拿现金，净值不动
        assertThat(nav[150]).isEqualTo(1.0);
        assertThat(st.markdown(100)).contains("山寨季轮动").contains("一直持有 BTC");
    }
}
