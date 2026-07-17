package com.choucj.aiaggregator.content.rewriter;

import com.choucj.aiaggregator.common.client.LlmClient;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.content.rag.service.ReferenceRetriever;
import com.choucj.aiaggregator.content.rewriter.config.RewriterProperties;
import com.choucj.aiaggregator.monitoring.TokenUsageTracker;
import com.choucj.aiaggregator.source.github.model.GitHubRepo;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Story 5.4 多模型投票改写器.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "feature-flags.multi-model", name = "enabled", havingValue = "true")
public class MultiModelRewriter implements ContentRewriter {

    private static final String MODEL_DEEPSEEK = "deepseek";
    private static final String MODEL_GLM = "glm";
    private final LlmClient llmClient;
    private final SingleModelRewriter singleModelDelegate;
    private final ModelScorer modelScorer;
    private final RewriterProperties properties;
    private final ExecutorService modelExecutor;

    @Autowired
    public MultiModelRewriter(LlmClient llmClient,
                              RewriterProperties properties,
                              TokenUsageTracker tokenUsageTracker,
                              Optional<ReferenceRetriever> referenceRetriever,
                              ModelScorer modelScorer) {
        this.llmClient = llmClient;
        this.singleModelDelegate = new SingleModelRewriter(llmClient, properties, tokenUsageTracker, referenceRetriever);
        this.modelScorer = modelScorer;
        this.properties = properties;
        this.modelExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public Article rewrite(Tweet tweet) {
        SingleModelRewriter.PreparedRewrite prepared = singleModelDelegate.prepareRewrite(tweet);
        return rewriteWithBothModels(prepared, response -> singleModelDelegate.buildArticle(tweet, response));
    }

    @Override
    public Article rewrite(GitHubRepo repo) {
        SingleModelRewriter.PreparedRewrite prepared = singleModelDelegate.prepareRewrite(repo);
        return rewriteWithBothModels(prepared,
                response -> singleModelDelegate.buildGitHubArticle(repo, response, prepared.normalizedFullName()));
    }

    @PreDestroy
    void shutdownExecutor() {
        modelExecutor.shutdown();
    }

    private Article rewriteWithBothModels(SingleModelRewriter.PreparedRewrite prepared,
                                          Function<String, Article> articleFactory) {
        LocalDate trackingDate = LocalDate.now();
        long startNanos = System.nanoTime();
        long deadlineNanos = startNanos + TimeUnit.MILLISECONDS.toNanos(properties.getMultiModelDeadlineMs());
        Future<ModelOutcome> deepSeekFuture = modelExecutor.submit(
                () -> callModel(MODEL_DEEPSEEK, prepared, articleFactory, trackingDate));
        Future<ModelOutcome> glmFuture = modelExecutor.submit(
                () -> callModel(MODEL_GLM, prepared, articleFactory, trackingDate));

        ModelOutcome deepSeek = awaitOutcome(deepSeekFuture, MODEL_DEEPSEEK, deadlineNanos);
        ModelOutcome glm = awaitOutcome(glmFuture, MODEL_GLM, deadlineNanos);
        return selectWinner(prepared, deepSeek, glm, deadlineNanos,
                Duration.ofNanos(System.nanoTime() - startNanos));
    }

    private ModelOutcome callModel(String model,
                                   SingleModelRewriter.PreparedRewrite prepared,
                                   Function<String, Article> articleFactory,
                                   LocalDate trackingDate) {
        try {
            String response = callWithRetry(model, prepared);
            throwIfInterrupted(model, prepared.sourceId(), "LLM 调用返回后");
            Article article = articleFactory.apply(response);
            throwIfInterrupted(model, prepared.sourceId(), "候选解析后");
            int estimatedTokens = singleModelDelegate.trackSuccessfulCall(model, prepared, response, trackingDate);
            log.info("多模型改写候选成功: model={}, sourceId={}, 估算 token={}",
                    model, prepared.sourceId(), estimatedTokens);
            return ModelOutcome.success(model, article);
        } catch (RetryableException e) {
            RetryableException sanitized = new RetryableException(e.getErrorCode(),
                    "多模型候选失败 model=" + model + ", sourceId=" + prepared.sourceId()
                            + ", errorType=" + e.getClass().getSimpleName());
            log.warn("多模型改写候选失败: model={}, sourceId={}, errorType={}",
                    model, prepared.sourceId(), e.getClass().getSimpleName());
            return ModelOutcome.failure(model, sanitized);
        } catch (RuntimeException e) {
            RetryableException wrapped = new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "多模型候选失败 model=" + model + ", sourceId=" + prepared.sourceId()
                            + ", errorType=" + e.getClass().getSimpleName());
            log.warn("多模型改写候选失败: model={}, sourceId={}, errorType={}",
                    model, prepared.sourceId(), e.getClass().getSimpleName());
            return ModelOutcome.failure(model, wrapped);
        }
    }

    /**
     * {@link Future#cancel(boolean)} 只能协作式中断：若 HTTP 客户端忽略中断并正常返回，
     * 此处阻止后续 Article 解析和 Token 观测产生晚到副作用。
     */
    private static void throwIfInterrupted(String model, String sourceId, String phase) {
        if (Thread.currentThread().isInterrupted()) {
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "多模型候选被中断: model=" + model + ", sourceId=" + sourceId + ", phase=" + phase);
        }
    }

    private String callWithRetry(String model, SingleModelRewriter.PreparedRewrite prepared) {
        int maxRetries = properties.getMaxRetries();
        long backoffMs = properties.getRetryBackoffMs();
        RetryableException lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return llmClient.chatWithModel(model, prepared.systemPrompt(), prepared.userPrompt());
            } catch (RetryableException e) {
                lastException = e;
                if (attempt < maxRetries) {
                    long sleepMs = backoffMs * (attempt + 1);
                    log.warn("多模型改写失败, 第 {} 次重试 ({}ms 后): model={}, sourceId={}, errorType={}",
                            attempt + 1, sleepMs, model, prepared.sourceId(), e.getClass().getSimpleName());
                    sleepBeforeRetry(model, prepared.sourceId(), sleepMs);
                }
            } catch (RuntimeException e) {
                log.error("多模型改写失败 (不可重试异常): model={}, sourceId={}, errorType={}",
                        model, prepared.sourceId(), e.getClass().getSimpleName());
                throw e;
            }
        }
        int totalAttempts = maxRetries + 1;
        throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                "多模型候选失败 model=" + model + ", sourceId=" + prepared.sourceId()
                        + " (" + totalAttempts + " 次尝试均失败, errorType="
                        + (lastException == null ? "unknown" : lastException.getClass().getSimpleName()) + ")");
    }

    private static void sleepBeforeRetry(String model, String sourceId, long sleepMs) {
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "多模型改写被中断: model=" + model + ", sourceId=" + sourceId);
        }
    }

    private ModelOutcome awaitOutcome(Future<ModelOutcome> future, String model, long deadlineNanos) {
        try {
            if (future.isDone()) {
                return future.get();
            }
            return future.get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ModelOutcome.failure(model, new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "多模型候选超时 model=" + model + ", deadlineMs=" + properties.getMultiModelDeadlineMs()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ModelOutcome.failure(model, new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "多模型候选等待被中断 model=" + model));
        } catch (ExecutionException e) {
            Throwable root = e.getCause();
            if (root instanceof RetryableException retryableException) {
                return ModelOutcome.failure(model, retryableException);
            }
            return ModelOutcome.failure(model, new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                    "多模型候选异常 model=" + model + ", errorType=" + root.getClass().getSimpleName()));
        }
    }

    private Article selectWinner(SingleModelRewriter.PreparedRewrite prepared,
                                 ModelOutcome deepSeek,
                                 ModelOutcome glm,
                                 long deadlineNanos,
                                 Duration elapsed) {
        if (deepSeek.success() && glm.success()) {
            ModelScorer.SourceContext sourceContext =
                    new ModelScorer.SourceContext(prepared.sourceId(), prepared.sourceText());
            ModelScorer.ScoreResult deepSeekScore = scoreWithDeadline(deepSeek, sourceContext, deadlineNanos);
            ModelScorer.ScoreResult glmScore = scoreWithDeadline(glm, sourceContext, deadlineNanos);
            boolean deepSeekWins = deepSeekScore.totalScore() >= glmScore.totalScore();
            ModelOutcome winner = deepSeekWins ? deepSeek : glm;
            log.info("多模型投票完成: sourceId={}, winner={}, deepseekScore={}, glmScore={}, deepseekDims={}, glmDims={}, elapsedMs={}",
                    prepared.sourceId(), winner.model(), deepSeekScore.totalScore(), glmScore.totalScore(),
                    formatDimensions(deepSeekScore), formatDimensions(glmScore), elapsed.toMillis());
            return winner.article();
        }
        if (deepSeek.success()) {
            log.info("多模型投票降级: sourceId={}, winner=deepseek, loser=glm, elapsedMs={}",
                    prepared.sourceId(), elapsed.toMillis());
            return deepSeek.article();
        }
        if (glm.success()) {
            log.info("多模型投票降级: sourceId={}, winner=glm, loser=deepseek, elapsedMs={}",
                    prepared.sourceId(), elapsed.toMillis());
            return glm.article();
        }
        RetryableException failure = new RetryableException(ErrorCode.EXTERNAL_API_ERROR,
                "多模型改写失败: sourceId=" + prepared.sourceId(), deepSeek.error());
        if (glm.error() != null) {
            failure.addSuppressed(glm.error());
        }
        throw failure;
    }

    private static String formatDimensions(ModelScorer.ScoreResult score) {
        Objects.requireNonNull(score, "score");
        return "coherence=" + score.coherenceScore()
                + ",accuracy=" + score.accuracyScore()
                + ",readability=" + score.readabilityScore()
                + ",humanTone=" + score.humanToneScore();
    }

    private ModelScorer.ScoreResult scoreWithDeadline(ModelOutcome outcome,
                                                      ModelScorer.SourceContext sourceContext,
                                                      long deadlineNanos) {
        Future<ModelScorer.ScoreResult> future = modelExecutor.submit(scoreTask(outcome, sourceContext));
        try {
            return future.get(remainingMillis(deadlineNanos), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("多模型评分超时, 将候选视为最低分: model={}, sourceId={}, deadlineMs={}",
                    outcome.model(), sourceContext.sourceId(), properties.getMultiModelDeadlineMs());
            return minimumScore(outcome.model(), "SCORER_TIMEOUT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            log.warn("多模型评分等待被中断, 将候选视为最低分: model={}, sourceId={}",
                    outcome.model(), sourceContext.sourceId());
            return minimumScore(outcome.model(), "SCORER_INTERRUPTED");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.warn("多模型评分异常, 将候选视为最低分: model={}, sourceId={}, errorType={}",
                    outcome.model(), sourceContext.sourceId(), cause.getClass().getSimpleName());
            return minimumScore(outcome.model(), "SCORER_ERROR");
        }
    }

    private Callable<ModelScorer.ScoreResult> scoreTask(ModelOutcome outcome,
                                                        ModelScorer.SourceContext sourceContext) {
        return () -> modelScorer.score(outcome.article(), sourceContext, outcome.model());
    }

    private static ModelScorer.ScoreResult minimumScore(String model, String reason) {
        return new ModelScorer.ScoreResult(model, Integer.MIN_VALUE, 0, 0, 0, 0, java.util.List.of(reason));
    }

    private static long remainingMillis(long deadlineNanos) throws TimeoutException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new TimeoutException("deadline exhausted");
        }
        return Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
    }

    private record ModelOutcome(String model, Article article, RetryableException error) {
        static ModelOutcome success(String model, Article article) {
            return new ModelOutcome(model, article, null);
        }

        static ModelOutcome failure(String model, RetryableException error) {
            return new ModelOutcome(model, null, error);
        }

        boolean success() {
            return error == null;
        }
    }
}
