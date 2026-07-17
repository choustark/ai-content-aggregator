package com.choucj.aiaggregator.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 功能开关配置属性.
 *
 * <p>绑定 {@code feature-flags.*}, 控制各内容源 (Twitter / GitHub / RAG / 多模型)
 * 与微信自动发布的启用状态. dev 默认全开 (除 Phase 4 功能), prod 通过
 * {@code feature-flags.yml} 覆盖.
 *
 * <p>本类不需要 {@link org.springframework.validation.annotation.Validated},
 * 因为所有字段均为布尔开关, 默认 {@code false} 即可, 无 mandatory 配置.
 */
@ConfigurationProperties(prefix = "feature-flags")
@Data
public class FeatureFlagsProperties {

    private Twitter twitter = new Twitter();
    private Github github = new Github();
    private Rag rag = new Rag();
    private MultiModel multiModel = new MultiModel();
    private Wechat wechat = new Wechat();

    @Data
    public static class Twitter {
        private boolean enabled = false;
        /** true=twscrape; false=FxTwitter (默认, 更稳定). */
        private boolean useTwscrape = false;
    }

    @Data
    public static class Github {
        private boolean enabled = false;
    }

    @Data
    public static class Rag {
        private boolean enabled = false;
        /** 批量 Embedding 异步开关；Story 5.1 默认关闭，避免无意放大 GLM 请求并发. */
        private boolean batchAsyncEnabled = false;
    }

    @Data
    public static class MultiModel {
        private boolean enabled = false;
    }

    @Data
    public static class Wechat {
        /** true=自动发布; false=人工审核 (默认). */
        private boolean autoPublish = false;
    }
}
