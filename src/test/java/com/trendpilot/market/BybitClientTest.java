package com.trendpilot.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Bybit v5 响应解析：K 线倒序转升序、涨跌幅换算成百分比、retCode 与 403 处理。 */
class BybitClientTest {

    MockRestServiceServer server;
    BybitClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new BybitClient(new BybitProperties("https://api.bybit.com", 2), builder);
    }

    private void respond(String path, String json) {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.bybit.com" + path)))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    @Test
    void parsesInstrumentsAndTickers() {
        respond("/v5/market/instruments-info", """
                {"retCode":0,"result":{"list":[
                  {"symbol":"ABCUSDT","baseCoin":"ABC","quoteCoin":"USDT","status":"Trading"},
                  {"symbol":"AAPLXUSDT","baseCoin":"AAPLX","quoteCoin":"USDT","status":"Trading","symbolType":"xStocks"},
                  {"symbol":"OLDUSDT","baseCoin":"OLD","quoteCoin":"USDT","status":"Closed"}],"nextPageCursor":""}}""");
        List<SymbolInfo> symbols = client.symbols();
        assertThat(symbols).hasSize(3);
        assertThat(symbols.get(0).trading()).isTrue();
        assertThat(symbols.get(1).tags()).contains("xStocks");
        assertThat(symbols.get(2).trading()).isFalse();

        server.reset();
        respond("/v5/market/tickers", """
                {"retCode":0,"result":{"list":[{"symbol":"ABCUSDT","lastPrice":"1.5","price24hPcnt":"0.0523","turnover24h":"123456.7"}]}}""");
        Ticker24h t = client.tickers24h().getFirst();
        assertThat(t.priceChangePct()).isEqualTo(5.23, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(t.quoteVolume()).isEqualTo(123456.7);
        // 最优买卖价复用同一次响应，不再请求（MockRestServiceServer 只准备了一次响应）
        assertThat(client.bookTickers()).hasSize(1);
        server.verify();
    }

    @Test
    void rateLimitPausesAllRequestsAndFailsFastWhenPauseIsLong() {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.add("Retry-After", "60");
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.bybit.com/v5/market/orderbook")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(h));
        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> client.depth("ABCUSDT", 50)).isInstanceOf(MarketDataException.class)
                .hasMessageContaining("限流暂停");
        assertThatThrownBy(() -> client.depth("XYZUSDT", 50)).hasMessageContaining("限流暂停");
        assertThat(System.currentTimeMillis() - start).isLessThan(10_000);
        server.verify(); // 暂停期间没有再发请求
    }

    @Test
    void klinesAreReturnedAscendingWithCloseTime() {
        respond("/v5/market/kline", """
                {"retCode":0,"result":{"list":[
                  ["1700000900000","2","3","1","2.5","10","25"],
                  ["1700000000000","1","2","0.5","2","10","20"]]}}""");
        List<Kline> k = client.klines("ABCUSDT", "15m", null, 2);
        assertThat(k).extracting(Kline::openTime).containsExactly(1700000000000L, 1700000900000L);
        assertThat(k.getFirst().quoteVolume()).isEqualTo(20);
        assertThat(k.getFirst().closeTime()).isEqualTo(1700000000000L + 15 * 60_000 - 1);
    }

    @Test
    void retCodeErrorAndRegionBlockFailWithoutFakeData() {
        respond("/v5/market/orderbook", """
                {"retCode":10001,"retMsg":"params error","result":{}}""");
        assertThatThrownBy(() -> client.depth("ABCUSDT", 500)).isInstanceOf(MarketDataException.class);

        server.reset();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.bybit.com/v5/market/tickers")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> client.tickers24h()).isInstanceOf(MarketDataException.class)
                .hasMessageContaining("403");
    }
}
