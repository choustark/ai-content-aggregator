package com.choucj.aiaggregator.publish.wechat;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.repository.RedisRepository;
import com.choucj.aiaggregator.common.util.RedisKeys;
import com.choucj.aiaggregator.publish.wechat.config.PublishingProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 3.4 {@link BatchPublishingScheduler} 单元测试.
 *
 * <p>核心覆盖 (AC-5 / AC-6 / AC-11):
 * <ul>
 *   <li>AC-6 顺序消费: listLength=N → N 次 lPop + 反序列化 + publish</li>
 *   <li>AC-6 单条失败隔离: WeChatPublisher.publish 抛任意 Exception → failure++ + continue</li>
 *   <li>AC-6 Redis 读失败终止: listLength 抛 Retryable/NonRetryable → 立即 return</li>
 *   <li>AC-6 反序列化失败隔离: 单条坏 JSON → failure++ + continue</li>
 *   <li>W11 summary log: 含 date + total + success + failure 4 字段</li>
 *   <li>lPop 返回 null 提前终止</li>
 * </ul>
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class BatchPublishingSchedulerTest {

    @Mock
    private WeChatPublisher weChatPublisher;
    @Mock
    private RedisRepository redisRepository;

    private PublishingProperties publishingProperties;
    private ObjectMapper objectMapper;
    private BatchPublishingScheduler scheduler;

    @BeforeEach
    void setUp() {
        publishingProperties = new PublishingProperties();
        objectMapper = new ObjectMapper().findAndRegisterModules();
        scheduler = new BatchPublishingScheduler(weChatPublisher, redisRepository,
                publishingProperties, objectMapper);
    }

    @Test
    void shouldProcessAllPendingArticlesAtCron(CapturedOutput output) {
        // 队列有 2 条: 全部成功消费
        Article a1 = article("art-1", 5);
        Article a2 = article("art-2", 6);
        String queueKey = RedisKeys.publishPending(LocalDate.now());
        when(redisRepository.listLength(queueKey)).thenReturn(2L);
        when(redisRepository.lPop(queueKey))
                .thenReturn(toJson(a1))
                .thenReturn(toJson(a2));

        scheduler.processBatch();

        verify(weChatPublisher, times(1)).publish(a1);
        verify(weChatPublisher, times(1)).publish(a2);
        assertThat(output.getOut()).contains("批量发布完成: date=" + LocalDate.now()
                + ", total=2, success=2, failure=0");
    }

    @Test
    void shouldHandleEmptyQueueGracefully(CapturedOutput output) {
        // 队列长度=0, 不调 lPop, 不调 publish
        when(redisRepository.listLength(any())).thenReturn(0L);

        scheduler.processBatch();

        verify(redisRepository, never()).lPop(any());
        verify(weChatPublisher, never()).publish(any());
        assertThat(output.getOut()).contains("total=0, success=0, failure=0");
    }

    @Test
    void shouldIsolatePerArticleFailureAndContinue(CapturedOutput output) {
        // 第 1 条 publish 失败, 第 2 条仍被处理 (L2 per-article 隔离)
        Article a1 = article("art-1", 5);
        Article a2 = article("art-2", 6);
        when(redisRepository.listLength(any())).thenReturn(2L);
        when(redisRepository.lPop(any())).thenReturn(toJson(a1)).thenReturn(toJson(a2));
        doThrow(new NonRetryableException(ErrorCode.WECHAT_API_ERROR, "微信限流"))
                .when(weChatPublisher).publish(eq(a1));

        scheduler.processBatch();

        verify(weChatPublisher, times(1)).publish(a1);
        verify(weChatPublisher, times(1)).publish(a2);
        assertThat(output.getOut()).contains("total=2, success=1, failure=1");
    }

    @Test
    void shouldIsolateRuntimeExceptionInPublisher(CapturedOutput output) {
        // W1+W2 兜底: RuntimeException 也被 catch(Exception) 隔离
        Article a1 = article("art-1", 5);
        Article a2 = article("art-2", 6);
        when(redisRepository.listLength(any())).thenReturn(2L);
        when(redisRepository.lPop(any())).thenReturn(toJson(a1)).thenReturn(toJson(a2));
        doThrow(new IllegalStateException("未预期异常")).when(weChatPublisher).publish(eq(a1));

        scheduler.processBatch();

        verify(weChatPublisher, times(1)).publish(a2);
        assertThat(output.getOut()).contains("total=2, success=1, failure=1");
    }

    @Test
    void shouldTerminateOnRedisListLengthFailure(CapturedOutput output) {
        // listLength 抛 Retryable → 立即终止, 不调 lPop / publish
        when(redisRepository.listLength(any()))
                .thenThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "Redis 抖动"));

        scheduler.processBatch();

        verify(redisRepository, never()).lPop(any());
        verify(weChatPublisher, never()).publish(any());
        assertThat(output.getOut()).contains("批量发布终止");
    }

    @Test
    void shouldTerminateOnRedisListLengthRuntimeException(CapturedOutput output) {
        when(redisRepository.listLength(any()))
                .thenThrow(new IllegalStateException("Redis 客户端裸异常"));

        scheduler.processBatch();

        verify(redisRepository, never()).lPop(any());
        verify(weChatPublisher, never()).publish(any());
        assertThat(output.getOut()).contains("读取队列长度未预期异常");
    }

    @Test
    void shouldTerminateOnRedisLPopFailureMidway(CapturedOutput output) {
        // 第 1 条消费成功后, lPop 抛 NonRetryable → 终止 (已读取的成功计入 summary)
        Article a1 = article("art-1", 5);
        when(redisRepository.listLength(any())).thenReturn(3L);
        when(redisRepository.lPop(any()))
                .thenReturn(toJson(a1))
                .thenThrow(new NonRetryableException(ErrorCode.REDIS_DATA_ERROR, "Redis 数据损坏"));

        scheduler.processBatch();

        verify(weChatPublisher, times(1)).publish(a1);
        assertThat(output.getOut()).contains("批量发布终止");
    }

    @Test
    void shouldTerminateOnRedisLPopRuntimeExceptionMidway(CapturedOutput output) {
        Article a1 = article("art-1", 5);
        when(redisRepository.listLength(any())).thenReturn(3L);
        when(redisRepository.lPop(any()))
                .thenReturn(toJson(a1))
                .thenThrow(new IllegalStateException("Redis 客户端裸异常"));

        scheduler.processBatch();

        verify(weChatPublisher, times(1)).publish(a1);
        assertThat(output.getOut()).contains("lPop 未预期异常");
        assertThat(output.getOut()).contains("total=3, success=1, failure=0");
    }

    @Test
    void shouldTerminateOnDeserializationFailure(CapturedOutput output) {
        // 反序列化失败属于 Redis 数据异常, 终止本次批量 (已 lPop 的坏数据不 requeue)
        when(redisRepository.listLength(any())).thenReturn(2L);
        when(redisRepository.lPop(any()))
                .thenReturn("{INVALID_JSON_NOT_ARTICLE}") // 第 1 条坏数据
                .thenReturn(toJson(article("art-2", 6))); // 第 2 条正常

        scheduler.processBatch();

        verify(weChatPublisher, never()).publish(any());
        assertThat(output.getOut()).contains("REDIS_DATA_ERROR");
        assertThat(output.getOut()).contains("total=2, success=0, failure=1");
    }

    @Test
    void shouldStopWhenLPopReturnsNull(CapturedOutput output) {
        // total=3 但 lPop 第 2 次返回 null (并发场景或 listLength 估算偏大) → 提前终止
        Article a1 = article("art-1", 5);
        when(redisRepository.listLength(any())).thenReturn(3L);
        when(redisRepository.lPop(any())).thenReturn(toJson(a1)).thenReturn(null);

        scheduler.processBatch();

        verify(weChatPublisher, times(1)).publish(any());
        // success=1 failure=0, total=3 (total 是初始读取的, 实际只处理 1 条)
        assertThat(output.getOut()).contains("total=3, success=1, failure=0");
    }

    @Test
    void shouldLogSummaryAtCompletion(CapturedOutput output) {
        // W11: summary log 含 date + total + success + failure
        when(redisRepository.listLength(any())).thenReturn(0L);

        scheduler.processBatch();

        assertThat(output.getOut()).contains("批量发布完成:");
        assertThat(output.getOut()).contains("date=" + LocalDate.now());
    }

    // ============ helpers ============

    private static Article article(String id, int score) {
        return Article.builder()
                .id(id)
                .title("标题 " + id)
                .content("正文 " + id)
                .innovationScore(score)
                .createdAt(LocalDate.of(2026, 7, 6).atStartOfDay())
                .build();
    }

    private String toJson(Article article) {
        try {
            return objectMapper.writeValueAsString(article);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
