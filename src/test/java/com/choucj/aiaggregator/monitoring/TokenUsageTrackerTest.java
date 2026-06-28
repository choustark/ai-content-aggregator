package com.choucj.aiaggregator.monitoring;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.repository.RedisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 2.4 {@link TokenUsageTracker} 单测.
 *
 * <p>覆盖 AC-27 (估算) / AC-28 (Redis 写) / AC-29 (Redis 失败容错).
 */
@ExtendWith(MockitoExtension.class)
class TokenUsageTrackerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 28);
    private static final String EXPECTED_KEY = "cost:daily:2026-06-28";

    @Mock
    private RedisRepository redisRepository;

    private TokenUsageTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new TokenUsageTracker(redisRepository);
    }

    @Test
    void shouldEstimateTokensByCharCount() {
        int tokens = TokenUsageTracker.estimateTokens("1234", "abcd");
        assertThat(tokens).isEqualTo(2);
    }

    @Test
    void shouldHandleNullPromptOrResponse() {
        assertThat(TokenUsageTracker.estimateTokens(null, null)).isEqualTo(0);
        assertThat(TokenUsageTracker.estimateTokens("abcd", null)).isEqualTo(1);
        assertThat(TokenUsageTracker.estimateTokens(null, "abcd")).isEqualTo(1);
    }

    @Test
    void shouldWriteToRedisCostDailyKey() {
        when(redisRepository.get(EXPECTED_KEY)).thenReturn(null);

        // "1234" + "1234" = 8 chars / 4 = 2 tokens
        tracker.track("1234", "1234", DATE);

        verify(redisRepository).set(eq(EXPECTED_KEY), eq("2"), any(Duration.class));
    }

    @Test
    void shouldSetTtlSevenDays() {
        when(redisRepository.get(anyString())).thenReturn(null);

        tracker.track("1234", "1234", DATE);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redisRepository).set(eq(EXPECTED_KEY), anyString(), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void shouldAccumulateExistingValue() {
        when(redisRepository.get(EXPECTED_KEY)).thenReturn("100");

        // "1234" + "1234" = 8 chars / 4 = 2 tokens, 100 + 2 = 102
        tracker.track("1234", "1234", DATE);

        verify(redisRepository).set(eq(EXPECTED_KEY), eq("102"), any(Duration.class));
    }

    @Test
    void shouldStartFromZeroWhenKeyMissing() {
        when(redisRepository.get(EXPECTED_KEY)).thenReturn(null);

        // "abcd" + "abcd" = 8 chars / 4 = 2 tokens
        tracker.track("abcd", "abcd", DATE);

        verify(redisRepository).set(eq(EXPECTED_KEY), eq("2"), any(Duration.class));
    }

    @Test
    void shouldNotThrowOnRedisRetryableFailure() {
        when(redisRepository.get(EXPECTED_KEY))
                .thenThrow(new RetryableException("redis down"));

        assertThatCode(() -> tracker.track("1234", "1234", DATE))
                .doesNotThrowAnyException();
        verify(redisRepository, times(0)).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void shouldNotThrowOnRedisNonRetryableFailure() {
        when(redisRepository.get(EXPECTED_KEY))
                .thenThrow(new NonRetryableException("serialization failed"));

        assertThatCode(() -> tracker.track("1234", "1234", DATE))
                .doesNotThrowAnyException();
        verify(redisRepository, times(0)).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void shouldResetWhenExistingValueIsNonNumeric() {
        when(redisRepository.get(EXPECTED_KEY)).thenReturn("not-a-number");

        tracker.track("1234", "1234", DATE);

        // 重置为当前估算值 (8 chars / 4 = 2 tokens)
        verify(redisRepository).set(eq(EXPECTED_KEY), eq("2"), any(Duration.class));
    }

    @Test
    void shouldSkipTrackWhenBothInputsProduceZeroTokens() {
        tracker.track("", "", DATE);

        verify(redisRepository, times(0)).get(anyString());
        verify(redisRepository, times(0)).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void shouldHaveTtlConstantSevenDays() {
        assertThat(ReflectionTestUtils.getField(TokenUsageTracker.class, "TTL"))
                .isEqualTo(Duration.ofDays(7));
    }
}
