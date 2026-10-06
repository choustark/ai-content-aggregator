package com.choucj.aiaggregator.common.exception;

import com.choucj.aiaggregator.common.model.ErrorCode;

/**
 * 媒体交付可重试异常(Story 10.8) — 携带失败文章 ID 的 {@link RetryableException}.
 *
 * <p><b>用途:</b> 微信正文图片准备发生可重试失败(如 {@code errcode=45009} 限流)且<b>草稿未创建</b>时,
 * 生成器抛出本异常走既有任务级延迟重试(10.5 状态机), <b>不立即触发</b> 10.7 四层收敛 —
 * 仅当 TaskQueue 判定重试耗尽落死信时, 调度器钩子凭 {@link #getArticleId()} 从任务 state
 * 定位失败文章并触发 {@code DeliveryFailureCoordinator} 收敛(AC4: 配置上限内重试、耗尽才终态)。
 *
 * <p>{@code articleId} 由调度器在 RETRY_SCHEDULED 时写入任务 state Hash 的 {@code articleId}
 * 字段(先权威迁移、后旁路上下文), 使"state 携带 articleId"在耗尽时刻成立。
 *
 * <p>引用源: Story 10.8 spec(AC4 + 调度器耗尽收敛钩子) / Story 10.5(重试状态机, 未改动)。
 */
public class ArticleDeliveryRetryableException extends RetryableException {

    /** 失败文章 ID(确定性 {@code tw-{tweetId}} 约定), 供调度器耗尽钩子定位 sidecar 与文章快照. */
    private final String articleId;

    /**
     * 构造媒体交付可重试异常(无根因链路, 默认 {@link ErrorCode#RETRYABLE_ERROR}).
     *
     * @param articleId 失败文章 ID
     * @param message   异常消息
     */
    public ArticleDeliveryRetryableException(String articleId, String message) {
        this(articleId, ErrorCode.RETRYABLE_ERROR, message, null);
    }

    /**
     * 构造媒体交付可重试异常(默认 {@link ErrorCode#RETRYABLE_ERROR}).
     *
     * @param articleId 失败文章 ID(不得为空 — 空值会让耗尽钩子失去定位依据)
     * @param message   异常消息(只含 taskId/内容 ID 等标识符, 遵守 N4 脱敏)
     * @param cause     原始失败(保留分类链路)
     */
    public ArticleDeliveryRetryableException(String articleId, String message, Throwable cause) {
        this(articleId, ErrorCode.RETRYABLE_ERROR, message, cause);
    }

    /**
     * 构造媒体交付可重试异常(自定义 errorCode).
     *
     * @param articleId 失败文章 ID
     * @param errorCode 错误分类(如 {@link ErrorCode#WECHAT_RATE_LIMITED})
     * @param message   异常消息
     */
    public ArticleDeliveryRetryableException(String articleId, ErrorCode errorCode, String message) {
        this(articleId, errorCode, message, null);
    }

    private ArticleDeliveryRetryableException(String articleId, ErrorCode errorCode,
                                              String message, Throwable cause) {
        super(errorCode, message, cause);
        this.articleId = articleId;
    }

    /** 失败文章 ID, 供调度器耗尽收敛钩子定位. */
    public String getArticleId() {
        return articleId;
    }
}
