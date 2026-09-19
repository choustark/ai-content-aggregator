package com.choucj.aiaggregator.common.client;

import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.observability.LogSanitizer;
import com.choucj.aiaggregator.common.observability.SlowOperationRecorder;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Dependency;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Kind;
import com.choucj.aiaggregator.monitoring.DependencyMetrics.Operation;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * LangChain4j 实现 {@link LlmClient} — DeepSeek 主路径 + GLM 备用降级.
 *
 * <p><b>调用链:</b>
 * <ol>
 *   <li>默认走 {@code deepSeekChatModel} (主路径)</li>
 *   <li>DeepSeek 抛任何异常 → catch → retry 一次 {@code glmChatModel}</li>
 *   <li>GLM 也失败 → 抛 {@link RetryableException}(双链全失败)</li>
 * </ol>
 *
 * <p><b>{@link #chatWithModel(String, String)}:</b> 绕过降级链, 调用指定模型一次, 失败即抛
 * {@link RetryableException} (不再 fallback).
 *
 * <p><b>异常映射 (Story 2.3a 决策表):</b> LangChain4j 抛任何 {@link RuntimeException}
 * (含 {@code HttpTimeoutException} / 429 / 5xx / 401 / 响应畸形)统一映射为
 * {@link RetryableException}(errorCode = {@link ErrorCode#EXTERNAL_API_ERROR}).
 * 不细分 LLM 来源 / 状态码 (YAGNI — 调用方按 Retryable / NonRetryable 决策降级即可,
 * Story 5.4 多模型投票若需统计再扩展).
 *
 * <p><b>日志:</b> 不记录 prompt、响应正文；失败时记录 model、耗时、长度和异常类型, 并附上游
 * HTTP 状态与错误体摘要 ({@link #sanitizeBody} 脱敏 + 300 字符截断) — 否则上游 4xx
 * (智谱内容审核 1301 / 参数拒绝等) 只剩 {@code InvalidRequestException} 一个类型名, 无法定位根因.
 *
 * <p>架构 delta (Story 2.3a): 默认 DeepSeek + 降级 GLM 决策, Story 5.4 多模型投票时
 * 本逻辑会被覆盖.
 */
@Slf4j
@Component
public class LangChain4jLlmClient implements LlmClient {

    private static final String MODEL_DEEPSEEK = "deepseek";
    private static final String MODEL_GLM = "glm";
    private final ChatModel deepSeekChatModel;
    private final ChatModel glmChatModel;
    private final SlowOperationRecorder slowOperationRecorder;

    /**
     * 构造器注入两个 ChatModel Bean.
     *
     * @param deepSeekChatModel 主路径 DeepSeek 模型 (从 {@link com.choucj.aiaggregator.common.config.LlmConfig} 注入)
     * @param glmChatModel      备用路径 GLM 模型
     */
    public LangChain4jLlmClient(@Qualifier("deepSeekChatModel") ChatModel deepSeekChatModel,
                                @Qualifier("glmChatModel") ChatModel glmChatModel,
                                SlowOperationRecorder slowOperationRecorder) {
        this.deepSeekChatModel = deepSeekChatModel;
        this.glmChatModel = glmChatModel;
        this.slowOperationRecorder = slowOperationRecorder;
    }

    @Override
    public String chat(String prompt) {
        requireNonBlank(prompt);
        log.debug("LLM chat (default deepseek): promptLength={}", prompt.length());
        try {
            long started = System.nanoTime();
            String response = observeChat(Dependency.LLM_DEEPSEEK, () -> deepSeekChatModel.chat(prompt));
            log.info("LLM 调用成功: model=deepseek, 响应长度={}, 耗时={}ms",
                    response == null ? 0 : response.length(), elapsedMillis(started));
            return response;
        } catch (RuntimeException e) {
            log.warn("DeepSeek 调用失败, 降级 GLM: errorType={}", e.getClass().getSimpleName());
            return fallbackChat(prompt, e);
        }
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        return chatWithResult(systemPrompt, userPrompt).text();
    }

    @Override
    public ChatResult chatWithResult(String systemPrompt, String userPrompt) {
        requireNonBlank(systemPrompt);
        requireNonBlank(userPrompt);
        log.debug("LLM chat (default deepseek): systemLength={}, userLength={}",
                systemPrompt.length(), userPrompt.length());
        try {
            long started = System.nanoTime();
            String response = observeChat(Dependency.LLM_DEEPSEEK, () -> extractText(deepSeekChatModel.chat(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt))));
            log.info("LLM 调用成功: model=deepseek, 响应长度={}, 耗时={}ms",
                    response == null ? 0 : response.length(), elapsedMillis(started));
            return new ChatResult(MODEL_DEEPSEEK, response);
        } catch (RuntimeException e) {
            log.warn("DeepSeek 调用失败, 降级 GLM: errorType={}", e.getClass().getSimpleName());
            return fallbackChatResult(systemPrompt, userPrompt, e);
        }
    }

    @Override
    public String chatWithModel(String model, String prompt) {
        Objects.requireNonNull(model, "model");
        requireNonBlank(prompt);
        log.debug("LLM chatWithModel model={}, promptLength={}", model, prompt.length());
        ChatModel target = resolveModel(model);
        try {
            long started = System.nanoTime();
            String response = observeChat(dependencyFor(model), () -> target.chat(prompt));
            log.info("LLM 调用成功: model={}, 响应长度={}, 耗时={}ms",
                    model, response == null ? 0 : response.length(), elapsedMillis(started));
            return response;
        } catch (RuntimeException e) {
            log.error("LLM 调用失败 (model={}, 不降级): errorType={}, upstream={}",
                    model, e.getClass().getSimpleName(), describe(e));
            throw new RetryableException(
                    ErrorCode.EXTERNAL_API_ERROR,
                    "LLM 调用失败(model=" + model + ", errorType=" + e.getClass().getSimpleName() + ")");
        }
    }

    @Override
    public String chatWithModel(String model, String systemPrompt, String userPrompt) {
        return chatWithModelResult(model, systemPrompt, userPrompt).text();
    }

    @Override
    public ChatResult chatWithModelResult(String model, String systemPrompt, String userPrompt) {
        Objects.requireNonNull(model, "model");
        requireNonBlank(systemPrompt);
        requireNonBlank(userPrompt);
        log.debug("LLM chatWithModel model={}, systemLength={}, userLength={}",
                model, systemPrompt.length(), userPrompt.length());
        ChatModel target = resolveModel(model);
        try {
            long started = System.nanoTime();
            String response = observeChat(dependencyFor(model), () -> extractText(target.chat(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt))));
            log.info("LLM 调用成功: model={}, 响应长度={}, 耗时={}ms",
                    model, response == null ? 0 : response.length(), elapsedMillis(started));
            return new ChatResult(model, response);
        } catch (RuntimeException e) {
            log.error("LLM 调用失败 (model={}, 不降级): errorType={}, upstream={}",
                    model, e.getClass().getSimpleName(), describe(e));
            throw new RetryableException(
                    ErrorCode.EXTERNAL_API_ERROR,
                    "LLM 调用失败(model=" + model + ", errorType=" + e.getClass().getSimpleName() + ")");
        }
    }

    private String fallbackChat(String prompt, Throwable primaryCause) {
        try {
            long started = System.nanoTime();
            String response = observeChat(Dependency.LLM_GLM, () -> glmChatModel.chat(prompt));
            log.info("LLM 调用成功 (降级 GLM): 响应长度={}, 耗时={}ms", response == null ? 0 : response.length(), elapsedMillis(started));
            return response;
        } catch (RuntimeException e) {
            log.error("LLM 调用失败 (deepseek + glm 均失败): primaryType={}, fallbackType={}",
                    primaryCause.getClass().getSimpleName(), e.getClass().getSimpleName());
            throw new RetryableException(
                    ErrorCode.EXTERNAL_API_ERROR,
                    "LLM 调用失败 (deepseek + glm 均失败, primaryType="
                            + primaryCause.getClass().getSimpleName()
                            + ", fallbackType=" + e.getClass().getSimpleName() + ")");
        }
    }

    private String fallbackChat(String systemPrompt, String userPrompt, Throwable primaryCause) {
        return fallbackChatResult(systemPrompt, userPrompt, primaryCause).text();
    }

    private ChatResult fallbackChatResult(String systemPrompt, String userPrompt, Throwable primaryCause) {
        try {
            long started = System.nanoTime();
            String response = observeChat(Dependency.LLM_GLM, () -> extractText(glmChatModel.chat(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt))));
            log.info("LLM 调用成功 (降级 GLM): 响应长度={}, 耗时={}ms", response == null ? 0 : response.length(), elapsedMillis(started));
            return new ChatResult(MODEL_GLM, response);
        } catch (RuntimeException e) {
            log.error("LLM 调用失败 (deepseek + glm 均失败): primaryType={}, fallbackType={}",
                    primaryCause.getClass().getSimpleName(), e.getClass().getSimpleName());
            throw new RetryableException(
                    ErrorCode.EXTERNAL_API_ERROR,
                    "LLM 调用失败 (deepseek + glm 均失败, primaryType="
                            + primaryCause.getClass().getSimpleName()
                            + ", fallbackType=" + e.getClass().getSimpleName() + ")");
        }
    }

    private <T> T observeChat(Dependency dependency, Supplier<T> action) {
        return slowOperationRecorder.observe(
                Kind.SDK, dependency, Operation.CHAT,
                action);
    }

    private static Dependency dependencyFor(String model) {
        return MODEL_GLM.equals(model) ? Dependency.LLM_GLM : Dependency.LLM_DEEPSEEK;
    }

    private static long elapsedMillis(long started) { return (System.nanoTime() - started) / 1_000_000; }

    private ChatModel resolveModel(String model) {
        return switch (model) {
            case MODEL_DEEPSEEK -> deepSeekChatModel;
            case MODEL_GLM -> glmChatModel;
            default -> throw new IllegalArgumentException("不支持的模型: " + model + " (仅支持 deepseek / glm)");
        };
    }

    private static void requireNonBlank(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
    }

    /**
     * 提取上游 HTTP 错误摘要 — 形如 {@code HTTP 400, body={...}}.
     *
     * <p>langchain4j 1.16 异常链: 上游 4xx/5xx → {@code HttpException(statusCode, body)} (root cause)
     * → {@code ExceptionMapper} 包装为 {@code InvalidRequestException} 等具体类型.
     * 非 HTTP 异常 (超时/连接拒绝) 返回空串, 不额外记录.
     */
    static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root instanceof dev.langchain4j.exception.HttpException httpException) {
            return "HTTP " + httpException.statusCode() + ", body=" + sanitizeBody(httpException.getMessage());
        }
        return "";
    }

    /**
     * 错误体脱敏 + 截断 — api-key / Bearer token 模式替换为掩码, 300 字符截断.
     */
    static String sanitizeBody(String body) {
        return LogSanitizer.sanitizeBody(body);
    }

    private static String extractText(ChatResponse response) {
        if (response == null || response.aiMessage() == null) {
            return "";
        }
        return response.aiMessage().text();
    }
}
