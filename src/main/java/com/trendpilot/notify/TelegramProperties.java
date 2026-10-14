package com.trendpilot.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Telegram 配置。botToken 和 chatId 只放在本机 config/application-local.yml，不进代码仓库。
 */
@ConfigurationProperties(prefix = "trendpilot.telegram")
public record TelegramProperties(String botToken, String chatId, String apiBase) {

    public TelegramProperties {
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = "https://api.telegram.org";
        }
    }

    public boolean configured() {
        return botToken != null && !botToken.isBlank() && chatId != null && !chatId.isBlank();
    }
}
