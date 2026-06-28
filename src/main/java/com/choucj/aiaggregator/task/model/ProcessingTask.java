package com.choucj.aiaggregator.task.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 处理任务模型 — 任务队列的核心数据结构.
 *
 * <p>引用源:
 * <ul>
 *   <li>Story 1.6 — {@code TaskQueue} CRUD 的核心实体(序列化到 Redis)</li>
 *   <li>Story 2.6 — Pipeline 入队 / 出队(支持崩溃可恢复)</li>
 * </ul>
 *
 * <p>嵌套枚举设计决策:{@link TaskType} / {@link TaskStatus} 仅在 ProcessingTask 上下文使用,
 * 放在外部会污染 {@code task.model} 包, 因此选择嵌套。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessingTask {

    /** 任务 ID(UUID). */
    private String id;

    /** 任务类型. */
    private TaskType type;

    /** JSON 序列化的任务参数(如 {@code {"tweetId":"123"}} 或 {@code {"repoFullName":"owner/repo"}}). */
    private String payload;

    /** 任务状态. */
    private TaskStatus status;

    /** 创建时间. */
    private LocalDateTime createdAt;

    /** 最后更新时间. */
    private LocalDateTime updatedAt;

    /** 重试次数(架构文档约束: 最大 3 次). */
    private int retryCount;

    /** 失败时的错误信息(成功时为 {@code null}). */
    private String errorMessage;

    /**
     * 任务类型枚举.
     *
     * <p>当前 Epic 2 / Epic 4 各贡献一个值. 后续若引入新数据源(如知乎 / B 站), 在此扩展。
     */
    public enum TaskType {
        /** Twitter 抓取 + 改写任务. */
        TWITTER,

        /** GitHub 仓库抓取 + 改写任务. */
        GITHUB
    }

    /**
     * 任务状态枚举.
     *
     * <p>状态机:{@link #PENDING} → {@link #PROCESSING} → {@link #COMPLETED} / {@link #FAILED}.
     * {@link #FAILED} 在重试次数耗尽前会回退到 {@link #PENDING}.
     */
    public enum TaskStatus {
        /** 待处理(在 task:queue 队列中等待消费). */
        PENDING,

        /** 处理中(已移入 task:processing 队列, 防止重复消费). */
        PROCESSING,

        /** 已完成. */
        COMPLETED,

        /** 失败(超过最大重试次数后终态). */
        FAILED
    }
}
