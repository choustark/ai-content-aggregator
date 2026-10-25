package com.choucj.aiaggregator.common.exception;

import com.choucj.aiaggregator.common.model.ErrorCode;

/**
 * 媒体交付可重试异常(Story 10.8) — 携带失败文章 ID 与收敛上下文的 {@link RetryableException}.
 *
 * <p><b>用途:</b> 微信正文图片准备发生可重试失败(如 {@code errcode=45009} 限流)且<b>草稿未创建</b>时,
 * 生成器抛出本异常走既有任务级延迟重试(10.5 状态机), <b>不立即触发</b> 10.7 四层收敛 —
 * 仅当 TaskQueue 判定重试耗尽落死信时, 调度器钩子凭 {@link #getArticleId()} 从任务 state
 * 定位失败文章并触发 {@code DeliveryFailureCoordinator} 收敛(AC4: 配置上限内重试、耗尽才终态)。
 *
 * <p><b>收敛上下文(Story 10.17):</b> {@code articleId}/{@code canonicalArchiveDate}/
 * {@code failedStage} 三字段由调度器在 RETRY_SCHEDULE 脚本内<b>与状态迁移同一 HSET 原子写入</b>
 * 任务 state Hash — 取代 10.8 的「先权威迁移、后旁路 HSET」两步写, 消除中间崩溃产生的
 * 「已 RETRY_SCHEDULED/DEAD_LETTER 但无上下文」孤儿态。
 *
 * <p>引用源: Story 10.8 spec(AC4 + 调度器耗尽收敛钩子) / Story 10.5(重试状态机) /
 * Story 10.17 spec(AC1 上下文原子化)。
 */
public class ArticleDeliveryRetryableException extends RetryableException {

    /** 失败文章 ID(确定性 {@code tw-{tweetId}} 约定), 供调度器耗尽钩子定位 sidecar 与文章快照. */
    private final String articleId;

    /**
     * canonical archive date(ISO-8601 {@code yyyy-MM-dd}, 可为 null — 解析失败时留空,
     * 由调度器按上下文缺失语义告警), 供耗尽收敛定位归档目录.
     */
    private final String canonicalArchiveDate;

    /**
     * 失败阶段闭式标识(如 {@code wechat_prepare}; 可为 null), 供耗尽收敛选择收敛路径.
     */
    private final String failedStage;

    /**
     * 构造媒体交付可重试异常(无根因链路, 默认 {@link ErrorCode#RETRYABLE_ERROR}).
     *
     * @param articleId 失败文章 ID
     * @param message   异常消息
     */
    public ArticleDeliveryRetryableException(String articleId, String message) {
        this(articleId, null, null, ErrorCode.RETRYABLE_ERROR, message, null);
    }

    /**
     * 构造媒体交付可重试异常(默认 {@link ErrorCode#RETRYABLE_ERROR}).
     *
     * @param articleId 失败文章 ID(不得为空 — 空值会让耗尽钩子失去定位依据)
     * @param message   异常消息(只含 taskId/内容 ID 等标识符, 遵守 N4 脱敏)
     * @param cause     原始失败(保留分类链路)
     */
    public ArticleDeliveryRetryableException(String articleId, String message, Throwable cause) {
        this(articleId, null, null, ErrorCode.RETRYABLE_ERROR, message, cause);
    }

    /**
     * 构造媒体交付可重试异常(自定义 errorCode).
     *
     * @param articleId 失败文章 ID
     * @param errorCode 错误分类(如 {@link ErrorCode#WECHAT_RATE_LIMITED})
     * @param message   异常消息
     */
    public ArticleDeliveryRetryableException(String articleId, ErrorCode errorCode, String message) {
        this(articleId, null, null, errorCode, message, null);
    }

    /**
     * 构造媒体交付可重试异常(完整收敛上下文, Story 10.17).
     *
     * @param articleId           失败文章 ID(不得为空)
     * @param canonicalArchiveDate canonical archive date(ISO-8601 {@code yyyy-MM-dd}; 可为 null)
     * @param failedStage         失败阶段闭式标识(如 {@code wechat_prepare}; 可为 null)
     * @param errorCode           错误分类
     * @param message             异常消息(只含标识符与安全摘要, 遵守 N4 脱敏)
     * @param cause               原始失败(保留分类链路)
     */
    public ArticleDeliveryRetryableException(String articleId, String canonicalArchiveDate,
                                             String failedStage, ErrorCode errorCode,
                                             String message, Throwable cause) {
        super(errorCode, message, cause);
        this.articleId = articleId;
        this.canonicalArchiveDate = canonicalArchiveDate;
        this.failedStage = failedStage;
    }

    /**
     * 构造媒体交付可重试异常(完整收敛上下文, 无根因链路, Story 10.17).
     *
     * @param articleId           失败文章 ID(不得为空)
     * @param canonicalArchiveDate canonical archive date(ISO-8601 {@code yyyy-MM-dd}; 可为 null)
     * @param failedStage         失败阶段闭式标识(如 {@code wechat_prepare}; 可为 null)
     * @param errorCode           错误分类
     * @param message             异常消息(只含标识符与安全摘要, 遵守 N4 脱敏)
     */
    public ArticleDeliveryRetryableException(String articleId, String canonicalArchiveDate,
                                             String failedStage, ErrorCode errorCode,
                                             String message) {
        this(articleId, canonicalArchiveDate, failedStage, errorCode, message, null);
    }

    /** 失败文章 ID, 供调度器耗尽收敛钩子定位. */
    public String getArticleId() {
        return articleId;
    }

    /** canonical archive date(ISO-8601 {@code yyyy-MM-dd}), 可为 null(Story 10.17). */
    public String getCanonicalArchiveDate() {
        return canonicalArchiveDate;
    }

    /** 失败阶段闭式标识(如 {@code wechat_prepare}), 可为 null(Story 10.17). */
    public String getFailedStage() {
        return failedStage;
    }
}
