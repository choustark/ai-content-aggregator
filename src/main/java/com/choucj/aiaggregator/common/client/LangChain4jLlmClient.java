package com.choucj.aiaggregator.common.client;

import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Objects;

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
 * <p><b>日志:</b>
 * <ul>
 *   <li>DEBUG 级别记录 prompt 前 200 字符 (避免 token 爆日志)</li>
 *   <li>INFO 级别记录调用耗时 + model + 响应长度</li>
 *   <li>WARN 级别记录 DeepSeek 失败降级 GLM (含异常 message 截断)</li>
 *   <li>ERROR 级别记录双链全失败 (含完整异常堆栈)</li>
 * </ul>
 *
 * <p>架构 delta (Story 2.3a): 默认 DeepSeek + 降级 GLM 决策, Story 5.4 多模型投票时
 * 本逻辑会被覆盖.
 */
@Slf4j
@Component
public class LangChain4jLlmClient implements LlmClient {

    private static final String MODEL_DEEPSEEK = "deepseek";
    private static final String MODEL_GLM = "glm";
    private static final int PROMPT_LOG_LIMIT = 200;

    private final ChatModel deepSeekChatModel;
    private final ChatModel glmChatModel;

    /**
     * 构造器注入两个 ChatModel Bean.
     *
     * @param deepSeekChatModel 主路径 DeepSeek 模型 (从 {@link com.choucj.aiaggregator.common.config.LlmConfig} 注入)
     * @param glmChatModel      备用路径 GLM 模型
     */
    public LangChain4jLlmClient(@Qualifier("deepSeekChatModel") ChatModel deepSeekChatModel,
                                @Qualifier("glmChatModel") ChatModel glmChatModel) {
        this.deepSeekChatModel = deepSeekChatModel;
        this.glmChatModel = glmChatModel;
    }

    @Override
    public String chat(String prompt) {
        requireNonBlank(prompt);
        log.debug("LLM chat (default deepseek) prompt: {}", truncate(prompt));
        long start = System.currentTimeMillis();
        try {
            String response = deepSeekChatModel.chat(prompt);
            log.info("LLM 调用成功: model=deepseek, 耗时={}ms, 响应长度={}",
                    System.currentTimeMillis() - start, response == null ? 0 : response.length());
            return response;
        } catch (RuntimeException e) {
            log.warn("DeepSeek 调用失败, 降级 GLM: {}", truncate(e.getMessage()));
            return fallbackChat(prompt, start, e);
        }
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        requireNonBlank(systemPrompt);
        requireNonBlank(userPrompt);
        log.debug("LLM chat (default deepseek) system={}, user={}",
                truncate(systemPrompt), truncate(userPrompt));
        long start = System.currentTimeMillis();
        try {
            String response = extractText(deepSeekChatModel.chat(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt)));
            log.info("LLM 调用成功: model=deepseek, 耗时={}ms, 响应长度={}",
                    System.currentTimeMillis() - start, response == null ? 0 : response.length());
            return response;
        } catch (RuntimeException e) {
            log.warn("DeepSeek 调用失败, 降级 GLM: {}", truncate(e.getMessage()));
            return fallbackChat(systemPrompt, userPrompt, start, e);
        }
    }

    @Override
    public String chatWithModel(String model, String prompt) {
        Objects.requireNonNull(model, "model");
        requireNonBlank(prompt);
        log.debug("LLM chatWithModel model={}, prompt={}", model, truncate(prompt));
        long start = System.currentTimeMillis();
        ChatModel target = resolveModel(model);
        try {
            String response = target.chat(prompt);
            log.info("LLM 调用成功: model={}, 耗时={}ms, 响应长度={}",
                    model, System.currentTimeMillis() - start, response == null ? 0 : response.length());
            return response;
        } catch (RuntimeException e) {
            log.error("LLM 调用失败 (model={}, 不降级): {}", model, e.getMessage());
            throw new RetryableException(
                    ErrorCode.EXTERNAL_API_ERROR,
                    "LLM 调用失败(model=" + model + "): " + truncate(e.getMessage()),
                    e);
        }
    }

    private String fallbackChat(String prompt, long startMs, Throwable primaryCause) {
        long fallbackStart = System.currentTimeMillis();
        try {
            String response = glmChatModel.chat(prompt);
            log.info("LLM 调用成功 (降级 GLM): 耗时={}ms (主路径失败前 {}ms), 响应长度={}",
                    System.currentTimeMillis() - fallbackStart,
                    fallbackStart - startMs,
                    response == null ? 0 : response.length());
            return response;
        } catch (RuntimeException e) {
            log.error("LLM 调用失败 (deepseek + glm 均失败): primary={}, fallback={}",
                    primaryCause.getMessage(), e.getMessage());
            throw new RetryableException(
                    ErrorCode.EXTERNAL_API_ERROR,
                    "LLM 调用失败 (deepseek + glm 均失败): " + truncate(e.getMessage()),
                    e);
        }
    }

    private String fallbackChat(String systemPrompt, String userPrompt, long startMs, Throwable primaryCause) {
        long fallbackStart = System.currentTimeMillis();
        try {
            String response = extractText(glmChatModel.chat(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt)));
            log.info("LLM 调用成功 (降级 GLM): 耗时={}ms (主路径失败前 {}ms), 响应长度={}",
                    System.currentTimeMillis() - fallbackStart,
                    fallbackStart - startMs,
                    response == null ? 0 : response.length());
            return response;
        } catch (RuntimeException e) {
            log.error("LLM 调用失败 (deepseek + glm 均失败): primary={}, fallback={}",
                    primaryCause.getMessage(), e.getMessage());
            throw new RetryableException(
                    ErrorCode.EXTERNAL_API_ERROR,
                    "LLM 调用失败 (deepseek + glm 均失败): " + truncate(e.getMessage()),
                    e);
        }
    }

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

    private static String truncate(String value) {
        if (value == null) {
            return "<null>";
        }
        return value.length() <= PROMPT_LOG_LIMIT ? value : value.substring(0, PROMPT_LOG_LIMIT) + "...";
    }

    private static String extractText(ChatResponse response) {
        if (response == null || response.aiMessage() == null) {
            return "";
        }
        return response.aiMessage().text();
    }
}
