package com.trendpilot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class SchedulingConfigTest {

    @Test
    void slowJobDoesNotBlockMonitor() throws Exception {
        ThreadPoolTaskScheduler s = SchedulingConfig.scheduler();
        s.initialize();
        try {
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch monitorRan = new CountDownLatch(1);
            s.execute(() -> {          // 慢任务（例如研究资料刷新）卡住
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            });
            s.execute(monitorRan::countDown);   // Monitor 仍然能跑
            assertThat(monitorRan.await(2, TimeUnit.SECONDS)).isTrue();
            release.countDown();
        } finally {
            s.shutdown();
        }
    }
}
