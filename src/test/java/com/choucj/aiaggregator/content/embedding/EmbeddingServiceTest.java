package com.choucj.aiaggregator.content.embedding;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import dev.langchain4j.community.store.embedding.redis.RedisRequestFailedException;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 5.1 {@link EmbeddingService} 单元测试 — 覆盖向量生成、存储、异步和异常映射.
 */
class EmbeddingServiceTest {

    private EmbeddingModel embeddingModel;
    private EmbeddingStore<TextSegment> embeddingStore;
    private ExecutorService embeddingExecutor;
    private ScheduledExecutorService timeoutScheduler;
    private EmbeddingServiceImpl service;

    @BeforeEach
    void setUp() {
        embeddingModel = mock(EmbeddingModel.class);
        embeddingStore = mock(EmbeddingStore.class);
        embeddingExecutor = Executors.newSingleThreadExecutor();
        timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
        service = new EmbeddingServiceImpl(embeddingModel, embeddingStore, true, 1024,
                Duration.ofSeconds(120), embeddingExecutor, timeoutScheduler);
    }

    @AfterEach
    void tearDown() {
        embeddingExecutor.shutdownNow();
        timeoutScheduler.shutdownNow();
    }

    @Test
    void shouldReturn1024DimensionVectorWhenEmbeddingSucceeds() {
        when(embeddingModel.embed("AI 内容聚合")).thenReturn(Response.from(Embedding.from(vector(1024))));

        long start = System.nanoTime();
        float[] result = service.embedText("article-1", "AI 内容聚合");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(result).hasSize(1024);
        assertThat(result[0]).isEqualTo(0.1f);
        assertThat(elapsedMs).isLessThan(5_000);
    }

    @Test
    void shouldStoreEmbeddingWithArticleKeyAndTextSegment() {
        float[] embedding = vector(1024);

        service.storeEmbedding("tw-123", "正文摘要", embedding);

        ArgumentCaptor<Embedding> embeddingCaptor = ArgumentCaptor.forClass(Embedding.class);
        ArgumentCaptor<TextSegment> segmentCaptor = ArgumentCaptor.forClass(TextSegment.class);
        ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Embedding>> embeddingsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<TextSegment>> segmentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(embeddingStore).addAll(idsCaptor.capture(), embeddingsCaptor.capture(), segmentsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly("tw-123");
        assertThat(embeddingsCaptor.getValue().getFirst().vector()).hasSize(1024);
        assertThat(segmentsCaptor.getValue().getFirst().text()).isEqualTo("正文摘要");
    }

    @Test
    void shouldRejectBlankText() {
        assertThatThrownBy(() -> service.embedText("article-1", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text");
    }

    @Test
    void shouldRejectNullText() {
        assertThatThrownBy(() -> service.embedText("article-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text");
    }

    @Test
    void shouldMapTimeoutToRetryableException() {
        when(embeddingModel.embed("timeout")).thenThrow(new RuntimeException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> service.embedText("article-1", "timeout"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("GLM embedding 超时");
    }

    @Test
    void shouldMapHttp400ToNonRetryableException() {
        when(embeddingModel.embed("bad-request")).thenThrow(new RuntimeException("HTTP 400 Bad Request"));

        assertThatThrownBy(() -> service.embedText("article-1", "bad-request"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("GLM embedding 请求失败");
    }

    @Test
    void shouldMapHttp408ToRetryableTimeout() {
        when(embeddingModel.embed("http-408")).thenThrow(new RuntimeException("HTTP 408 Request Timeout"));

        assertThatThrownBy(() -> service.embedText("article-1", "http-408"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("GLM embedding 超时");
    }

    @Test
    void shouldMapHttp500ToRetryableException() {
        when(embeddingModel.embed("server-error")).thenThrow(new RuntimeException("HTTP 500 Internal Server Error"));

        assertThatThrownBy(() -> service.embedText("article-1", "server-error"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("GLM embedding 服务异常");
    }

    @Test
    void shouldMapRateLimitToRetryableException() {
        when(embeddingModel.embed("rate-limit")).thenThrow(new RateLimitException("429 Too Many Requests"));

        assertThatThrownBy(() -> service.embedText("article-1", "rate-limit"))
                .isInstanceOf(RetryableException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    void shouldMapLangChainTimeoutToRetryableException() {
        when(embeddingModel.embed("lc-timeout")).thenThrow(new TimeoutException("request timed out"));

        assertThatThrownBy(() -> service.embedText("article-1", "lc-timeout"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("GLM embedding 超时");
    }

    @Test
    void shouldNotMisclassifyArbitraryNumbersAsHttpStatus() {
        when(embeddingModel.embed("bad-number")).thenThrow(new RuntimeException("vector got 0500 samples"));

        assertThatThrownBy(() -> service.embedText("article-1", "bad-number"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("GLM embedding 调用失败");
    }

    @Test
    void shouldNotExtractHttpStatusFromPlainCodeWords() {
        when(embeddingModel.embed("code-review")).thenThrow(new RuntimeException("code review for 400 lines"));
        when(embeddingModel.embed("decode")).thenThrow(new RuntimeException("decode error 500 tokens"));

        assertThatThrownBy(() -> service.embedText("article-1", "code-review"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("GLM embedding 调用失败");
        assertThatThrownBy(() -> service.embedText("article-1", "decode"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("GLM embedding 调用失败");
    }

    @Test
    void shouldKeepTypedInvalidRequestNonRetryableEvenWhenMessageMentionsTimeout() {
        when(embeddingModel.embed("invalid-timeout-param"))
                .thenThrow(new InvalidRequestException("request timeout parameter invalid"));

        assertThatThrownBy(() -> service.embedText("article-1", "invalid-timeout-param"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("GLM embedding 请求失败");
    }

    @Test
    void shouldMapDimensionMismatchToNonRetryableException() {
        when(embeddingModel.embed("wrong-dim")).thenReturn(Response.from(Embedding.from(vector(512))));

        assertThatThrownBy(() -> service.embedText("article-1", "wrong-dim"))
                .isInstanceOf(NonRetryableException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    void shouldRejectNonFiniteVectorBeforeRedisCall() {
        float[] invalid = vector(1024);
        invalid[7] = Float.NaN;

        assertThatThrownBy(() -> service.storeEmbedding("tw-123", "正文摘要", invalid))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("非有限值");
    }

    @Test
    void shouldRejectNullCallerVectorAsBadInput() {
        assertThatThrownBy(() -> service.storeEmbedding("tw-123", "正文摘要", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vector");
    }

    @Test
    void shouldRejectEmptyCallerVectorAsBadInput() {
        assertThatThrownBy(() -> service.searchSimilar(new float[0], 3, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vector");
    }

    @Test
    void shouldRejectInfiniteVectorBeforeRedisCall() {
        float[] invalid = vector(1024);
        invalid[7] = Float.POSITIVE_INFINITY;

        assertThatThrownBy(() -> service.searchSimilar(invalid, 3, 0.0))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("非有限值");
    }

    @Test
    void shouldRejectZeroVectorBeforeRedisCall() {
        assertThatThrownBy(() -> service.searchSimilar(new float[1024], 3, 0.0))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("零范数");
    }

    @Test
    void shouldRejectInvalidMinScoreBeforeRedisCall() {
        assertThatThrownBy(() -> service.searchSimilar(vector(1024), 3, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minScore");
        assertThatThrownBy(() -> service.searchSimilar(vector(1024), 3, 1.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minScore");
    }

    @Test
    void shouldPassSimilarityMinScoreToEmbeddingStore() {
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenReturn(new EmbeddingSearchResult<>(List.of()));

        service.searchSimilar(vector(1024), 3, 0.75);

        ArgumentCaptor<EmbeddingSearchRequest> requestCaptor = ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(embeddingStore).search(requestCaptor.capture());
        assertThat(requestCaptor.getValue().minScore()).isEqualTo(0.75);
    }

    @Test
    void shouldMapRedisConnectionFailureToRetryableRedisError() {
        when(embeddingStore.search(any(EmbeddingSearchRequest.class)))
                .thenThrow(new JedisConnectionException("connection refused"));

        assertThatThrownBy(() -> service.searchSimilar(vector(1024), 3, 0.0))
                .isInstanceOf(RetryableException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REDIS_CONNECTION_ERROR);
    }

    @Test
    void shouldMapRedisDataFailureToNonRetryableRedisError() {
        doThrow(new JedisDataException("wrong vector dimension"))
                .when(embeddingStore).addAll(any(), any(), any());

        assertThatThrownBy(() -> service.storeEmbedding("tw-123", "正文摘要", vector(1024)))
                .isInstanceOf(NonRetryableException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REDIS_DATA_ERROR);
    }

    @Test
    void shouldMapRedisRequestFailureToNonRetryableRedisDataError() {
        doThrow(new RedisRequestFailedException("ERR wrong vector dimension"))
                .when(embeddingStore).addAll(any(), any(), any());

        assertThatThrownBy(() -> service.storeEmbedding("tw-123", "正文摘要", vector(1024)))
                .isInstanceOf(NonRetryableException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.REDIS_DATA_ERROR);
    }

    @Test
    void shouldRejectAsyncBlankInputBeforeDispatch() {
        assertThatThrownBy(() -> service.embedTextAsync("article-1", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text");
    }

    @Test
    void shouldMapAsyncTimeoutToRetryableException() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            EmbeddingServiceImpl timeoutService = new EmbeddingServiceImpl(embeddingModel, embeddingStore,
                    true, 1024, Duration.ofMillis(10), executor, scheduler);
            when(embeddingModel.embed("slow")).thenAnswer(invocation -> {
                Thread.sleep(1000);
                return Response.from(Embedding.from(vector(1024)));
            });

            CompletableFuture<float[]> future = timeoutService.embedTextAsync("article-1", "slow");

            assertThat(future).failsWithin(Duration.ofSeconds(1))
                    .withThrowableOfType(java.util.concurrent.ExecutionException.class)
                    .withCauseInstanceOf(RetryableException.class);
        } finally {
            executor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void shouldReturnRetryableFutureWhenAsyncExecutorIsSaturated() throws Exception {
        ThreadPoolExecutor saturatedExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            saturatedExecutor.submit(() -> {
                Thread.sleep(1_000);
                return null;
            });
            saturatedExecutor.submit(() -> null);
            EmbeddingServiceImpl saturatedService = new EmbeddingServiceImpl(embeddingModel, embeddingStore,
                    true, 1024, Duration.ofSeconds(120), saturatedExecutor, scheduler);

            CompletableFuture<float[]> future = saturatedService.embedTextAsync("article-1", "queued");

            assertThat(future).failsWithin(Duration.ofSeconds(1))
                    .withThrowableOfType(java.util.concurrent.ExecutionException.class)
                    .withCauseInstanceOf(RetryableException.class);
        } finally {
            saturatedExecutor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void shouldReturnRetryableFutureWhenTimeoutSchedulerRejectsAfterWorkerSubmit() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.shutdownNow();
        try {
            when(embeddingModel.embed("scheduler-down")).thenAnswer(invocation -> {
                Thread.sleep(1_000);
                return Response.from(Embedding.from(vector(1024)));
            });
            EmbeddingServiceImpl schedulerDownService = new EmbeddingServiceImpl(embeddingModel, embeddingStore,
                    true, 1024, Duration.ofSeconds(120), executor, scheduler);

            CompletableFuture<float[]> future = schedulerDownService.embedTextAsync("article-1", "scheduler-down");

            assertThat(future).failsWithin(Duration.ofSeconds(1))
                    .withThrowableOfType(java.util.concurrent.ExecutionException.class)
                    .withCauseInstanceOf(RetryableException.class);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldPropagateAsyncCancellationToWorkerTask() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            EmbeddingServiceImpl cancelService = new EmbeddingServiceImpl(embeddingModel, embeddingStore,
                    true, 1024, Duration.ofSeconds(120), executor, scheduler);
            when(embeddingModel.embed("cancel")).thenAnswer(invocation -> {
                Thread.sleep(5_000);
                return Response.from(Embedding.from(vector(1024)));
            });

            CompletableFuture<float[]> future = cancelService.embedTextAsync("article-1", "cancel");
            boolean cancelled = future.cancel(true);

            assertThat(cancelled).isTrue();
            assertThat(future).isCancelled();
        } finally {
            executor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    @Test
    void shouldReturnFutureWhenAsyncEnabled() {
        when(embeddingModel.embed("async")).thenReturn(Response.from(Embedding.from(vector(1024))));

        CompletableFuture<float[]> future = service.embedTextAsync("article-1", "async");

        assertThat(future).succeedsWithin(Duration.ofSeconds(1));
        assertThat(future.join()).hasSize(1024);
    }

    private static float[] vector(int dimension) {
        float[] values = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            values[i] = 0.1f;
        }
        return values;
    }
}
