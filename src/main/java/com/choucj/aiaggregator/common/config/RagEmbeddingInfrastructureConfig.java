package com.choucj.aiaggregator.common.config;

import com.choucj.aiaggregator.common.util.RedisKeys;
import dev.langchain4j.community.store.embedding.redis.RedisEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RAG embedding 基础设施配置，隔离线程池和 standalone Redis-Stack 连接生命周期.
 *
 * <p>引用源: Story 5.1 Round 3 review。模型 Bean 保持在 {@link EmbeddingConfig}，本类只管理
 * 执行器、超时调度器、Jedis 客户端与 RedisEmbeddingStore。
 */
@Configuration
@ConditionalOnProperty(prefix = "features.rag", name = "enabled", havingValue = "true")
@Slf4j
public class RagEmbeddingInfrastructureConfig {

    private static final String DIMENSIONS = "langchain4j.open-ai.embedding-model.dimensions";
    private static final String TIMEOUT = "langchain4j.open-ai.embedding-model.timeout";
    private static final String REDIS_HOST = "langchain4j.community.redis.host";
    private static final String REDIS_PORT = "langchain4j.community.redis.port";
    private static final String REDIS_USER = "langchain4j.community.redis.user";
    private static final String REDIS_PASSWORD = "langchain4j.community.redis.password";
    private static final String REDIS_INDEX = "langchain4j.community.redis.index-name";
    private static final String REDIS_PREFIX = "langchain4j.community.redis.prefix";
    private static final String REDIS_DIMENSION = "langchain4j.community.redis.dimension";
    private static final String ASYNC_THREADS = "features.rag.async-threads";
    private static final String ASYNC_QUEUE_CAPACITY = "features.rag.async-queue-capacity";
    private static final String ASYNC_TIMEOUT = "features.rag.async-timeout";
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration SHUTDOWN_NOW_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Embedding 异步专用线程池，避免阻塞 HTTP 调用占用 ForkJoin commonPool.
     *
     * @param env Spring 环境配置
     * @return 有界固定线程池
     */
    @Bean(name = "embeddingExecutor", destroyMethod = "close")
    public ExecutorService embeddingExecutor(Environment env) {
        Duration modelTimeout = parseDuration(env.getProperty(TIMEOUT), Duration.ofSeconds(60));
        Duration asyncTimeout = parseDuration(env.getProperty(ASYNC_TIMEOUT), Duration.ofSeconds(120));
        requirePositive(asyncTimeout, ASYNC_TIMEOUT);
        requirePositive(modelTimeout, TIMEOUT);
        if (asyncTimeout.compareTo(modelTimeout) < 0) {
            log.warn("Embedding async-timeout 小于模型 timeout: asyncTimeout={}, modelTimeout={}; "
                    + "在途 HTTP 可能继续占用 worker", asyncTimeout, modelTimeout);
        }
        int threads = requirePositive(env.getProperty(ASYNC_THREADS, Integer.class, 2), ASYNC_THREADS);
        int queueCapacity = requirePositive(env.getProperty(ASYNC_QUEUE_CAPACITY, Integer.class, 100),
                ASYNC_QUEUE_CAPACITY);
        return new GracefulEmbeddingExecutor(threads, queueCapacity);
    }

    /**
     * Embedding 异步超时调度器.
     *
     * @return 支持取消清理的单线程调度器
     */
    @Bean(name = "embeddingTimeoutScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService embeddingTimeoutScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1,
                new NamedDaemonThreadFactory("embedding-timeout-"));
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /**
     * standalone Redis-Stack Jedis 客户端.
     *
     * <p>单独声明为 Bean 是为了让 Spring 在上下文关闭时调用 {@code close()}，避免连接资源泄漏。
     *
     * @param env Spring 环境配置
     * @return Jedis 客户端
     */
    @Bean(name = "embeddingUnifiedJedis", destroyMethod = "close")
    @ConditionalOnMissingBean(name = "embeddingUnifiedJedis")
    @ConditionalOnProperty(prefix = "langchain4j.community.redis", name = "enabled", havingValue = "true")
    public UnifiedJedis embeddingUnifiedJedis(Environment env) {
        DefaultJedisClientConfig.Builder clientConfig = DefaultJedisClientConfig.builder();
        String user = env.getProperty(REDIS_USER);
        String password = env.getProperty(REDIS_PASSWORD);
        if (user != null && !user.isBlank()) {
            clientConfig.user(user);
        }
        if (password != null && !password.isBlank()) {
            clientConfig.password(password);
        }

        return new UnifiedJedis(
                new HostAndPort(env.getProperty(REDIS_HOST, "localhost"),
                        env.getProperty(REDIS_PORT, Integer.class, 7100)),
                clientConfig.build());
    }

    /**
     * standalone Redis-Stack 向量存储 Bean.
     *
     * <p>返回具体 {@link RedisEmbeddingStore} 类型，让 starter 的 {@code @ConditionalOnMissingBean}
     * 能正确识别自定义 store 并退让，避免生产启动路径出现重复 EmbeddingStore。
     *
     * @param jedis Jedis 客户端
     * @param env   Spring 环境配置
     * @return RediSearch-backed RedisEmbeddingStore
     */
    @Bean
    @ConditionalOnProperty(prefix = "langchain4j.community.redis", name = "enabled", havingValue = "true")
    public RedisEmbeddingStore embeddingStore(@Qualifier("embeddingUnifiedJedis") UnifiedJedis jedis,
                                              Environment env) {
        int modelDimension = requirePositive(env.getProperty(DIMENSIONS, Integer.class, 1024), DIMENSIONS);
        int redisDimension = requirePositive(env.getProperty(REDIS_DIMENSION, Integer.class, modelDimension),
                REDIS_DIMENSION);
        if (modelDimension != redisDimension) {
            throw new IllegalStateException("Embedding model dimension must match Redis schema dimension: model="
                    + modelDimension + ", redis=" + redisDimension);
        }
        String indexName = env.getProperty(REDIS_INDEX, "aiaggregator-embeddings");
        String prefix = env.getProperty(REDIS_PREFIX, RedisKeys.embeddingPrefix());
        RedisEmbeddingStore store = RedisEmbeddingStore.builder()
                .unifiedJedis(jedis)
                .indexName(indexName)
                .prefix(prefix)
                .dimension(redisDimension)
                .build();
        validateRedisIndexSchema(jedis, indexName, prefix, redisDimension);
        return store;
    }

    private static Duration parseDuration(String raw, Duration defaultValue) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return DurationStyle.detectAndParse(raw);
    }

    private static int requirePositive(int value, String propertyName) {
        if (value <= 0) {
            throw new IllegalStateException(propertyName + " must be > 0");
        }
        return value;
    }

    private static void requirePositive(Duration value, String propertyName) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalStateException(propertyName + " must be > 0");
        }
    }

    private static void validateRedisIndexSchema(UnifiedJedis jedis, String indexName, String prefix, int dimension) {
        Map<String, Object> info = jedis.ftInfo(indexName);
        String flattened = flatten(info).toLowerCase(Locale.ROOT);
        requireContains(flattened, "vector", "vector field", indexName);
        requireContains(flattened, "float32", "FLOAT32 vector type", indexName);
        requireContains(flattened, Integer.toString(dimension), "dimension " + dimension, indexName);
        requireContains(flattened, "cosine", "COSINE distance metric", indexName);
        requireContains(flattened, prefix.toLowerCase(Locale.ROOT), "prefix " + prefix, indexName);
    }

    private static void requireContains(String flattened, String expected, String label, String indexName) {
        if (!flattened.contains(expected)) {
            throw new IllegalStateException("RediSearch index schema mismatch for " + indexName
                    + ": missing " + label);
        }
    }

    private static String flatten(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder();
            map.forEach((key, item) -> builder.append(' ')
                    .append(flatten(key))
                    .append(' ')
                    .append(flatten(item)));
            return builder.toString();
        }
        if (value instanceof Collection<?> collection) {
            StringBuilder builder = new StringBuilder();
            for (Object item : collection) {
                builder.append(' ').append(flatten(item));
            }
            return builder.toString();
        }
        if (value.getClass().isArray()) {
            if (value instanceof Object[] array) {
                return flatten(List.of(array));
            }
            return value.toString();
        }
        return value.toString();
    }

    private static final class GracefulEmbeddingExecutor extends ThreadPoolExecutor implements AutoCloseable {

        GracefulEmbeddingExecutor(int threads, int queueCapacity) {
            super(threads, threads, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(queueCapacity),
                    new NamedDaemonThreadFactory("embedding-worker-"),
                    new AbortPolicy());
        }

        @Override
        public void close() {
            shutdown();
            try {
                if (!awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    log.warn("Embedding executor 未在 {} 内完成关闭, remainingTasks={}",
                            SHUTDOWN_TIMEOUT, getQueue().size());
                    List<Runnable> droppedTasks = shutdownNow();
                    log.warn("Embedding executor 已强制关闭, cancelledTasks={}", droppedTasks.size());
                    if (!awaitTermination(SHUTDOWN_NOW_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        log.warn("Embedding executor 强制关闭后仍有任务未结束, activeCount={}", getActiveCount());
                    }
                }
            } catch (InterruptedException e) {
                List<Runnable> droppedTasks = shutdownNow();
                Thread.currentThread().interrupt();
                log.warn("Embedding executor 关闭等待被中断, cancelledTasks={}", droppedTasks.size());
            }
        }
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();
        private final String prefix;

        private NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
