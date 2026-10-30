package com.trendpilot.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;


import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** K 线价格检查：0、负数、非有限、最高 / 最低和开收矛盾的 K 线都按缺失处理，不入库、不进规则。 */
class KlineValidityTest {

    static Kline k(double o, double h, double l, double c) {
        return new Kline(0, o, h, l, c, 100, 899_999);
    }

    @Test
    void onlyConsistentPositivePricesAreValid() {
        assertThat(k(100, 101, 99, 100.5).valid()).isTrue();
        assertThat(k(100, 100, 100, 100).valid()).isTrue();
        assertThat(k(0, 0, 0, 0).valid()).isFalse();
        assertThat(k(100, 101, 0, 100).valid()).isFalse();
        assertThat(k(100, 101, -1, 100).valid()).isFalse();
        assertThat(k(100, 99, 98, 100).valid()).isFalse();      // 最高价低于开盘
        assertThat(k(100, 101, 100.5, 100).valid()).isFalse();  // 最低价高于收盘
        assertThat(k(Double.NaN, 101, 99, 100).valid()).isFalse();
        assertThat(k(100, Double.POSITIVE_INFINITY, 99, 100).valid()).isFalse();
        assertThat(new Kline(0, 100, 101, 99, 100, Double.NaN, 1).valid()).isFalse();
        assertThat(new Kline(0, 100, 101, 99, 100, -5, 1).valid()).isFalse();
    }

    @Test
    void restBarsWithBrokenPricesAreDropped() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BinanceClient client = new BinanceClient(new BinanceProperties("https://api.binance.com", 2), builder);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.binance.com/api/v3/klines")))
                .andRespond(withSuccess("""
                        [[0,"100","101","99","100.5","1",899999,"1000"],
                         [900000,"0","0","0","0","1",1799999,"0"],
                         [1800000,"100","101","99","100.5","1",2699999,"1000"]]""", MediaType.APPLICATION_JSON));
        List<Kline> out = client.klines("BTCUSDT", "15m", null, 3);
        assertThat(out).extracting(Kline::openTime).containsExactly(0L, 1_800_000L);
    }
}
