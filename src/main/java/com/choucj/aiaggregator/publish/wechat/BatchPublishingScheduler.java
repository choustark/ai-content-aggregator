package com.choucj.aiaggregator.publish.wechat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 批量发布调度器: 每天早上扫描本地发布池中到期文章, 推送到微信草稿.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Conditional(BatchPublishingScheduler.OnWeChatAndBatchEnabled.class)
public class BatchPublishingScheduler {

    private final ArticlePublicationWorkflow publicationWorkflow;

    /**
     * 默认每天 08:00 触发批量发布.
     */
    @Scheduled(cron = "${wechat.mp.publishing.batch-cron:0 0 8 * * ?}")
    public void processBatch() {
        try {
            ArticlePublicationWorkflow.PublishDueResult result = publicationWorkflow.publishDueArticles();
            log.info("批量发布完成: total={}, success={}, failure={}",
                    result.total(), result.success(), result.failure());
        } catch (Exception e) {
            log.error("批量发布失败(调度器存活, 等待下次 cron 触发)", e);
        }
    }

    /**
     * 双条件: wechat.mp.enabled=true 且 wechat.mp.publishing.batch-enabled=true.
     */
    static class OnWeChatAndBatchEnabled extends AllNestedConditions {
        OnWeChatAndBatchEnabled() {
            super(ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty(prefix = "wechat.mp", name = "enabled", havingValue = "true")
        static class OnWeChatEnabled {
        }

        @ConditionalOnProperty(prefix = "wechat.mp.publishing", name = "batch-enabled",
                havingValue = "true", matchIfMissing = true)
        static class OnBatchEnabled {
        }
    }
}
