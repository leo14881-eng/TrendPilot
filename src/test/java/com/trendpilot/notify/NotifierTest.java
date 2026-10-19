package com.trendpilot.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.trendpilot.support.TestData;

/** 通知：长消息分段、失败重试只补发没发出的分段、错误信息遮住 Token。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class NotifierTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
    }

    @Autowired JdbcClient jdbc;

    @Test
    void splitsLongMessagesByLine() {
        String line = "x".repeat(1500);
        List<String> parts = Notifier.split(String.join("\n", line, line, line, line), Notifier.MAX_PART);
        assertThat(parts).hasSize(2);
        assertThat(parts).allMatch(p -> p.length() <= Notifier.MAX_PART);
        assertThat(Notifier.split("x".repeat(9000), 4000)).hasSize(3);
        assertThat(Notifier.split("短消息", 4000)).containsExactly("短消息");
    }

    @Test
    void sendsDocumentAsMultipartOnceAndRetriesAfter429() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Notifier n = new Notifier(jdbc, new TelegramProperties("1:t", "9", "https://tg.test"), builder);
        String url = "https://tg.test/bot1%3At/sendDocument";
        server.expect(ExpectedCount.once(), requestTo(url))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS)
                        .body("{\"ok\":false,\"parameters\":{\"retry_after\":0}}"));
        server.expect(ExpectedCount.once(), requestTo(url))
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("filename=\"daily.md\""),
                        org.hamcrest.Matchers.containsString("name=\"chat_id\""),
                        org.hamcrest.Matchers.containsString("# 标题"))))
                .andRespond(withSuccess());
        assertThat(n.sendDocument("doc-test", "daily.md", "# 标题\n内容", "说明")).isTrue();
        assertThat(n.sendDocument("doc-test", "daily.md", "# 标题\n内容", "说明")).isFalse();   // 同一事件只发一次
        server.verify();
        // 附件不进入正文的失败重试
        assertThat(n.retryFailed(Instant.now())).isZero();
        assertThat(Notifier.retryAfterSeconds("{\"parameters\":{\"retry_after\": 12}}")).isEqualTo(12);
        assertThat(Notifier.retryAfterSeconds("x")).isEqualTo(5);
    }

    @Test
    void masksTokenInErrors() {
        assertThat(Notifier.mask("I/O error on POST request for \"https://api.telegram.org/bot123%3AAbC_d-9/sendMessage\"")).doesNotContain("AbC_d");
        assertThat(Notifier.mask("bot123:AbC_d-9/x"))
                .doesNotContain("123:AbC").contains("bot***");
    }

    @Test
    void failedPartsAreRetriedOnce() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Notifier n = new Notifier(jdbc, new TelegramProperties("1:t", "9", "https://tg.test"), builder);
        String url = "https://tg.test/bot1%3At/sendMessage";
        String text = "a".repeat(3000) + "\n" + "b".repeat(3000);
        server.expect(ExpectedCount.once(), requestTo(url)).andRespond(withSuccess());
        server.expect(ExpectedCount.once(), requestTo(url)).andRespond(withServerError());
        assertThat(n.send("retry-test", text)).isFalse();
        server.verify();

        server.reset();
        server.expect(ExpectedCount.once(), requestTo(url)).andRespond(withSuccess());   // 只补发第 2 段
        assertThat(n.retryFailed(Instant.now())).isEqualTo(1);
        server.verify();
        assertThat(jdbc.sql("SELECT status FROM notification_outbox WHERE event_key = 'retry-test'")
                .query(String.class).single()).isEqualTo("SENT");
        assertThat(n.retryFailed(Instant.now())).isZero();
    }

    @Test
    void standbyOnlyRecordsButSwitchNoticesStillGoOut() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Notifier n = new Notifier(jdbc, new TelegramProperties("1:t", "9", "https://tg.test"), builder);
        n.gate(() -> false);
        assertThat(n.send("standby-test", "x")).isFalse();
        assertThat(jdbc.sql("SELECT status FROM notification_outbox WHERE event_key = 'standby-test'")
                .query(String.class).single()).isEqualTo("STANDBY");
        server.expect(ExpectedCount.once(), requestTo("https://tg.test/bot1%3At/sendMessage")).andRespond(withSuccess());
        assertThat(n.sendAlways("standby-switch", "⏸")).isTrue();
        server.verify();
    }

    @Test
    void strandedPreparedIsResentAfterRestart() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Notifier n = new Notifier(jdbc, new TelegramProperties("1:t", "9", "https://tg.test"), builder);
        Instant now = Instant.now();
        // 写入 Outbox 后服务被重启：一条 10 分钟前的 PREPARED，一条刚写入的 PREPARED（可能正在发送，不动它）
        jdbc.sql("INSERT INTO notification_outbox(event_key, channel, text, status, created_at) VALUES ('stranded-old', 'TELEGRAM', '⚠️ 旧告警', 'PREPARED', :t)")
                .param("t", now.minus(java.time.Duration.ofMinutes(10)).toString()).update();
        jdbc.sql("INSERT INTO notification_outbox(event_key, channel, text, status, created_at) VALUES ('stranded-new', 'TELEGRAM', '新告警', 'PREPARED', :t)")
                .param("t", now.minus(java.time.Duration.ofMinutes(1)).toString()).update();
        server.expect(ExpectedCount.once(), requestTo("https://tg.test/bot1%3At/sendMessage"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.text")
                        .value(Notifier.PROJECT_TAG + Notifier.RESENT_PREFIX + "⚠️ 旧告警"))
                .andRespond(withSuccess());
        assertThat(n.retryFailed(now)).isEqualTo(1);
        server.verify();
        assertThat(jdbc.sql("SELECT status FROM notification_outbox WHERE event_key = 'stranded-old'").query(String.class).single()).isEqualTo("SENT");
        assertThat(jdbc.sql("SELECT status FROM notification_outbox WHERE event_key = 'stranded-new'").query(String.class).single()).isEqualTo("PREPARED");
    }
}
