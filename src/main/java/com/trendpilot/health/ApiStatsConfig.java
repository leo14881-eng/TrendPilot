package com.trendpilot.health;

import java.io.IOException;
import java.time.Instant;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;

/** 给所有 RestClient（行情、研究、BTC 宏观数据、Telegram）加上请求统计。 */
@Configuration
public class ApiStatsConfig {

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
