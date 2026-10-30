package com.trendpilot.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** 新增的永续 premiumIndex 读取：走永续地址（不是现货 base-url），解析标记价格和资金费率；现货接口不受影响。 */
class BinancePremiumIndexTest {

    @Test
    void readsMarkPriceAndFundingRateFromFuturesHost() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo("https://fapi.example/fapi/v1/premiumIndex?symbol=ETHUSDT"))
                .andRespond(withSuccess("{\"symbol\":\"ETHUSDT\",\"markPrice\":\"3012.45\",\"lastFundingRate\":\"0.00010000\","
                        + "\"nextFundingTime\":1761811200000}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://spot.example/api/v3/ticker/24hr"))
                .andRespond(withSuccess("[{\"symbol\":\"BTCUSDT\",\"lastPrice\":\"1\",\"priceChangePercent\":\"2\",\"quoteVolume\":\"3\"}]",
                        MediaType.APPLICATION_JSON));
        BinanceClient c = new BinanceClient(new BinanceProperties("https://spot.example", 1), b);
        BinanceClient.PremiumIndex p = c.premiumIndex("https://fapi.example", "ETHUSDT");
        assertThat(p.markPrice()).isEqualTo(3012.45);
        assertThat(p.lastFundingRate()).isEqualTo(0.0001);
        assertThat(p.nextFundingTime()).isEqualTo(1761811200000L);
        assertThat(c.tickers24h()).hasSize(1);
        server.verify();
    }

    @Test
    void missingMarkPriceIsAnError() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        server.expect(requestTo("https://fapi.example/fapi/v1/premiumIndex?symbol=ETHUSDT"))
                .andRespond(withSuccess("{\"symbol\":\"ETHUSDT\"}", MediaType.APPLICATION_JSON));
        BinanceClient c = new BinanceClient(new BinanceProperties("https://spot.example", 1), b);
        assertThatThrownBy(() -> c.premiumIndex("https://fapi.example", "ETHUSDT")).isInstanceOf(MarketDataException.class);
    }
}
