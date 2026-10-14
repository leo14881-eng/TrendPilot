package com.trendpilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trendpilot.notify.Notifier;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@org.springframework.test.context.ActiveProfiles("test")
class ApplicationTest {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
        r.add("trendpilot.telegram.bot-token", () -> "");
        r.add("trendpilot.telegram.chat-id", () -> "");
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Notifier notifier;

    @Test
    void startupIsRecorded() {
        Integer runs = jdbc.sql("SELECT COUNT(*) FROM job_run WHERE job = 'STARTUP'").query(Integer.class).single();
        assertThat(runs).isEqualTo(1);
    }

    @Test
    void unconfiguredTelegramOnlyPreparesAndDeduplicates() {
        assertThat(notifier.send("test:1", "hello")).isFalse();
        assertThat(notifier.send("test:1", "hello again")).isFalse();

        String status = jdbc.sql("SELECT status FROM notification_outbox WHERE event_key = 'test:1'")
                .query(String.class).single();
        Integer count = jdbc.sql("SELECT COUNT(*) FROM notification_outbox WHERE event_key = 'test:1'")
                .query(Integer.class).single();
        assertThat(status).isEqualTo("PREPARED");
        assertThat(count).isEqualTo(1);
    }
}
