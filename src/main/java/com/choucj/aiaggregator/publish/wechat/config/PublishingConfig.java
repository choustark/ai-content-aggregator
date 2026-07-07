package com.choucj.aiaggregator.publish.wechat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 混合发布模式配置入口 (Story 3.4).
 *
 * <p>仅注册 {@link PublishingProperties} 到 Spring 容器, 供 PublishingModeDecider 与
 * BatchPublishingScheduler 构造器注入. 无 {@code @Bean} 定义 — 决策器/调度器本身用
 * {@code @Component} 自行注册.
 *
 * <p>模仿 ArchiverConfig (Story 2.5) / FilterConfig (Story 2.3b) / RewriterConfig (Story 2.4) 模式.
 * 与 WeChatConfig (Story 3.1) 同包但独立 — 两者职责不同: WeChatConfig 装配 SDK 层 (WxMpConfigStorage),
 * PublishingConfig 仅启用业务层配置绑定.
 *
 * <p>引用源: Story 3.4 (本 story).
 */
@Configuration
@EnableConfigurationProperties(PublishingProperties.class)
public class PublishingConfig {
}
