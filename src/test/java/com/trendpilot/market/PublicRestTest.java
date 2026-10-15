package com.trendpilot.market;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PublicRestTest {

    @Test
    void ipBanIsHonouredInFullAndFailsFastAfterwards() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestClient http = b.baseUrl("https://x.test").build();
        PublicRest rest = new PublicRest("X", 2, 0);
        HttpHeaders h = new HttpHeaders();
        h.add("Retry-After", "3600");
        server.expect(ExpectedCount.once(), requestTo("https://x.test/a")).andRespond(withStatus(HttpStatus.I_AM_A_TEAPOT).headers(h));
        assertThatThrownBy(() -> rest.get(() -> http.get().uri("/a"), "a")).hasMessageContaining("418");
        // 封禁 1 小时（超过原来 2 分钟的上限）：之后的请求不再发出，直接失败
        assertThatThrownBy(() -> rest.get(() -> http.get().uri("/a"), "a")).hasMessageContaining("限流暂停中");
        server.verify();
    }

    @Test
    void venueOutageOpensCircuitInsteadOfRetryingEverySymbol() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestClient http = b.baseUrl("https://x.test").build();
        PublicRest rest = new PublicRest("X", 2, 0);
        server.expect(ExpectedCount.times(PublicRest.BREAKER_FAILURES), requestTo("https://x.test/a")).andRespond(withServerError());
        assertThatThrownBy(() -> rest.get(() -> http.get().uri("/a"), "a")).hasMessageContaining("多次失败");
        assertThatThrownBy(() -> rest.get(() -> http.get().uri("/a"), "a")).isInstanceOf(MarketDataException.class);
        // 熔断中：不再请求
        assertThatThrownBy(() -> rest.get(() -> http.get().uri("/a"), "a")).hasMessageContaining("限流暂停中");
        server.verify();
    }
}
