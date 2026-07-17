package com.choucj.aiaggregator.content.embedding;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import dev.langchain4j.community.store.embedding.redis.RedisRequestFailedException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisBroadcastException;
import redis.clients.jedis.exceptions.JedisClusterException;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisValidationException;

/**
 * LangChain4j + Redis-Stack 实现 {@link EmbeddingService}.
 *
 * <p>引用源: Story 5.1。异常映射复用 W1+W2: 已映射业务异常透传，第三方 RuntimeException
 * 按超时/HTTP 状态码映射到 Retryable 或 NonRetryable。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "features.rag", name = "enabled", havingValue = "true")
public class EmbeddingServiceImpl implements EmbeddingService {

    private static final String UNKNOWN_ARTICLE = "unknown";
    private static final int LOG_REASON_LIMIT = 120;
    private static final Pattern HTTP_STATUS = Pattern.compile("(?i)(?:http|status(?:code)?|status code)"
            + "[^0-9]{0,16}([45]\\d\\d)\\b");

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final boolean batchAsyncEnabled;
    private final int expectedDimension;
    private final Duration asyncTimeout;
    private final ExecutorService embeddingExecutor;
    private final ScheduledExecutorService timeoutScheduler;

    /**
     * 构造 EmbeddingServiceImpl.
     *
     * @param embeddingModel      GLM embedding 模型
     * @param embeddingStore      可选 RediSearch 向量存储
     * @param batchAsyncEnabled   批量异步开关
     * @param expectedDimension   期望向量维度
     * @param asyncTimeout        异步调用超时
     */
    @Autowired
    public EmbeddingServiceImpl(@Qualifier("embeddingModel") EmbeddingModel embeddingModel,
                                ObjectProvider<EmbeddingStore<TextSegment>> embeddingStore,
                                @Qualifier("embeddingExecutor") ExecutorService embeddingExecutor,
                                @Qualifier("embeddingTimeoutScheduler") ScheduledExecutorService timeoutScheduler,
                                @Value("${feature-flags.rag.batch-async-enabled:false}") boolean batchAsyncEnabled,
                                @Value("${langchain4j.open-ai.embedding-model.dimensions:1024}") int expectedDimension,
                                @Value("${features.rag.async-timeout:120s}") String asyncTimeout) {
        this(embeddingModel, embeddingStore.getIfAvailable(), batchAsyncEnabled, expectedDimension,
                parseDuration(asyncTimeout, Duration.ofSeconds(120)), embeddingExecutor, timeoutScheduler);
    }

    EmbeddingServiceImpl(EmbeddingModel embeddingModel,
                         EmbeddingStore<TextSegment> embeddingStore,
                         boolean batchAsyncEnabled,
                         int expectedDimension,
                         Duration asyncTimeout,
                         ExecutorService embeddingExecutor,
                         ScheduledExecutorService timeoutScheduler) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.batchAsyncEnabled = batchAsyncEnabled;
        this.expectedDimension = expectedDimension;
        this.asyncTimeout = asyncTimeout;
        this.embeddingExecutor = embeddingExecutor;
        this.timeoutScheduler = timeoutScheduler;
    }

    @Override
    public float[] embedText(String text) {
        return embedText(UNKNOWN_ARTICLE, text);
    }

    @Override
    public float[] embedText(String articleId, String text) {
        requireNonBlank(articleId, "articleId");
        requireNonBlank(text, "text");
        long start = System.currentTimeMillis();
        try {
            float[] vector = Optional.ofNullable(embeddingModel.embed(text).content())
                    .map(Embedding::vector)
                    .orElseThrow(() -> new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                            "GLM embedding 响应为空: articleId=" + articleId));
            validateModelVector(articleId, vector);
            log.info("EmbeddingService 调用成功: articleId={}, 文本长度={}, 向量维度={}, 耗时={}ms",
                    articleId, text.codePointCount(0, text.length()), vector.length,
                    System.currentTimeMillis() - start);
            return vector;
        } catch (RetryableException | NonRetryableException e) {
            logMappedEmbeddingFailure(articleId, e);
            throw e;
        } catch (RuntimeException e) {
            throw mapEmbeddingException(articleId, e);
        }
    }

    @Override
    public CompletableFuture<float[]> embedTextAsync(String text) {
        return embedTextAsync(UNKNOWN_ARTICLE, text);
    }

    @Override
    public CompletableFuture<float[]> embedTextAsync(String articleId, String text) {
        requireNonBlank(articleId, "articleId");
        requireNonBlank(text, "text");
        if (!batchAsyncEnabled) {
            CompletableFuture<float[]> failed = new CompletableFuture<>();
            failed.completeExceptionally(new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "批量 Embedding 异步未启用: feature-flags.rag.batch-async-enabled=false"));
            return failed;
        }
        AtomicReference<Future<?>> taskRef = new AtomicReference<>();
        AtomicReference<ScheduledFuture<?>> timeoutRef = new AtomicReference<>();
        CompletableFuture<float[]> result = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                Future<?> task = taskRef.get();
                if (task != null) {
                    task.cancel(mayInterruptIfRunning);
                }
                ScheduledFuture<?> timeout = timeoutRef.get();
                if (timeout != null) {
                    timeout.cancel(false);
                }
                return super.cancel(mayInterruptIfRunning);
            }
        };
        try {
            Future<?> task = embeddingExecutor.submit(() -> {
                try {
                    result.complete(embedText(articleId, text));
                } catch (RuntimeException e) {
                    result.completeExceptionally(e);
                }
            });
            taskRef.set(task);
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "批量 Embedding 队列已满: articleId=" + articleId, e));
            return result;
        }
        ScheduledFuture<?> timeout;
        try {
            timeout = timeoutScheduler.schedule(() -> {
                if (result.completeExceptionally(new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                        "GLM embedding 超时: articleId=" + articleId))) {
                    Future<?> task = taskRef.get();
                    if (task != null) {
                        task.cancel(true);
                    }
                }
            }, asyncTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            Future<?> task = taskRef.get();
            if (task != null) {
                task.cancel(true);
            }
            result.completeExceptionally(new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "批量 Embedding 超时调度器不可用: articleId=" + articleId, e));
            return result;
        }
        timeoutRef.set(timeout);
        result.whenComplete((ignored, throwable) -> timeout.cancel(false));
        if (result.isCancelled()) {
            timeout.cancel(false);
            Future<?> task = taskRef.get();
            if (task != null) {
                task.cancel(true);
            }
        }
        return result;
    }

    @Override
    public void storeEmbedding(String articleId, String text, float[] vector) {
        requireStoreConfigured(articleId);
        requireNonBlank(articleId, "articleId");
        requireNonBlank(text, "text");
        validateCallerVector(articleId, vector);
        long start = System.currentTimeMillis();
        try {
            embeddingStore.addAll(List.of(articleId), List.of(Embedding.from(vector)), List.of(TextSegment.from(text)));
            log.info("EmbeddingService 向量写入成功: articleId={}, 文本长度={}, 向量维度={}, 耗时={}ms",
                    articleId, text.codePointCount(0, text.length()), vector.length,
                    System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            log.warn("EmbeddingService 向量写入失败: articleId={}, 错误类={}, 原因={}",
                    articleId, e.getClass().getSimpleName(), truncate(e.getMessage()));
            throw mapRedisException("RediSearch 向量写入失败", articleId, e);
        }
    }

    @Override
    public EmbeddingSearchResult<TextSegment> searchSimilar(float[] vector, int maxResults, double minScore) {
        requireStoreConfigured(UNKNOWN_ARTICLE);
        validateCallerVector(UNKNOWN_ARTICLE, vector);
        if (maxResults < 1) {
            throw new IllegalArgumentException("maxResults must be >= 1");
        }
        if (!Double.isFinite(minScore) || minScore < 0.0 || minScore > 1.0) {
            throw new IllegalArgumentException("minScore must be finite and between 0.0 and 1.0");
        }
        long start = System.currentTimeMillis();
        try {
            EmbeddingSearchResult<TextSegment> result = embeddingStore.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(Embedding.from(vector))
                    .maxResults(maxResults)
                    .minScore(minScore)
                    .build());
            log.info("EmbeddingService 向量检索成功: maxResults={}, minScore={}, 返回数量={}, 耗时={}ms",
                    maxResults, minScore, result.matches().size(), System.currentTimeMillis() - start);
            return result;
        } catch (RuntimeException e) {
            log.warn("EmbeddingService 向量检索失败: 错误类={}, 原因={}",
                    e.getClass().getSimpleName(), truncate(e.getMessage()));
            throw mapRedisException("RediSearch 向量检索失败", UNKNOWN_ARTICLE, e);
        }
    }

    private void requireStoreConfigured(String articleId) {
        if (embeddingStore == null) {
            throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "RediSearch embedding store 未配置: articleId=" + articleId);
        }
    }

    private void validateModelVector(String articleId, float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "GLM embedding 响应向量为空: articleId=" + articleId);
        }
        if (expectedDimension > 0 && vector.length != expectedDimension) {
            throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "GLM embedding 维度不匹配: articleId=" + articleId
                            + ", expected=" + expectedDimension + ", actual=" + vector.length);
        }
        double normSquared = 0.0;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                        "GLM embedding 向量包含非有限值: articleId=" + articleId);
            }
            normSquared += (double) value * value;
        }
        if (normSquared == 0.0) {
            throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "GLM embedding 向量为零范数: articleId=" + articleId);
        }
    }

    private void validateCallerVector(String articleId, float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("vector must not be null or empty: articleId=" + articleId);
        }
        validateModelVector(articleId, vector);
    }

    private void logMappedEmbeddingFailure(String articleId, RuntimeException e) {
        String reason = truncate(e.getMessage());
        if (e instanceof NonRetryableException) {
            log.warn("EmbeddingService 调用失败: articleId={}, 错误类={}, 状态码={}, 原因={}",
                    articleId, e.getClass().getSimpleName(), null, reason);
            return;
        }
        log.warn("EmbeddingService 调用失败: articleId={}, 错误类={}, 状态码={}, 原因={}",
                articleId, e.getClass().getSimpleName(), null, reason);
    }

    private RuntimeException mapEmbeddingException(String articleId, RuntimeException e) {
        Integer statusCode = extractHttpStatus(e);
        String reason = truncate(e.getMessage());
        if (e instanceof RateLimitException || statusCode != null && statusCode == 429) {
            log.warn("EmbeddingService 调用失败: articleId={}, 错误类={}, 状态码={}, 原因={}",
                    articleId, e.getClass().getSimpleName(), 429, reason);
            return new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "GLM embedding 限流: articleId=" + articleId + ", statusCode=429", e);
        }
        if (e instanceof dev.langchain4j.exception.TimeoutException
                || statusCode != null && statusCode == 408) {
            log.warn("EmbeddingService 调用失败: articleId={}, 错误类={}, 状态码={}, 原因={}",
                    articleId, e.getClass().getSimpleName(), statusCode, reason);
            return new RetryableException(ErrorCode.EXTERNAL_API_ERROR, "GLM embedding 超时: articleId=" + articleId, e);
        }
        if (e instanceof InvalidRequestException
                || e instanceof AuthenticationException
                || e instanceof ModelNotFoundException
                || e instanceof dev.langchain4j.exception.NonRetriableException
                || statusCode != null && statusCode >= 400 && statusCode < 500) {
            log.warn("EmbeddingService 调用失败: articleId={}, 错误类={}, 状态码={}, 原因={}",
                    articleId, e.getClass().getSimpleName(), statusCode, reason);
            return new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "GLM embedding 请求失败: articleId=" + articleId
                            + (statusCode != null ? ", statusCode=" + statusCode : ""), e);
        }
        if (isTimeout(e)) {
            log.warn("EmbeddingService 调用失败: articleId={}, 错误类={}, 状态码={}, 原因={}",
                    articleId, e.getClass().getSimpleName(), statusCode, reason);
            return new RetryableException(ErrorCode.EXTERNAL_API_ERROR, "GLM embedding 超时: articleId=" + articleId, e);
        }
        if (e instanceof InternalServerException
                || e instanceof dev.langchain4j.exception.RetriableException
                || statusCode != null && statusCode >= 500) {
            log.warn("EmbeddingService 调用失败: articleId={}, 错误类={}, 状态码={}, 原因={}",
                    articleId, e.getClass().getSimpleName(), statusCode, reason);
            return new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "GLM embedding 服务异常: articleId=" + articleId
                            + (statusCode != null ? ", statusCode=" + statusCode : ""), e);
        }
        log.error("EmbeddingService 调用异常: articleId={}, 异常类型={}, message={}",
                articleId, e.getClass().getSimpleName(), reason);
        return new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                "GLM embedding 调用失败: articleId=" + articleId + ", reason=" + reason, e);
    }

    private static Integer extractHttpStatus(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof HttpException httpException) {
                return httpException.statusCode();
            }
            String message = current.getMessage();
            if (message != null) {
                Matcher matcher = HTTP_STATUS.matcher(message);
                if (matcher.find()) {
                    return Integer.parseInt(matcher.group(1));
                }
            }
            current = current.getCause();
        }
        return null;
    }

    private static boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof dev.langchain4j.exception.TimeoutException
                    || current instanceof java.util.concurrent.TimeoutException
                    || current instanceof SocketTimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(java.util.Locale.ROOT);
                if (normalized.contains("timeout") || normalized.contains("timed out")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static RuntimeException mapRedisException(String operation, String articleId, RuntimeException e) {
        String reason = truncate(e.getMessage());
        if (isRedisDataError(e)) {
            return new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    operation + ": articleId=" + articleId + ", reason=" + reason, e);
        }
        if (isRedisConnectionError(e)) {
            return new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR,
                    operation + ": articleId=" + articleId + ", reason=" + reason, e);
        }
        return new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                operation + ": articleId=" + articleId + ", reason=" + reason, e);
    }

    private static boolean isRedisConnectionError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof JedisConnectionException
                    || current instanceof JedisClusterException
                    || current instanceof JedisBroadcastException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isRedisDataError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof JedisDataException
                    || current instanceof RedisRequestFailedException
                    || current instanceof JedisAccessControlException
                    || current instanceof JedisValidationException
                    || current instanceof IllegalArgumentException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "<null>";
        }
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= LOG_REASON_LIMIT) {
            return value;
        }
        int end = value.offsetByCodePoints(0, LOG_REASON_LIMIT - 3);
        return value.substring(0, end) + "...";
    }

    private static Duration parseDuration(String raw, Duration defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return DurationStyle.detectAndParse(raw);
    }
}
