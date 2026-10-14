package com.trendpilot.market;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trendpilot.binance")
public record BinanceProperties(String baseUrl, int maxConcurrency) {
}
