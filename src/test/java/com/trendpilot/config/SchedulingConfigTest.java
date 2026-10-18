package com.trendpilot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.PeriodicTrigger;

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

    /** 待命期间经过几次执行时间点，接管运行后周期任务（Trigger、fixedDelay、fixedRate）都要继续执行。 */
    @Test
    void periodicJobsResumeAfterStandbyTurnsActive() throws Exception {
        AtomicBoolean active = new AtomicBoolean(false);
        ThreadPoolTaskScheduler s = SchedulingConfig.scheduler(active::get);
        s.initialize();
        try {
            AtomicInteger trigger = new AtomicInteger();
            AtomicInteger delay = new AtomicInteger();
            AtomicInteger rate = new AtomicInteger();
            s.schedule(trigger::incrementAndGet, new PeriodicTrigger(Duration.ofMillis(50)));   // cron 走的就是 Trigger
            s.scheduleWithFixedDelay(delay::incrementAndGet, Instant.now(), Duration.ofMillis(50));
            s.scheduleAtFixedRate(rate::incrementAndGet, Duration.ofMillis(50));
            Thread.sleep(400);   // 待命：执行点到了只跳过
            assertThat(trigger.get()).isZero();
            assertThat(delay.get()).isZero();
            assertThat(rate.get()).isZero();

            active.set(true);    // 接管运行
            Thread.sleep(400);
            assertThat(trigger.get()).isPositive();
            assertThat(delay.get()).isPositive();
            assertThat(rate.get()).isPositive();
        } finally {
            s.shutdown();
        }
    }
}
