package com.trendpilot.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.trendpilot.support.TestData;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class HealthJobTest {

    static final Path dataDir = TestData.dataDir();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("trendpilot.data-dir", () -> dataDir.toString());
    }

    @Autowired
    HealthJob health;

    @Autowired
    JobRuns runs;

    @Autowired
    JdbcClient jdbc;

    @Test
    void graceAfterStartupThenAlertOnceThenRecover() {
        Instant startup = runs.lastStarted(StartupCheck.JOB).orElseThrow();

        // 刚启动，首轮 Discovery（每小时 :17）还没到：不报异常
        assertThat(health.problems(startup.plus(Duration.ofMinutes(10)))).isEmpty();
        health.check(startup.plus(Duration.ofMinutes(10)));
        assertThat(healthTexts()).isEmpty();

        // 超过宽限期仍没运行：只报一次
        Instant late = startup.plus(Duration.ofMinutes(100));
        health.check(late);
        health.check(late.plus(Duration.ofMinutes(30)));
        assertThat(healthTexts()).hasSize(1);
        assertThat(healthTexts().getFirst()).contains("MONITOR", "DISCOVERY", "还没有运行");

        // 两个任务都恢复：发一条恢复通知
        Instant back = late.plus(Duration.ofMinutes(40));
        runs.record(MonitorJob.JOB, "M", back, back, "SUCCESS", "N/A", "");
        runs.record(DiscoveryJob.JOB, "D", back, back, "SUCCESS", "COMPLETE", "");
        health.check(back.plus(Duration.ofMinutes(1)));
        health.check(back.plus(Duration.ofMinutes(5)));
        assertThat(healthTexts()).hasSize(2);
        assertThat(healthTexts().getLast()).contains("恢复正常");
    }

    private List<String> healthTexts() {
        return jdbc.sql("SELECT text FROM notification_outbox WHERE event_key LIKE 'health:%' ORDER BY id")
                .query(String.class).list();
    }
}
