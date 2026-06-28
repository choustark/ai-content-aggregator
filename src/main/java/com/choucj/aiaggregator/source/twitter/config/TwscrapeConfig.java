package com.choucj.aiaggregator.source.twitter.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * twscrape CLI 客户端配置入口.
 *
 * <p>仅注册 {@link TwscrapeProperties} 到 Spring 容器. {@link com.choucj.aiaggregator.source.twitter.client.TwscrapeClient}
 * 不需要专用 RestClient Bean(走 {@code ProcessBuilder} 子进程, 不通过 HTTP), 因此本配置类比
 * {@link FxTwitterConfig} 简化 — 无 {@code @Bean} 定义.
 *
 * <p>架构 delta (Story 2.2b): twscrape 凭据(cookie / 账号)不通过 {@code @ConfigurationProperties}
 * 绑定, 由运行环境 {@code twscrape add_accounts} 命令维护登录态.
 */
@Configuration
@EnableConfigurationProperties(TwscrapeProperties.class)
public class TwscrapeConfig {
}
