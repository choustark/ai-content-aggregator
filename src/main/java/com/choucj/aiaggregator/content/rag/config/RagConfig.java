package com.choucj.aiaggregator.content.rag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 检索侧配置入口 (Story 5.2).
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {
}
