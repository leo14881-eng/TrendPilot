package com.trendpilot.health;

import java.io.IOException;
import java.time.Instant;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;

/**
 * 给所有 RestClient（行情、研究、BTC 宏观数据、Telegram）加上请求统计和超时。
 * 超时是硬性要求：没有超时时一个卡住的连接会让定时任务永远不返回，8 个任务线程占满后所有任务（包括自检）都停。
 */
@Configuration
public class ApiStatsConfig {

    @Bean
    RestClientCustomizer timeoutCustomizer(
            @org.springframework.beans.factory.annotation.Value("${trendpilot.http.connect-timeout-ms:5000}") long connectMs,
            @org.springframework.beans.factory.annotation.Value("${trendpilot.http.read-timeout-ms:30000}") long readMs) {
        return builder -> builder.requestFactory(requestFactory(connectMs, readMs));
    }

    public static org.springframework.http.client.JdkClientHttpRequestFactory requestFactory(long connectMs, long readMs) {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(connectMs))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
        var f = new org.springframework.http.client.JdkClientHttpRequestFactory(client);
        f.setReadTimeout(java.time.Duration.ofMillis(readMs));
        return f;
    }

    @Bean
    RestClientCustomizer apiStatsCustomizer(ApiStats stats) {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            String host = request.getURI().getHost();
            try {
                ClientHttpResponse response = execution.execute(request, body);
                stats.record(host, response.getStatusCode().value(), Instant.now());
                return response;
            } catch (IOException e) {
                stats.record(host, -1, Instant.now());
                throw e;
            }
        });
    }
}
