package com.choucj.aiaggregator.publish.wechat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 原帖复现配置 Bean 装配入口.
 *
 * <p>单独拆分类以遵守项目「配置类按功能拆分」规则, 避免把 Epic 8 的模式配置塞进其他 feature flag
 * 或 WeChat 发布配置中.
 *
 * <p>引用源: Story 8.3 / project-context 配置类拆分规则.
 */
@Configuration
@EnableConfigurationProperties(OriginalPostProperties.class)
public class OriginalPostConfig {
}
