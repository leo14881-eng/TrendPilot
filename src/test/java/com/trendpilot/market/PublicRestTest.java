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
        assertThatThrownBy(() -> rest.get(() -> http.get().uri("/a"), "a")).hasMessageContaining("IP 被封禁暂停中");
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
        // 熔断中：不再请求；说明是网络不通而不是限流
        assertThatThrownBy(() -> rest.get(() -> http.get().uri("/a"), "a"))
                .hasMessageContaining("连续不通").hasMessageNotContaining("限流暂停中");
        server.verify();
    }

    @Test
    void rateLimitPauseIsLabelledAsRateLimit() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestClient http = b.baseUrl("https://x.test").build();
        PublicRest rest = new PublicRest("X", 2, 0);
        HttpHeaders h = new HttpHeaders();
        h.add("Retry-After", "100");
        server.expect(ExpectedCount.once(), requestTo("https://x.test/a")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(h));
        assertThatThrownBy(() -> rest.get(() -> http.get().uri("/a"), "a")).hasMessageContaining("限流暂停中");
        server.verify();
    }

    @Test
    void failureMessageCarriesTheCause() {
        RestClient.Builder b = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        RestClient http = b.baseUrl("https://x.test").build();
        PublicRest rest = new PublicRest("X", 2, 0);
        server.expect(ExpectedCount.times(3), requestTo("https://x.test/a")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> rest.get(() -> http.get().uri("/a"), "a")).hasMessageContaining("HTTP 502");
        org.assertj.core.api.Assertions.assertThat(PublicRest.reason(
                new org.springframework.web.client.ResourceAccessException("I/O error", new java.net.UnknownHostException("api.bybit.com"))))
                .isEqualTo("网络不通（域名解析失败）：api.bybit.com");
        org.assertj.core.api.Assertions.assertThat(PublicRest.reason(
                new RuntimeException("I/O error", new java.nio.channels.UnresolvedAddressException())))
                .isEqualTo("网络不通（域名解析失败）");
        org.assertj.core.api.Assertions.assertThat(PublicRest.reason(null)).isEqualTo("未知");
    }
}
