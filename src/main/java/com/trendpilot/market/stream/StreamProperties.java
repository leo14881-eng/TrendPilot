package com.trendpilot.market.stream;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trendpilot.stream")
public record StreamProperties(boolean enabled, String binanceUrl, String bybitUrl) {
}
