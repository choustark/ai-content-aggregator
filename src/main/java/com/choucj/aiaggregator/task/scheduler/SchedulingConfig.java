package com.choucj.aiaggregator.task.scheduler;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 激活 Spring {@code @Scheduled} 注解扫描(Story 1.6).
 *
 * <p>{@link EnableScheduling} 必须显式声明, 否则 {@link ContentScheduler} 的
 * {@code @Scheduled(cron = "${schedule.cron:...")} 注解不会生效.
 *
 * <p>使用 Spring Boot 默认的 {@code ThreadPoolTaskScheduler} 即可,
 * MVP 阶段不自定义线程池. 未来若需要多调度器隔离或并发控制, 再加 {@code @Bean TaskScheduler}.
 *
 * <p>引用源: Story 1.6 创建.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
