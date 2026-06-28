package com.choucj.aiaggregator.content.rewriter.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 内容改写器配置入口 (Story 2.4).
 *
 * <p>仅注册 {@link RewriterProperties} 到 Spring 容器, 供
 * {@link com.choucj.aiaggregator.content.rewriter.SingleModelRewriter} 构造器注入.
 * 无 {@code @Bean} 定义 — 改写器本身用 {@code @Component} 自行注册.
 *
 * <p>模仿 {@code FilterConfig} (Story 2.3b) / {@code TwscrapeConfig} (Story 2.2b) 模式.
 */
@Configuration
@EnableConfigurationProperties(RewriterProperties.class)
public class RewriterConfig {
}
