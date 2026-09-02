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

        /**
         * DeepSeek 对话模型名.
         *
         * <p>可配置: 平台旧模型下线时无需改代码 (历史教训: {@code glm-4-plus} 硬编码下线后
         * 返回 400 InvalidRequestException, 见 {@link Glm#modelName}).
         */
        private String modelName = "deepseek-chat";
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

        /**
         * GLM 对话模型名, 默认 {@code glm-4.7} (智谱在售旗舰).
         *
         * <p>历史教训: 曾硬编码 {@code glm-4-plus}, 该模型下线后上游返回 400 → langchain4j
         * {@code InvalidRequestException}, 多模型投票的 GLM 分支全量失败. 现改为可配置,
         * 平台旧模型下线时只需改 {@code api-keys.yml} / 环境变量.
         */
        private String modelName = "glm-4.7";
    }
}
