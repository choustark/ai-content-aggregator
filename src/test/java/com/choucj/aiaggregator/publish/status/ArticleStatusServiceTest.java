package com.choucj.aiaggregator.publish.status;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.repository.RedisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 3.5 — {@link ArticleStatusService} 单元测试.
 *
 * <p>覆盖 AC-1/2/3/7/9: markPending/markProcessing/markDraftCreated + getStatus + 入口校验 + 软失败策略.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ArticleStatusServiceTest {

    private static final String ARTICLE_ID = "tw-1234567890";
    private static final String STATUS_KEY = "article:tw-1234567890:status";
    private static final Duration PENDING_TTL = Duration.ofDays(30);

    @Mock
    private RedisRepository redisRepository;

    private ArticleStatusService service;

    @BeforeEach
    void setUp() {
        service = new ArticleStatusService(redisRepository);
    }

    // ===== Task 2.4: markPending =====

    @Test
    void shouldMarkPendingWriteCorrectKeyAndValue() {
        service.markPending(ARTICLE_ID);

        verify(redisRepository).set(STATUS_KEY, ArticleStatus.PENDING.name(), PENDING_TTL);
    }

    // ===== Task 2.4: markProcessing =====

    @Test
    void shouldMarkProcessingNotResetTtl() {
        service.markProcessing(ARTICLE_ID);

        // 必须用 KEEPTTL 语义更新 value, 避免 Redis 普通 SET 清掉首次 PENDING 的 TTL
        verify(redisRepository).setKeepingTtl(STATUS_KEY, ArticleStatus.PROCESSING.name());
        verify(redisRepository, never()).set(STATUS_KEY, ArticleStatus.PROCESSING.name());
        verify(redisRepository, never()).set(eq(STATUS_KEY), eq(ArticleStatus.PROCESSING.name()), any(Duration.class));
    }

    @Test
    void shouldMarkDraftCreatedNotResetTtl() {
        service.markDraftCreated(ARTICLE_ID);

        verify(redisRepository).setKeepingTtl(STATUS_KEY, ArticleStatus.DRAFT_CREATED.name());
        verify(redisRepository, never()).set(STATUS_KEY, ArticleStatus.DRAFT_CREATED.name());
        verify(redisRepository, never()).set(eq(STATUS_KEY), eq(ArticleStatus.DRAFT_CREATED.name()), any(Duration.class));
    }

    // ===== Task 2.4: 入口校验 (AC-7) =====

    @Test
    void shouldThrowOnNullArticleId() {
        assertThatThrownBy(() -> service.markPending(null))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((NonRetryableException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NON_RETRYABLE_ERROR))
                .hasMessageContaining("非法 articleId");
        verify(redisRepository, never()).set(any(), any());
    }

    @Test
    void shouldThrowOnBlankArticleId() {
        assertThatThrownBy(() -> service.markPending("   "))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("非法 articleId");
        verify(redisRepository, never()).set(any(), any());
    }

    @Test
    void shouldThrowOnArticleIdWithNewline() {
        assertThatThrownBy(() -> service.markPending("tw-123\nrm -rf /"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("必须匹配");
        verify(redisRepository, never()).set(any(), any());
    }

    @Test
    void shouldThrowOnArticleIdWithTab() {
        assertThatThrownBy(() -> service.markProcessing("tw-123\t rm"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("必须匹配");
    }

    @Test
    void shouldThrowOnArticleIdWithRedisDelimiter() {
        assertThatThrownBy(() -> service.markPending("tw-123:status"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("必须匹配");
        verify(redisRepository, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void shouldThrowOnArticleIdWithSlash() {
        assertThatThrownBy(() -> service.markPending("tw-../123"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("必须匹配");
        verify(redisRepository, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void shouldThrowOnGetStatusWithNullArticleId() {
        assertThatThrownBy(() -> service.getStatus(null))
                .isInstanceOf(NonRetryableException.class);
        verify(redisRepository, never()).get(any());
    }

    // ===== Task 2.4: 软失败 (AC-9 写路径) =====

    @Test
    void shouldSwallowRetryableExceptionOnMarkPending(CapturedOutput output) {
        doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "Redis 连接失败"))
                .when(redisRepository).set(STATUS_KEY, ArticleStatus.PENDING.name(), PENDING_TTL);

        service.markPending(ARTICLE_ID);

        // 软失败: 不抛异常, log.warn 触发, 流水线不被阻塞
        assertThat(output.getOut()).contains("状态写入失败, 跳过");
        assertThat(output.getOut()).contains("articleId=" + ARTICLE_ID);
        assertThat(output.getOut()).contains("operation=markPending");
    }

    @Test
    void shouldSwallowNonRetryableExceptionOnMarkPending(CapturedOutput output) {
        doThrow(new NonRetryableException(ErrorCode.REDIS_DATA_ERROR, "Redis 数据错"))
                .when(redisRepository).set(STATUS_KEY, ArticleStatus.PENDING.name(), PENDING_TTL);

        service.markPending(ARTICLE_ID);

        assertThat(output.getOut()).contains("状态写入失败, 跳过");
    }

    @Test
    void shouldSwallowRetryableExceptionOnMarkProcessing(CapturedOutput output) {
        doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "Redis 连接失败"))
                .when(redisRepository).setKeepingTtl(STATUS_KEY, ArticleStatus.PROCESSING.name());

        service.markProcessing(ARTICLE_ID);

        assertThat(output.getOut()).contains("operation=markProcessing");
    }

    @Test
    void shouldSwallowRetryableExceptionOnMarkDraftCreated(CapturedOutput output) {
        doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "Redis 连接失败"))
                .when(redisRepository).setKeepingTtl(STATUS_KEY, ArticleStatus.DRAFT_CREATED.name());

        service.markDraftCreated(ARTICLE_ID);

        assertThat(output.getOut()).contains("operation=markDraftCreated");
    }

    // ===== Task 2.4: getStatus 查询路径 (AC-9 读路径, 不软失败) =====

    @Test
    void shouldPropagateRetryableExceptionOnGetStatus(CapturedOutput output) {
        when(redisRepository.get(STATUS_KEY))
                .thenThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "Redis 连接失败"));

        assertThatThrownBy(() -> service.getStatus(ARTICLE_ID))
                .isInstanceOf(RetryableException.class)
                .satisfies(ex -> assertThat(((RetryableException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.REDIS_CONNECTION_ERROR));
        assertThat(output.getOut()).contains("状态查询失败 (异常透传给 Controller)");
    }

    @Test
    void shouldPropagateNonRetryableExceptionOnGetStatus() {
        when(redisRepository.get(STATUS_KEY))
                .thenThrow(new NonRetryableException(ErrorCode.REDIS_DATA_ERROR, "Redis 数据错"));

        assertThatThrownBy(() -> service.getStatus(ARTICLE_ID))
                .isInstanceOf(NonRetryableException.class);
    }

    @Test
    void shouldReturnEmptyWhenKeyNotExists() {
        when(redisRepository.get(STATUS_KEY)).thenReturn(null);

        Optional<ArticleStatus> result = service.getStatus(ARTICLE_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnParsedStatusWhenKeyExists() {
        when(redisRepository.get(STATUS_KEY)).thenReturn(ArticleStatus.DRAFT_CREATED.name());

        Optional<ArticleStatus> result = service.getStatus(ARTICLE_ID);

        assertThat(result).contains(ArticleStatus.DRAFT_CREATED);
    }

    @Test
    void shouldReturnPendingStatusWhenKeyExists() {
        when(redisRepository.get(STATUS_KEY)).thenReturn(ArticleStatus.PENDING.name());

        Optional<ArticleStatus> result = service.getStatus(ARTICLE_ID);

        assertThat(result).contains(ArticleStatus.PENDING);
    }

    @Test
    void shouldThrowOnUnknownStatusValueInRedis() {
        when(redisRepository.get(STATUS_KEY)).thenReturn("BOGUS");

        assertThatThrownBy(() -> service.getStatus(ARTICLE_ID))
                .isInstanceOf(NonRetryableException.class)
                .satisfies(ex -> assertThat(((NonRetryableException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.REDIS_DATA_ERROR))
                .hasMessageContaining("Redis 数据非法")
                .hasMessageContaining("value=BOGUS");
    }
}
