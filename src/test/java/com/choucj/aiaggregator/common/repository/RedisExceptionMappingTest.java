package com.choucj.aiaggregator.common.repository;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.ClusterStateFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.SerializationException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * Story 1.5b AC-5/6/7 异常映射单测 — 验证 {@link RedisRepositoryImpl} 把底层
 * Spring Data Redis 异常包装为业务异常.
 *
 * <p>覆盖决策表 6 类异常 + {@link RedisSystemException} 根因透传 + Object 方法路径.
 *
 * <p><b>Mockito 严格模式:</b> 每个 stub 都被用到,无 {@code UnnecessaryStubbing}.
 * void 方法用 {@code doThrow().when().method()},有返回值方法用 {@code when().thenThrow()}.
 *
 * <p>1.5a 的 String 方法透传测试见 {@link RedisRepositoryImplTest}(不重测).
 */
@ExtendWith(MockitoExtension.class)
class RedisExceptionMappingTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private RedisTemplate<String, Object> objectRedisTemplate;

    @Mock
    private ValueOperations<String, String> stringValueOps;

    @Mock
    private ValueOperations<String, Object> objectValueOps;

    private RedisRepositoryImpl repository;

    /**
     * 手动构造被测对象,避免 {@code @InjectMocks} 在
     * {@code StringRedisTemplate}(子类) 与 {@code RedisTemplate<String, Object>}(父类)
     * 间因类型擦除产生二义性注入导致 objectRedisTemplate 字段为 null.
     */
    @BeforeEach
    void setUp() {
        repository = new RedisRepositoryImpl(stringRedisTemplate, objectRedisTemplate);
    }

    // ============ AC-5: Retryable 映射(RedisConnectionFailure / QueryTimeout / ClusterStateFailure) ============

    @Test
    void shouldMapConnectionFailureToRetryableException() {
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOps);
        when(stringValueOps.get(anyString()))
                .thenThrow(new RedisConnectionFailureException("conn refused"));

        assertThatThrownBy(() -> repository.get("k"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("get 失败")
                .hasMessageContaining("Redis 连接异常")
                .hasCauseInstanceOf(RedisConnectionFailureException.class)
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_CONNECTION_ERROR);
    }

    @Test
    void shouldMapQueryTimeoutToRetryableException() {
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOps);
        doThrow(new QueryTimeoutException("cmd timeout"))
                .when(stringValueOps).set(anyString(), anyString());

        assertThatThrownBy(() -> repository.set("k", "v"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("set 失败")
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_CONNECTION_ERROR);
    }

    @Test
    void shouldMapClusterStateFailureToRetryableException() {
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOps);
        when(stringValueOps.get(anyString()))
                .thenThrow(new ClusterStateFailureException("cluster rearranging"));

        assertThatThrownBy(() -> repository.get("k"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("get 失败")
                .hasCauseInstanceOf(ClusterStateFailureException.class)
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_CONNECTION_ERROR);
    }

    // ============ AC-6: NonRetryable 映射(InvalidApiUsage / Serialization) ============

    @Test
    void shouldMapInvalidApiUsageToNonRetryableException() {
        when(stringRedisTemplate.opsForValue()).thenReturn(stringValueOps);
        when(stringValueOps.get(anyString()))
                .thenThrow(new InvalidDataAccessApiUsageException("null key not allowed"));

        assertThatThrownBy(() -> repository.get("k"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("get 失败")
                .hasMessageContaining("Redis 数据异常")
                .hasCauseInstanceOf(InvalidDataAccessApiUsageException.class)
                .extracting(e -> ((NonRetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_DATA_ERROR);
    }

    @Test
    void shouldMapSerializationToNonRetryableException() {
        doThrow(new SerializationException("jackson fail"))
                .when(stringRedisTemplate).delete(anyString());

        assertThatThrownBy(() -> repository.delete("k"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("delete 失败")
                .hasCauseInstanceOf(SerializationException.class)
                .extracting(e -> ((NonRetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_DATA_ERROR);
    }

    // ============ AC-7: RedisSystemException 保守归 Retryable + 根因透传 ============

    @Test
    void shouldMapBareRedisSystemExceptionToRetryable() {
        when(stringRedisTemplate.hasKey(anyString()))
                .thenThrow(new RedisSystemException("unknown", new RuntimeException("x")));

        assertThatThrownBy(() -> repository.exists("k"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("保守归可重试")
                .extracting(e -> ((RetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_CONNECTION_ERROR);
    }

    @Test
    void shouldMapRedisSystemExceptionWithSerializationCauseToNonRetryable() {
        SerializationException rootCause = new SerializationException("inner jackson fail");
        when(stringRedisTemplate.hasKey(anyString()))
                .thenThrow(new RedisSystemException("wrapped", rootCause));

        assertThatThrownBy(() -> repository.exists("k"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("根因透传")
                .extracting(e -> ((NonRetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_DATA_ERROR);
    }

    @Test
    void shouldMapRedisSystemExceptionWithInvalidUsageCauseToNonRetryable() {
        InvalidDataAccessApiUsageException rootCause =
                new InvalidDataAccessApiUsageException("inner null key");
        doThrow(new RedisSystemException("wrapped", rootCause))
                .when(stringRedisTemplate).expire(anyString(), any(Duration.class));

        assertThatThrownBy(() -> repository.expire("k", Duration.ofMinutes(1)))
                .isInstanceOf(NonRetryableException.class)
                .extracting(e -> ((NonRetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_DATA_ERROR);
    }

    // ============ Object 方法异常路径(AC-3 + AC-6 在 Object 路径) ============

    @Test
    void shouldMapSerializationExceptionOnSetObjectToNonRetryable() {
        when(objectRedisTemplate.opsForValue()).thenReturn(objectValueOps);
        doThrow(new SerializationException("cannot serialize DO"))
                .when(objectValueOps).set(anyString(), any(Object.class), any(Duration.class));

        assertThatThrownBy(() -> repository.setObject("k", new Object(), Duration.ofMinutes(1)))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("setObject 失败")
                .extracting(e -> ((NonRetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_DATA_ERROR);
    }

    @Test
    void shouldThrowNonRetryableWhenGetObjectHasTypeMismatch() {
        when(objectRedisTemplate.opsForValue()).thenReturn(objectValueOps);
        when(objectValueOps.get(anyString())).thenReturn("not-a-Duration-instance");

        assertThatThrownBy(() -> repository.getObject("k", Duration.class))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("类型不匹配")
                .hasMessageContaining("expected=java.time.Duration")
                .extracting(e -> ((NonRetryableException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_DATA_ERROR);
    }

    // ============ Object 方法 happy path(验证异常映射不破坏正常路径) ============

    @Test
    void shouldGetObjectReturnNullWhenKeyMissing() {
        when(objectRedisTemplate.opsForValue()).thenReturn(objectValueOps);
        when(objectValueOps.get(anyString())).thenReturn(null);

        assertThat(repository.getObject("missing", String.class))
                .as("键不存在时 getObject 返回 null,不抛异常")
                .isNull();
    }

    @Test
    void shouldGetObjectReturnCastedObjectWhenTypeMatches() {
        when(objectRedisTemplate.opsForValue()).thenReturn(objectValueOps);
        when(objectValueOps.get(anyString())).thenReturn("hello");

        assertThat(repository.getObject("k", String.class))
                .isEqualTo("hello");
    }
}
