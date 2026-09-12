package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.monitoring.TaskMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 批量发布调度器: 高频轮询本地发布池中到期文章, 推送到微信草稿.
 *
 * <p>默认每 30 分钟触发 (batch-cron 可配). 扫描是到期制 (scheduledPublishAt &lt;= now),
 * 机器睡眠/应用未启动造成的积压在任意下次触发时一次性补发 — 替代原"每天 08:00 定点一次"
 * 的设计 (定点 cron 在本机睡眠场景会整批漏发且 Spring @Scheduled 无补跑机制).
 */
@Slf4j
@Component
@Conditional(BatchPublishingScheduler.OnWeChatAndBatchEnabled.class)
public class BatchPublishingScheduler {

    private final ArticlePublicationWorkflow publicationWorkflow;
    private final Optional<TaskMetrics> taskMetricsOptional;

    public BatchPublishingScheduler(ArticlePublicationWorkflow publicationWorkflow) {
        this(publicationWorkflow, Optional.empty());
    }

    @Autowired
    public BatchPublishingScheduler(ArticlePublicationWorkflow publicationWorkflow,
                                    Optional<TaskMetrics> taskMetricsOptional) {
        this.publicationWorkflow = publicationWorkflow;
        this.taskMetricsOptional = taskMetricsOptional;
    }

    /**
     * 默认每 30 分钟轮询到期稿件 (到期制扫描 + 状态机防重, 幂等补发积压).
     * 工作流完整返回即记录窗口执行成功；文章级成功/失败通过独立 Counter 记录，
     * 避免单篇永久失败把“窗口未执行”告警永久锁定。
     */
    @Scheduled(cron = "${wechat.mp.publishing.batch-cron:0 */30 * * * ?}")
    public void processBatch() {
        try {
            ArticlePublicationWorkflow.PublishDueResult result = publicationWorkflow.publishDueArticles();
            taskMetricsOptional.ifPresent(metrics -> {
                metrics.recordScheduleSuccess(TaskMetrics.Operation.BATCH_PUBLISH);
                metrics.recordBatchArticles(result.success(), result.failure());
            });
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
