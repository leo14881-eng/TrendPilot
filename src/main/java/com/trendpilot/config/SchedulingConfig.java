package com.trendpilot.config;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BooleanSupplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.trendpilot.lease.ActiveLease;

/**
 * 定时任务开关；测试中关闭（trendpilot.scheduling.enabled=false）。
 * 定时任务用多线程：研究资料、回放这类慢任务不会挡住 Monitor 和 FastWatch。
 * 同一个任务不会和自己重叠；写组合的 Discovery 与 Monitor 之间仍由 PortfolioLock 互斥。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "trendpilot.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

    static final int POOL_SIZE = 8;

    /** 本机待命（另一台机器在运行，见 ActiveLease）时，所有定时任务跳过本次执行，但照常排下一次。 */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler(ObjectProvider<ActiveLease> lease) {
        return scheduler(() -> {
            ActiveLease l = lease.getIfAvailable();
            return l == null || l.active();
        });
    }

    static ThreadPoolTaskScheduler scheduler() {
        return scheduler(() -> true);
    }

    static ThreadPoolTaskScheduler scheduler(BooleanSupplier active) {
        ThreadPoolTaskScheduler s = new LeaseAwareScheduler(active);
        s.setPoolSize(POOL_SIZE);
        s.setThreadNamePrefix("job-");
        s.setWaitForTasksToCompleteOnShutdown(true);   // 停机时等正在跑的 Discovery / Monitor 结束
        s.setAwaitTerminationSeconds(90);
        return s;
    }

    /**
     * 待命判断包在任务本身外面，而不是包在调度器的执行单元外面：
     * 周期任务（cron、fixedDelay、fixedRate）的"排下一次"是执行单元自己做的，
     * 如果待命时连执行单元一起跳过，这个任务就再也不会被排上（2026-10-18：待命期间经过一次 Monitor 时间点，
     * 接管运行后 Monitor 一直不执行）。现在待命时只跳过任务内容，下一次照常排。
     */
    static final class LeaseAwareScheduler extends ThreadPoolTaskScheduler {
        private final transient BooleanSupplier active;

        LeaseAwareScheduler(BooleanSupplier active) {
            this.active = active;
        }

        private Runnable gate(Runnable task) {
            return () -> {
                if (active.getAsBoolean()) task.run();
            };
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return super.schedule(gate(task), trigger);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            return super.schedule(gate(task), startTime);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            return super.scheduleAtFixedRate(gate(task), startTime, period);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return super.scheduleAtFixedRate(gate(task), period);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            return super.scheduleWithFixedDelay(gate(task), startTime, delay);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return super.scheduleWithFixedDelay(gate(task), delay);
        }
    }
}
