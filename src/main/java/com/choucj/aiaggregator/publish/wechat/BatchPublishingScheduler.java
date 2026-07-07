package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.repository.RedisRepository;
import com.choucj.aiaggregator.common.util.RedisKeys;
import com.choucj.aiaggregator.content.rewriter.SingleModelRewriter;
import com.choucj.aiaggregator.publish.wechat.config.PublishingProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.AllNestedConditions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Story 3.4: 批量发布调度器 — 项目第二个独立 {@code @Scheduled} 调度器.
 *
 * <p>每晚 20:00 cron 触发, 消费 Redis List {@code publish:pending:{today}} 队列, 逐条
 * lPop + 反序列化 + 调 {@link WeChatPublisher#publish(Article)} 批量创建草稿.
 *
 * <p><b>架构 delta (Story 3.4 引入):</b>
 * <ul>
 *   <li>项目第二个 {@code @Scheduled} 调度器 — 与 ContentScheduler (Story 1.6) 同级, 但
 *       <b>仅 cron 触发, 不监听 ApplicationReadyEvent</b> (遵循 A7 单编排器原则, 避免双启动监听器顺序不确定).</li>
 *   <li>启动时若批量队列有积压 (e.g. 应用昨晚 20:00 前崩溃), 不立即补跑, 等下一个 20:00 cron 触发
 *       (YAGNI — 紧急补跑由运维手工触发 REST endpoint, 留 Story 5.x 范围).</li>
 * </ul>
 *
 * <p><b>故障隔离 (L2 per-article 模式, 复用 TwitterProcessor Story 2.6):</b>
 * <ul>
 *   <li><b>单条 publish 失败 (任意 Exception, 含 WxErrorException 包装):</b>
 *       log.error + {@code failure++} + continue 不阻塞下一条</li>
 *   <li><b>Redis 读失败 (读取长度 / lPop):</b>
 *       log.error + 终止本次批量 (不继续, 防数据损坏扩散) — 与单条失败不同,
 *       Redis 读取失败暗示集群故障或键不存在, 继续循环无意义.</li>
 *   <li><b>反序列化失败 (单条 JSON schema 不匹配):</b>
 *       归类为 {@code REDIS_DATA_ERROR}, log.error + 终止本次批量. 本 story 维持 MVP 一次性
 *       lPop 队列语义, 坏数据已被移除; reliable queue / dead-letter / retry count 留 Story 5.x.</li>
 * </ul>
 *
 * <p><b>lessons-learned 模式引用:</b>
 * <ul>
 *   <li>W1+W2 — per-article try/catch(Exception) 兜底, 防 WxJava SDK 内部异常逃逸到 @Scheduled 顶层</li>
 *   <li>W11 — summary log.info 含 date + total + success + failure 4 字段 (镜像 TwitterProcessor.process summary)</li>
 *   <li>N4 — 异常 message 不含序列化 JSON 正文, 只含 articleId + 截断 cause</li>
 *   <li>N2+R3-1 — cause 截断复用 {@link SingleModelRewriter#truncateForLog} (修复版)</li>
 *   <li>A7 — 不监听 ApplicationReadyEvent (启动流程唯一编排器原则, 启动顺序由 ContentScheduler 独占)</li>
 * </ul>
 *
 * <p><b>M1 forward-looking (与 Story 3.5 协同):</b> Story 3.5 dev-story 实施时需在本调度器
 * per-article publish 成功后追加 {@code articleStatusService.markDraftCreated(articleId)} 调用,
 * 并新增测试用例 {@code shouldUpdateStatusAfterBatchPublish}. 本 story 仅 flag, 不实施此调用.
 *
 * <p>引用源: Story 3.4 (本 story) / Story 1.6 (ContentScheduler A7 单编排器参考) /
 * Story 2.6 (TwitterProcessor L2 per-article 隔离模式) / Story 3.3 (WeChatPublisher 调用).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Conditional(BatchPublishingScheduler.OnWeChatAndBatchEnabled.class)
public class BatchPublishingScheduler {

    /** 日志/异常中 cause message 截断长度 (codepoint, R3-1 ≤ max, N4 防泄漏). */
    private static final int LOG_CAUSE_MAX_CODEPOINTS = 200;

    private final WeChatPublisher weChatPublisher;
    private final RedisRepository redisRepository;
    private final PublishingProperties publishingProperties;
    private final ObjectMapper objectMapper;

    /**
     * 每晚 20:00 cron 触发批量发布.
     *
     * <p>cron 表达式由 {@code wechat.mp.publishing.batch-cron} 配置, 默认 {@code 0 0 20 * * ?}.
     * 触发时: 读当日 {@code publish:pending:{today}} 队列长度 N, for i in 0..N: lPop + 反序列化 + publish.
     *
     * <p>异常处理:
     * <ul>
     *   <li>Redis 读 listLength 失败 → log.error + return (终止本次批量, 防数据损坏扩散)</li>
     *   <li>lPop 返回 null → 队列已空, 提前 return (避免循环到 N 但队列已空)</li>
     *   <li>反序列化失败 → REDIS_DATA_ERROR + log.error + failure++ + return (终止本次批量)</li>
     *   <li>WeChatPublisher.publish 任意 Exception → log.error + failure++ + continue (L2 隔离)</li>
     * </ul>
     *
     * <p><b>队列可靠性边界:</b> 本 story 明确维持一次性 lPop 队列语义. lPop 后若发布失败, 元素不会自动
     * requeue; 该风险由日志和 Story 5.x 的状态追踪/运维补偿能力承接.
     */
    @Scheduled(cron = "${wechat.mp.publishing.batch-cron:0 0 20 * * ?}")
    public void processBatch() {
        LocalDate today = LocalDate.now();
        String queueKey = RedisKeys.publishPending(today);

        long total;
        try {
            total = redisRepository.listLength(queueKey);
        } catch (RetryableException | NonRetryableException e) {
            // Redis 读失败 — 终止本次批量 (防数据损坏扩散, AC-6)
            String truncatedCause = SingleModelRewriter.truncateForLog(
                    SingleModelRewriter.getRootMessage(e), LOG_CAUSE_MAX_CODEPOINTS);
            log.error("批量发布终止: 读取队列长度失败, date={}, queueKey={}, rootMessage={}",
                    today, queueKey, truncatedCause, e);
            return;
        } catch (RuntimeException e) {
            String truncatedCause = SingleModelRewriter.truncateForLog(
                    SingleModelRewriter.getRootMessage(e), LOG_CAUSE_MAX_CODEPOINTS);
            log.error("批量发布终止: 读取队列长度未预期异常, date={}, queueKey={}, rootMessage={}",
                    today, queueKey, truncatedCause, e);
            return;
        }

        log.info("批量发布启动: date={}, queueKey={}, total={}", today, queueKey, total);

        long success = 0;
        long failure = 0;
        for (long i = 0; i < total; i++) {
            String json;
            try {
                json = redisRepository.lPop(queueKey);
            } catch (RetryableException | NonRetryableException e) {
                // Redis 读失败 — 终止本次批量 (已读取的部分继续处理, 不继续读避免更多失败)
                String truncatedCause = SingleModelRewriter.truncateForLog(
                        SingleModelRewriter.getRootMessage(e), LOG_CAUSE_MAX_CODEPOINTS);
                log.error("批量发布终止: lPop 失败, date={}, queueKey={}, processed={}/{}/{}, rootMessage={}",
                        today, queueKey, i, success, failure, truncatedCause, e);
                log.info("批量发布完成: date={}, total={}, success={}, failure={}",
                        today, total, success, failure);
                return;
            } catch (RuntimeException e) {
                String truncatedCause = SingleModelRewriter.truncateForLog(
                        SingleModelRewriter.getRootMessage(e), LOG_CAUSE_MAX_CODEPOINTS);
                log.error("批量发布终止: lPop 未预期异常, date={}, queueKey={}, processed={}/{}/{}, rootMessage={}",
                        today, queueKey, i, success, failure, truncatedCause, e);
                log.info("批量发布完成: date={}, total={}, success={}, failure={}",
                        today, total, success, failure);
                return;
            }

            if (json == null) {
                // 队列已空, 提前结束 (并发场景或 total 估算偏大时)
                break;
            }

            Article article;
            try {
                article = objectMapper.readValue(json, Article.class);
            } catch (JsonProcessingException e) {
                failure++;
                String truncatedCause = SingleModelRewriter.truncateForLog(
                        SingleModelRewriter.getRootMessage(e), LOG_CAUSE_MAX_CODEPOINTS);
                NonRetryableException classified = new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                        "批量队列元素反序列化失败: date=" + today + ", index=" + i
                                + ", rootMessage=" + truncatedCause,
                        e);
                log.error("批量发布终止: 队列元素反序列化失败, errorCode={}, date={}, index={}/{}/{}, rootMessage={}",
                        classified.getErrorCode(), today, i, success, failure, truncatedCause, classified);
                log.info("批量发布完成: date={}, total={}, success={}, failure={}",
                        today, total, success, failure);
                return;
            }

            // 单条隔离: publish 任意异常 → failure++ + continue
            try {
                weChatPublisher.publish(article);
                success++;
            } catch (Exception e) {
                failure++;
                // N4: 异常 message 不含 JSON 正文 (json 不在 message 中), 只含 cause 截断
                String truncatedCause = SingleModelRewriter.truncateForLog(
                        SingleModelRewriter.getRootMessage(e), LOG_CAUSE_MAX_CODEPOINTS);
                log.error("批量发布单条失败, 跳过 (故障隔离 L2 per-article): date={}, index={}/{}/{}, rootMessage={}",
                        today, i, success, failure, truncatedCause, e);
            }
        }

        // W11: summary log.info 含 date + total + success + failure (镜像 TwitterProcessor.process summary)
        log.info("批量发布完成: date={}, total={}, success={}, failure={}",
                today, total, success, failure);
    }

    /**
     * 双条件 {@link Conditional}: wechat.mp.enabled=true <b>且</b> wechat.mp.publishing.batch-enabled=true
     * (matchIfMissing=true, 默认开).
     *
     * <p>{@link ConditionalOnProperty} 不支持重复标注 (Java 注解不可重复), 故用 {@link AllNestedConditions}
     * 组合两个独立条件 (Story 3.4 AC-5 双 ConditionalOnProperty 级联要求).
     *
     * <p>{@link ConfigurationCondition.ConfigurationPhase#REGISTER_BEAN} 阶段判定 — 早于 Bean 实例化, 避免 WeChatPublisher
     * 缺失时 BatchPublishingScheduler 仍尝试注入失败.
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
