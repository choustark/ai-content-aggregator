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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    private static final String EXPECTED_RAG_EXTRA_KEY = "cost:daily:rag-extra:2026-06-28";
    private static final String EXPECTED_DEEPSEEK_INPUT_KEY = "cost:daily:model:2026-06-28:deepseek:input";
    private static final String EXPECTED_DEEPSEEK_OUTPUT_KEY = "cost:daily:model:2026-06-28:deepseek:output";
    private static final String EXPECTED_DEEPSEEK_COST_KEY =
            "cost:daily:model:2026-06-28:deepseek:estimated-micro-cents";

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
        // "1234" + "1234" = 8 chars / 4 = 2 tokens
        tracker.track("1234", "1234", DATE);

        verify(redisRepository).incrementBy(eq(EXPECTED_KEY), eq(2L), any(Duration.class));
    }

    @Test
    void shouldSetTtlLongEnoughForMonthlySummary() {
        tracker.track("1234", "1234", DATE);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redisRepository).incrementBy(eq(EXPECTED_KEY), eq(2L), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isGreaterThanOrEqualTo(Duration.ofDays(45));
    }

    @Test
    void shouldDelegateAccumulationToRedisAtomicIncrement() {
        tracker.track("1234", "1234", DATE);

        verify(redisRepository).incrementBy(eq(EXPECTED_KEY), eq(2L), any(Duration.class));
        verify(redisRepository, times(0)).get(anyString());
        verify(redisRepository, times(0)).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void shouldNotThrowOnRedisRetryableFailure() {
        when(redisRepository.incrementBy(eq(EXPECTED_KEY), eq(2L), any(Duration.class)))
                .thenThrow(new RetryableException("redis down"));

        assertThatCode(() -> tracker.track("1234", "1234", DATE))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldNotThrowOnRedisNonRetryableFailure() {
        when(redisRepository.incrementBy(eq(EXPECTED_KEY), eq(2L), any(Duration.class)))
                .thenThrow(new NonRetryableException("serialization failed"));

        assertThatCode(() -> tracker.track("1234", "1234", DATE))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldSkipTrackWhenBothInputsProduceZeroTokens() {
        tracker.track("", "", DATE);

        verify(redisRepository, times(0)).get(anyString());
        verify(redisRepository, times(0)).set(anyString(), anyString(), any(Duration.class));
        verify(redisRepository, times(0)).incrementBy(anyString(), org.mockito.ArgumentMatchers.anyLong(), any(Duration.class));
    }

    @Test
    void shouldHaveTtlConstantLongEnoughForMonthlySummary() {
        assertThat(ReflectionTestUtils.getField(TokenUsageTracker.class, "TTL"))
                .isEqualTo(Duration.ofDays(45));
    }

    @Test
    void shouldTrackRagPromptDeltaToIndependentKey() {
        tracker.trackRagPromptDelta(11, DATE);

        verify(redisRepository).incrementBy(eq(EXPECTED_RAG_EXTRA_KEY), eq(11L), any(Duration.class));
    }

    @Test
    void shouldTrackModelInputOutputAndEstimatedCost() {
        CostPricingProperties pricing = new CostPricingProperties();
        CostPricingProperties.ModelPricing deepseek = new CostPricingProperties.ModelPricing();
        deepseek.setInputCentsPerMillion(14);
        deepseek.setOutputCentsPerMillion(28);
        pricing.getPricing().put("deepseek", deepseek);
        tracker = new TokenUsageTracker(redisRepository, pricing);

        tracker.track("deepseek", "12345678", "abcdefgh", DATE);

        verify(redisRepository).incrementBy(eq(EXPECTED_KEY), eq(4L), any(Duration.class));
        verify(redisRepository).incrementBy(eq(EXPECTED_DEEPSEEK_INPUT_KEY), eq(2L), any(Duration.class));
        verify(redisRepository).incrementBy(eq(EXPECTED_DEEPSEEK_OUTPUT_KEY), eq(2L), any(Duration.class));
        verify(redisRepository).incrementBy(eq(EXPECTED_DEEPSEEK_COST_KEY), eq(84L), any(Duration.class));
    }

    @Test
    void shouldTrackModelTokensButSkipCostWhenPricingMissing() {
        tracker.track("glm", "1234", "abcd", DATE);

        verify(redisRepository).incrementBy(eq("cost:daily:model:2026-06-28:glm:input"),
                eq(1L), any(Duration.class));
        verify(redisRepository).incrementBy(eq("cost:daily:model:2026-06-28:glm:output"),
                eq(1L), any(Duration.class));
        verify(redisRepository, times(0)).incrementBy(
                eq("cost:daily:model:2026-06-28:glm:estimated-micro-cents"),
                org.mockito.ArgumentMatchers.anyLong(),
                any(Duration.class));
    }

    @Test
    void shouldNotThrowWhenModelNameIsInvalid() {
        assertThatCode(() -> tracker.track(" ", "1234", "abcd", DATE))
                .doesNotThrowAnyException();

        verify(redisRepository, times(0)).incrementBy(
                org.mockito.ArgumentMatchers.startsWith("cost:daily:model"),
                org.mockito.ArgumentMatchers.anyLong(),
                any(Duration.class));
    }

    @Test
    void shouldAccumulateRagPromptDeltaAndKeepMonthlySummaryTtl() {
        tracker.trackRagPromptDelta(5, DATE);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(redisRepository).incrementBy(eq(EXPECTED_RAG_EXTRA_KEY), eq(5L), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofDays(45));
    }

    @Test
    void shouldNoOpWhenRagPromptDeltaIsNotPositive() {
        tracker.trackRagPromptDelta(0, DATE);
        tracker.trackRagPromptDelta(-1, DATE);

        verify(redisRepository, times(0)).get(EXPECTED_RAG_EXTRA_KEY);
        verify(redisRepository, times(0)).set(eq(EXPECTED_RAG_EXTRA_KEY), anyString(), any(Duration.class));
        verify(redisRepository, times(0)).incrementBy(eq(EXPECTED_RAG_EXTRA_KEY),
                org.mockito.ArgumentMatchers.anyLong(), any(Duration.class));
    }

    @Test
    void shouldNotThrowWhenAtomicIncrementSeesDirtyRedisValue() {
        when(redisRepository.incrementBy(eq(EXPECTED_RAG_EXTRA_KEY), eq(9L), any(Duration.class)))
                .thenThrow(new NonRetryableException("dirty redis value"));

        assertThatCode(() -> tracker.trackRagPromptDelta(9, DATE))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldNotThrowWhenRagPromptDeltaRedisFails() {
        when(redisRepository.incrementBy(eq(EXPECTED_RAG_EXTRA_KEY), eq(9L), any(Duration.class)))
                .thenThrow(new RetryableException("redis down"));

        assertThatCode(() -> tracker.trackRagPromptDelta(9, DATE))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldNotLoseTokensWhenTwoModelCallsTrackConcurrently() throws Exception {
        TokenUsageTracker concurrentTracker = new TokenUsageTracker(new InMemoryRedisRepository());
        CountDownLatch start = new CountDownLatch(1);
        Thread first = new Thread(() -> trackAfter(start, concurrentTracker));
        Thread second = new Thread(() -> trackAfter(start, concurrentTracker));

        first.start();
        second.start();
        start.countDown();
        first.join(TimeUnit.SECONDS.toMillis(2));
        second.join(TimeUnit.SECONDS.toMillis(2));

        InMemoryRedisRepository repository =
                (InMemoryRedisRepository) ReflectionTestUtils.getField(concurrentTracker, "redisRepository");
        assertThat(repository.values.get(EXPECTED_KEY).get()).isEqualTo(4L);
    }

    private static void trackAfter(CountDownLatch start, TokenUsageTracker tracker) {
        try {
            start.await(1, TimeUnit.SECONDS);
            tracker.track("1234", "1234", DATE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class InMemoryRedisRepository implements RedisRepository {
        private final Map<String, AtomicLong> values = new ConcurrentHashMap<>();

        @Override public void set(String key, String value) { values.put(key, new AtomicLong(Long.parseLong(value))); }
        @Override public void set(String key, String value, Duration ttl) { values.put(key, new AtomicLong(Long.parseLong(value))); }
        @Override public void setKeepingTtl(String key, String value) { values.put(key, new AtomicLong(Long.parseLong(value))); }
        @Override public String get(String key) {
            AtomicLong value = values.get(key);
            return value == null ? null : String.valueOf(value.get());
        }
        @Override public void delete(String key) { values.remove(key); }
        @Override public boolean exists(String key) { return values.containsKey(key); }
        @Override public void expire(String key, Duration ttl) { }
        @Override public long incrementBy(String key, long delta, Duration ttl) {
            return values.computeIfAbsent(key, ignored -> new AtomicLong()).addAndGet(delta);
        }
        @Override public <T> void setObject(String key, T value, Duration ttl) { }
        @Override public <T> T getObject(String key, Class<T> type) { return null; }
        @Override public void rPush(String key, String value) { }
        @Override public String lPop(String key) { return null; }
        @Override public long listLength(String key) { return 0; }
        @Override public <T> List<T> lRange(String key, long start, long end, Class<T> type) { return List.of(); }
    }
}
