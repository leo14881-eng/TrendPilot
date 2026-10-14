package com.trendpilot.market;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trendpilot.bybit")
public record BybitProperties(String baseUrl, int maxConcurrency) {
}
