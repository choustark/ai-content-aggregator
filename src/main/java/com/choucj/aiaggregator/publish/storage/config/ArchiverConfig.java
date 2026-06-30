package com.choucj.aiaggregator.publish.storage.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Markdown 归档器配置入口 (Story 2.5).
 *
 * <p>仅注册 {@link ArchiverProperties} 到 Spring 容器, 供
 * {@link com.choucj.aiaggregator.publish.storage.MarkdownArchiver} 构造器注入.
 * 无 {@code @Bean} 定义 — 归档器本身用 {@code @Component} 自行注册.
 *
 * <p>模仿 {@code RewriterConfig} (Story 2.4) / {@code FilterConfig} (Story 2.3b) 模式.
 */
@Configuration
@EnableConfigurationProperties(ArchiverProperties.class)
public class ArchiverConfig {
}
