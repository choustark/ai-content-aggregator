package com.choucj.aiaggregator.task.queue;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 任务队列配置入口(Story 10.5) — 注册 {@link RetryPolicyProperties} 到 Spring 容器.
 *
 * <p>{@code @ConfigurationPropertiesScan} 只扫 {@code common} 包, {@code task} 包的属性类
 * 必须经 {@code @EnableConfigurationProperties} 显式注册, 否则启动时 Bean 缺失.
 * 无 {@code @Bean} 定义 — TaskQueue/RetryDispatchScheduler 等组件用 {@code @Component} 自行注册.
 *
 * <p>模仿 {@code ProcessorConfig}(Story 2.6) 模式.
 *
 * <p>引用源: Story 10.5 创建(2026-09-22)。
 */
@Configuration
@EnableConfigurationProperties(RetryPolicyProperties.class)
public class TaskQueueConfig {
}
