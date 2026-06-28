package com.choucj.aiaggregator.content.filter.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 内容筛选器配置入口 (Story 2.3b).
 *
 * <p>仅注册 {@link FilterProperties} 到 Spring 容器, 供
 * {@link com.choucj.aiaggregator.content.filter.CommentFilter} /
 * {@link com.choucj.aiaggregator.content.filter.InnovationFilter}
 * 构造器注入. 无 {@code @Bean} 定义 — 筛选器本身用 {@code @Component} 自行注册.
 *
 * <p>模仿 {@code TwscrapeConfig} / {@code FxTwitterConfig} 模式.
 */
@Configuration
@EnableConfigurationProperties(FilterProperties.class)
public class FilterConfig {
}
