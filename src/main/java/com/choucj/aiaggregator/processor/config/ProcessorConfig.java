package com.choucj.aiaggregator.processor.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Twitter 处理流水线配置入口 (Story 2.6).
 *
 * <p>仅注册 {@link ProcessorProperties} 到 Spring 容器, 供
 * {@link com.choucj.aiaggregator.processor.TwitterProcessor} 构造器注入.
 * 无 {@code @Bean} 定义 — Processor 本身用 {@code @Component} 自行注册.
 *
 * <p>模仿 {@code ArchiverConfig} (Story 2.5) / {@code RewriterConfig} (Story 2.4) /
 * {@code FilterConfig} (Story 2.3b) 模式.
 */
@Configuration
@EnableConfigurationProperties(ProcessorProperties.class)
public class ProcessorConfig {
}
