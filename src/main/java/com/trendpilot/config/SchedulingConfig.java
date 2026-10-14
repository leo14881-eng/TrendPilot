package com.trendpilot.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 定时任务开关；测试中关闭（trendpilot.scheduling.enabled=false）。 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "trendpilot.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
