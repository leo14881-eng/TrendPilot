package com.trendpilot.notify;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 通知发送：先写 Outbox（PREPARED），再发 Telegram，成功记 SENT，失败记 FAILED。
 * 同一个 eventKey 只发一次。PREPARED 不代表用户已收到。
 */
@Service
public class Notifier {

    private static final Logger log = LoggerFactory.getLogger(Notifier.class);

    private final JdbcClient jdbc;
    private final TelegramProperties props;
    private final RestClient http;

    public Notifier(JdbcClient jdbc, TelegramProperties props, RestClient.Builder builder) {
        this.jdbc = jdbc;
        this.props = props;
        this.http = builder.baseUrl(props.apiBase()).build();
    }

    /** @return 本次是否真正发出（重复事件或未配置时返回 false） */
    public boolean send(String eventKey, String text) {
        int inserted = jdbc.sql("""
                INSERT OR IGNORE INTO notification_outbox(event_key, channel, text, status, created_at)
                VALUES (:key, 'TELEGRAM', :text, 'PREPARED', :now)
                """)
                .param("key", eventKey)
                .param("text", text)
                .param("now", Instant.now().toString())
                .update();
        if (inserted == 0) {
            log.debug("通知已存在，跳过：{}", eventKey);
            return false;
        }
        if (!props.configured()) {
            log.warn("Telegram 未配置，通知只记录在 Outbox：{}", eventKey);
            return false;
        }
        try {
            http.post()
                    .uri("/bot{token}/sendMessage", props.botToken())
                    .body(Map.of("chat_id", props.chatId(), "text", text))
                    .retrieve()
                    .toBodilessEntity();
            mark(eventKey, "SENT", null);
            return true;
        } catch (RestClientException e) {
            log.error("Telegram 发送失败：{}", eventKey, e);
            mark(eventKey, "FAILED", e.getMessage());
            return false;
        }
    }

    private void mark(String eventKey, String status, String error) {
        jdbc.sql("UPDATE notification_outbox SET status = :s, last_error = :e, updated_at = :now WHERE event_key = :key")
                .param("s", status)
                .param("e", error)
                .param("now", Instant.now().toString())
                .param("key", eventKey)
                .update();
    }
}
