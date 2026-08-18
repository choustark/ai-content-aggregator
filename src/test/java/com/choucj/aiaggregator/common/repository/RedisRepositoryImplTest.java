package com.choucj.aiaggregator.common.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 1.5a {@link RedisRepositoryImpl} 单元测试.
 *
 * <p>Mock {@link StringRedisTemplate},验证 Impl 仅做透传 — 不验证 Redis 真实行为
 * (那是 {@code RedisClusterConnectivityTest} 集成测试的职责).
 *
 * <p>覆盖 6 个方法 × happy path + null/edge 场景:
 * <ul>
 *   <li>{@code set(k,v)} / {@code set(k,v,ttl)} — 验证 opsForValue().set 调用</li>
 *   <li>{@code get(k)} — 验证返回值与底层一致</li>
 *   <li>{@code delete(k)} — 验证透传</li>
 *   <li>{@code exists(k)} — 验证 {@code Boolean.TRUE.equals(null)} 返回 false 的防御</li>
 *   <li>{@code expire(k,ttl)} — 验证透传</li>
 * </ul>
 *
 * <p><b>Mockito 严格模式:</b> 不在 {@code @BeforeEach} 中预先 stub
 * {@code opsForValue()} — 仅 set/get 路径需要它,delete/exists/expire 直接走
 * {@link StringRedisTemplate} 顶层 API. 预先 stub 会被 Mockito 报为
 * {@code UnnecessaryStubbing}.
 */
@ExtendWith(MockitoExtension.class)
class RedisRepositoryImplTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private RedisRepositoryImpl repository;

    @Test
    void shouldSetKeyValueWithoutTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        repository.set("cache:rsshub:url1", "value1");

        verify(valueOps, times(1)).set("cache:rsshub:url1", "value1");
    }

    @Test
    void shouldSetKeyValueWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        Duration ttl = Duration.ofMinutes(30);

        repository.set("cache:rsshub:url1", "value1", ttl);

        verify(valueOps, times(1)).set("cache:rsshub:url1", "value1", ttl);
    }

    @Test
    void shouldSetKeyValueKeepingTtlViaLowLevelCommand() {
        repository.setKeepingTtl("article:tw-1:status", "PROCESSING");

        verify(redisTemplate, times(1)).execute(any(RedisCallback.class));
        verify(valueOps, never()).set(any(), any());
    }

    @Test
    void shouldGetReturnNullForMissingKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("missing")).thenReturn(null);

        String result = repository.get("missing");

        assertThat(result).isNull();
        verify(valueOps, times(1)).get("missing");
    }

    @Test
    void shouldGetReturnStoredValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("cache:rsshub:url1")).thenReturn("value1");

        String result = repository.get("cache:rsshub:url1");

        assertThat(result).isEqualTo("value1");
    }

    @Test
    void shouldDeleteKey() {
        repository.delete("cache:rsshub:url1");

        verify(redisTemplate, times(1)).delete("cache:rsshub:url1");
    }

    @Test
    void shouldExistsReturnTrueWhenKeyHasValue() {
        when(redisTemplate.hasKey("cache:rsshub:url1")).thenReturn(Boolean.TRUE);

        boolean result = repository.exists("cache:rsshub:url1");

        assertThat(result).isTrue();
    }

    @Test
    void shouldExistsReturnFalseWhenKeyMissing() {
        when(redisTemplate.hasKey("missing")).thenReturn(Boolean.FALSE);

        boolean result = repository.exists("missing");

        assertThat(result).isFalse();
    }

    @Test
    void shouldExistsReturnFalseWhenHasKeyReturnsNull() {
        when(redisTemplate.hasKey(any(String.class))).thenReturn(null);

        boolean result = repository.exists("any-key");

        assertThat(result)
                .as("hasKey 返回 null(罕见,但 Spring Data Redis 文档允许)时应安全退化为 false")
                .isFalse();
    }

    @Test
    void shouldExpireKeyWithTtl() {
        Duration ttl = Duration.ofHours(1);

        repository.expire("cache:rsshub:url1", ttl);

        verify(redisTemplate, times(1)).expire("cache:rsshub:url1", ttl);
    }

    @Test
    void shouldNotInteractWithOpsForValueOnExpire() {
        repository.expire("k", Duration.ZERO);

        verify(valueOps, never()).get(any());
        verify(redisTemplate, times(1)).expire(eq("k"), any(Duration.class));
    }

    @Test
    void shouldIncrementByAndSetTtlInOneRedisScript() {
        Duration ttl = Duration.ofDays(7);
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(java.util.List.of("cost:daily:2026-06-28")),
                eq(2L), eq(ttl.toMillis()))).thenReturn(102L);

        long total = repository.incrementBy("cost:daily:2026-06-28", 2L, ttl);

        assertThat(total).isEqualTo(102L);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), eq(java.util.List.of("cost:daily:2026-06-28")),
                eq(2L), eq(ttl.toMillis()));
        verify(valueOps, never()).get(any());
        verify(valueOps, never()).set(any(), any());
    }

    @Test
    void shouldAcceptAnyNumericRedisScriptReturnTypeOnIncrementBy() {
        Duration ttl = Duration.ofDays(7);
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(java.util.List.of("cost:daily:2026-06-28")),
                eq(2L), eq(ttl.toMillis()))).thenReturn(102);

        long total = repository.incrementBy("cost:daily:2026-06-28", 2L, ttl);

        assertThat(total).isEqualTo(102L);
    }

    @Test
    void shouldThrowNonRetryableExceptionWhenIncrementByReturnsNonNumericType() {
        Duration ttl = Duration.ofDays(7);
        // 模拟 Redis 返回非数字类型（如旧数据存储为字符串）
        when(redisTemplate.execute(any(DefaultRedisScript.class), eq(java.util.List.of("cost:daily:2026-06-28")),
                eq(2L), eq(ttl.toMillis()))).thenReturn("invalid-string");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                repository.incrementBy("cost:daily:2026-06-28", 2L, ttl))
                .isInstanceOf(com.choucj.aiaggregator.common.exception.NonRetryableException.class)
                .hasMessageContaining("incrementBy 返回类型异常")
                .hasMessageContaining("actualType=java.lang.String");
    }
}
