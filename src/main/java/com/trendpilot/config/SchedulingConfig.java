package com.trendpilot.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

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

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        return scheduler();
    }

    static ThreadPoolTaskScheduler scheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(POOL_SIZE);
        s.setThreadNamePrefix("job-");
        s.setWaitForTasksToCompleteOnShutdown(true);   // 停机时等正在跑的 Discovery / Monitor 结束
        s.setAwaitTerminationSeconds(90);
        return s;
    }
}
