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

import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 3.4 {@link PublishingModeDecider} 单元测试.
 *
 * <p>核心覆盖 (AC-1 ~ AC-3 + AC-11):
 * <ul>
 *   <li>AC-2 实时路径: innovationScore {@code >=} threshold 立即调 WeChatPublisher.publish</li>
 *   <li>AC-3 批量路径: innovationScore {@code <} threshold 入队 Redis + TTL + listLength 日志</li>
 *   <li>AC-3 软失败降级: Redis/序列化失败时降级走 WeChatPublisher.publish 兜底</li>
 *   <li>AC-11 异常分类: Redis 异常软失败不抛出, 实时路径异常透传到上层</li>
 * </ul>
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class PublishingModeDeciderTest {

    @Mock
    private WeChatPublisher weChatPublisher;
    @Mock
    private RedisRepository redisRepository;

    private PublishingProperties publishingProperties;
    private ObjectMapper objectMapper;
    private PublishingModeDecider decider;

    @BeforeEach
    void setUp() {
        publishingProperties = new PublishingProperties();
        objectMapper = new ObjectMapper().findAndRegisterModules();
        decider = new PublishingModeDecider(weChatPublisher, redisRepository,
                publishingProperties, objectMapper);
    }

    @Test
    void shouldPublishImmediatelyWhenScoreAboveThreshold(CapturedOutput output) {
        // AC-2: score=9 >= threshold=8 → 实时路径, 不入队
        Article article = article("art-1", 9);

        decider.publish(article);

        verify(weChatPublisher).publish(article);
        verify(redisRepository, never()).rPush(any(), any());
        assertThat(output.getOut()).contains("decision=realtime");
        assertThat(output.getOut()).contains("articleId=art-1");
        assertThat(output.getOut()).contains("innovationScore=9");
    }

    @Test
    void shouldPublishImmediatelyWhenScoreEqualsThreshold(CapturedOutput output) {
        // 边界: score == threshold (8 == 8) 也走实时路径 (D3: 严格 >= 与 epics.md 文案对齐)
        Article article = article("art-eq", 8);

        decider.publish(article);

        verify(weChatPublisher).publish(article);
        verify(redisRepository, never()).rPush(any(), any());
        assertThat(output.getOut()).contains("decision=realtime");
    }

    @Test
    void shouldEnqueueToRedisWhenScoreBelowThreshold(CapturedOutput output) {
        // AC-3: score=5 < threshold=8 → 入队
        Article article = article("art-2", 5);
        when(redisRepository.listLength(any())).thenReturn(3L);

        decider.publish(article);

        verify(weChatPublisher, never()).publish(any());
        String expectedKey = RedisKeys.publishPending(article.getCreatedAt().toLocalDate());
        verify(redisRepository).rPush(eq(expectedKey), any());
        verify(redisRepository).expire(eq(expectedKey), eq(Duration.ofDays(7)));
        // W11: 日志含 decision=batch + queueDate + queueSize
        assertThat(output.getOut()).contains("decision=batch");
        assertThat(output.getOut()).contains("queueSize=3");
    }

    @Test
    void shouldFallbackToRealtimeOnRedisFailure(CapturedOutput output) {
        // AC-3 / 决策 4 软失败: Redis 抖动 → log.warn + 降级调 WeChatPublisher.publish
        Article article = article("art-3", 5);
        doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "Redis 抖动"))
                .when(redisRepository).rPush(any(), any());

        decider.publish(article);

        // 软失败: 仍调 WeChatPublisher, 不抛异常到 TwitterProcessor
        verify(weChatPublisher).publish(article);
        assertThat(output.getOut()).contains("降级走实时路径");
    }

    @Test
    void shouldFallbackToRealtimeOnRedisRuntimeException(CapturedOutput output) {
        // W1+W2 兜底: Spring 框架 RuntimeException (非 Retryable/NonRetryable 子类) 也走软失败降级
        Article article = article("art-4", 5);
        doThrow(new IllegalStateException("未预期 Redis 客户端异常"))
                .when(redisRepository).rPush(any(), any());

        decider.publish(article);

        verify(weChatPublisher).publish(article);
        assertThat(output.getOut()).contains("未预期异常");
        assertThat(output.getOut()).contains("降级走实时路径");
    }

    @Test
    void shouldNotFallbackToRealtimeWhenExpireFailsAfterRPush(CapturedOutput output) {
        Article article = article("art-expire", 5);
        doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "expire 抖动"))
                .when(redisRepository).expire(any(), any());

        decider.publish(article);

        verify(redisRepository).rPush(any(), any());
        verify(weChatPublisher, never()).publish(any());
        assertThat(output.getOut()).contains("保持批量路径不降级");
        assertThat(output.getOut()).contains("decision=batch");
        assertThat(output.getOut()).contains("queueSize=-1");
    }

    @Test
    void shouldNotFallbackToRealtimeWhenListLengthFailsAfterRPush(CapturedOutput output) {
        Article article = article("art-length", 5);
        doThrow(new IllegalStateException("llen 失败"))
                .when(redisRepository).listLength(any());

        decider.publish(article);

        verify(redisRepository).rPush(any(), any());
        verify(redisRepository).expire(any(), any());
        verify(weChatPublisher, never()).publish(any());
        assertThat(output.getOut()).contains("保持批量路径不降级");
        assertThat(output.getOut()).contains("queueSize=-1");
    }

    @Test
    void shouldRespectCustomThresholdFromConfig(CapturedOutput output) {
        // AC-4: 自定义阈值 realtime-threshold=10 → score=8 仍入队
        publishingProperties.setRealtimeThreshold(10);
        Article article = article("art-5", 8);
        when(redisRepository.listLength(any())).thenReturn(1L);

        decider.publish(article);

        verify(weChatPublisher, never()).publish(any());
        verify(redisRepository).rPush(any(), any());
        assertThat(output.getOut()).contains("decision=batch");
        assertThat(output.getOut()).contains("threshold=10");
    }

    @Test
    void shouldFallbackToRealtimeWhenCreatedAtIsNull() {
        // createdAt null 时回退 LocalDate.now(), 不抛 NPE
        Article article = Article.builder()
                .id("art-no-date")
                .title("无日期 Article")
                .innovationScore(3)
                .build();
        when(redisRepository.listLength(any())).thenReturn(0L);

        decider.publish(article);

        verify(redisRepository).rPush(eq(RedisKeys.publishPending(LocalDate.now())), any());
    }

    @Test
    void shouldFallbackToRealtimeOnSerializationFailure(CapturedOutput output) throws Exception {
        // 序列化失败: 用 Mockito mock ObjectMapper 抛 JsonMappingException (JsonProcessingException 子类)
        ObjectMapper failingMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        // JsonMappingException 是 JsonProcessingException 的具体子类, 构造器为 public
        com.fasterxml.jackson.databind.JsonMappingException ex =
                new com.fasterxml.jackson.databind.JsonMappingException(null, "模拟序列化失败");
        when(failingMapper.writeValueAsString(any())).thenThrow(ex);
        decider = new PublishingModeDecider(weChatPublisher, redisRepository,
                publishingProperties, failingMapper);

        Article article = article("art-serial", 5);
        decider.publish(article);

        verify(weChatPublisher).publish(article);
        verify(redisRepository, never()).rPush(any(), any());
        assertThat(output.getOut()).contains("序列化失败");
        assertThat(output.getOut()).contains("降级走实时路径");
    }

    @Test
    void shouldPublishRealtimeWhenBatchDisabled(CapturedOutput output) {
        publishingProperties.setBatchEnabled(false);
        Article article = article("art-batch-off", 5);

        decider.publish(article);

        verify(weChatPublisher).publish(article);
        verify(redisRepository, never()).rPush(any(), any());
        assertThat(output.getOut()).contains("decision=realtime");
        assertThat(output.getOut()).contains("reason=batchDisabled");
    }

    @Test
    void shouldThrowOnNullArticle() {
        // 入口 null check, 防 NPE 在 objectMapper 内部触发
        assertThatThrownBy(() -> decider.publish(null))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("Article 为 null");
        verify(weChatPublisher, never()).publish(any());
        verify(redisRepository, never()).rPush(any(), any());
    }

    @Test
    void shouldThrowOnNullArticleId() {
        Article article = article(null, 5);

        assertThatThrownBy(() -> decider.publish(article))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("Article.id 不能为空");
        verify(weChatPublisher, never()).publish(any());
        verify(redisRepository, never()).rPush(any(), any());
    }

    @Test
    void shouldThrowOnBlankArticleId() {
        Article article = article(" ", 5);

        assertThatThrownBy(() -> decider.publish(article))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("Article.id 不能为空");
        verify(weChatPublisher, never()).publish(any());
        verify(redisRepository, never()).rPush(any(), any());
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
}
