package com.choucj.aiaggregator.common.client;

/**
 * LLM 调用门面 — 统一屏蔽 DeepSeek / GLM 等多模型差异.
 *
 * <p><b>调用契约:</b>
 * <ul>
 *   <li>{@link #chat(String)} 默认走主路径 (DeepSeek), 失败时内部降级到备用路径 (GLM), 双链全失败抛
 *       {@code RetryableException(ErrorCode.EXTERNAL_API_ERROR)}</li>
 *   <li>{@link #chat(String, String)} 拼 System + User message 后调用, 用于需要角色设定的 prompt
 *       (如改写 / 评分)</li>
 *   <li>{@link #chatWithModel(String, String)} 显式指定模型 ({@code "deepseek"} / {@code "glm"}),
 *       绕过降级链. Story 5.4 多模型投票时使用</li>
 * </ul>
 *
 * <p><b>设计决策 (Story 2.3a):</b>
 * <ul>
 *   <li>不暴露 timeout / maxTokens / temperature 参数 (YAGNI — 用 LangChain4j 默认值,
 *       真到生产瓶颈再扩 {@code LlmProperties})</li>
 *   <li>不做 streaming / function calling (架构未规划)</li>
 *   <li>不在接口层缓存 prompt / 响应 (调用方决定, InnovationFilter 当前无缓存)</li>
 * </ul>
 *
 * <p>引用源:Story 2.3a (LlmClient 抽象) / Story 2.3b (InnovationFilter 注入) /
 * Story 2.4 (ContentRewriter 注入) / Story 5.4 (多模型投票).
 */
public interface LlmClient {

    /**
     * 同步调用 LLM (默认 DeepSeek 主路径, 失败降级 GLM).
     *
     * @param prompt 用户 prompt, 非空
     * @return 模型生成文本 (trim 后非空)
     * @throws com.choucj.aiaggregator.common.exception.RetryableException 双链全失败时抛出
     * @throws IllegalArgumentException                                     prompt 为 null / blank
     */
    String chat(String prompt);

    /**
     * 同步调用 LLM, 拼 System + User message.
     *
     * @param systemPrompt 角色 / 指令 prompt (如 "你是一个 AI 内容评分助手")
     * @param userPrompt   实际内容 prompt
     * @return 模型生成文本
     * @throws com.choucj.aiaggregator.common.exception.RetryableException 双链全失败时抛出
     */
    String chat(String systemPrompt, String userPrompt);

    /**
     * 显式指定模型调用 (绕过降级链).
     *
     * @param model  模型标识 ({@code "deepseek"} / {@code "glm"})
     * @param prompt prompt 内容
     * @return 模型生成文本
     * @throws com.choucj.aiaggregator.common.exception.RetryableException 指定模型调用失败时抛出 (不降级)
     * @throws IllegalArgumentException                                   model 不支持 / prompt 为空
     */
    String chatWithModel(String model, String prompt);
}
