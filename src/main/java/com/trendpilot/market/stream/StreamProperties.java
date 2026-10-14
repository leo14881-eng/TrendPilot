package com.trendpilot.market.stream;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trendpilot.stream")
/** debugLog：是否把收到的原始消息写到 logs/ws-raw.log（排查用，默认关闭，见 RawFrameLog）。 */
public record StreamProperties(boolean enabled, String binanceUrl, String bybitUrl, boolean debugLog) {
}
