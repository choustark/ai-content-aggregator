package com.choucj.aiaggregator.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * LLM 模型配置属性.
 *
 * <p>支持多模型 (deepseek / glm), 后续 Story 5.4 多模型投票时按需扩展为 Map 注入.
 * 两个模型的 api-key 均强制 {@link NotBlank} 校验, 启动期即发现配置缺失.
 *
 * <p>嵌套字段使用 {@link Valid} 触发级联校验, 否则 Spring Boot 不会校验子对象的 @NotBlank.
 */
@ConfigurationProperties(prefix = "llm")
@Validated
@Data
public class LlmProperties {

    @Valid
    private DeepSeek deepseek = new DeepSeek();

    @Valid
    private Glm glm = new Glm();

    @Data
    @ToString(exclude = "apiKey")
    public static class DeepSeek {

        @NotBlank(message = "llm.deepseek.api-key must be configured (set LLM_DEEPSEEK_API_KEY env var or fill api-keys.yml)")
        private String apiKey;

        private String baseUrl = "https://api.deepseek.com";
    }

    @Data
    @ToString(exclude = "apiKey")
    public static class Glm {

        @NotBlank(message = "llm.glm.api-key must be configured (set LLM_GLM_API_KEY env var or fill api-keys.yml)")
        private String apiKey;

        /**
         * GLM OpenAI 兼容 API base-url.
         *
         * <p>迁移提示: 旧值 {@code https://open.bigmodel.cn} 会导致 OpenAI 兼容路径返回 405；
         * 已显式配置旧值的环境需同步改为 {@code https://open.bigmodel.cn/api/paas/v4}.
         */
        private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";
    }
}
