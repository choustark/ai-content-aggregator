package com.choucj.aiaggregator.task.queue;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * Story 10.17: 可重试交付失败的任务收敛上下文 — 作为 RETRY_SCHEDULE 脚本新增 ARGV,
 * 与状态迁移(retry 排期 / 耗尽死信)在<b>同一 Lua 原子操作</b>内写入任务 state Hash,
 * 消除「已终态化但无上下文」的中间崩溃孤儿态(AC1)。
 *
 * <p>三字段语义:
 * <ul>
 *   <li>{@code articleId} — 失败文章 ID(确定性 {@code tw-{tweetId}} 约定),
 *       耗尽收敛钩子读取 state 该字段定位 sidecar 与文章快照;上下文存在的必要条件。</li>
 *   <li>{@code canonicalArchiveDate} — canonical archive date(ISO-8601 {@code yyyy-MM-dd},
 *       可为 null — 生成器侧解析失败时留空, 由调度器按缺失语义告警);
 *       收敛钩子当前经 articleId 推导归档目录, 该字段随 state 持久化供后续阶段扩展。</li>
 *   <li>{@code failedStage} — 失败阶段闭式标识({@link TaskFailureStage});当前唯一登记阶段
 *       (wechat_prepare)与收敛钩子路径直接对应, 字段为未来新失败阶段扩展预留。</li>
 * </ul>
 *
 * <p><b>校验纪律(写前拒绝零写入, 镜像 10.5 先例):</b> articleId 非空、date 为可解析的
 * ISO-8601 格式、stage 闭式枚举 — 全部在 Java 侧工厂完成, 脚本内不解析日期
 * (保持脚本纯键值搬运)。非法上下文抛 {@link NonRetryableException} 而非静默降级,
 * 防止上下文损坏被静默吞掉后产生无收敛依据的死信。
 *
 * <p>源级任务(如 fetch 失败)无业务上下文属预期, 使用 {@link #none()}。
 */
public record TaskFailureContext(String articleId, String canonicalArchiveDate, String failedStage) {

    /**
     * 紧凑构造器兜底校验(Story 10.17 review): 直接 {@code new} 绕过 {@link #of} 工厂
     * 同样被拒绝(写前拒绝零写入), 防止非法值直入 RETRY_SCHEDULE 脚本 ARGV;
     * 仅三字段全 null 的 {@link #EMPTY} 单例豁免。
     */
    public TaskFailureContext {
        if (articleId != null || canonicalArchiveDate != null || failedStage != null) {
            if (articleId == null || articleId.isBlank()) {
                throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                        "TaskFailureContext 非法: articleId 不能为空白");
            }
            if (failedStage == null || failedStage.isBlank()) {
                throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                        "TaskFailureContext 非法: failedStage 不能为空白");
            }
            TaskFailureStage.ofTagValue(failedStage);
            if (canonicalArchiveDate != null) {
                try {
                    LocalDate.parse(canonicalArchiveDate);
                } catch (DateTimeParseException e) {
                    throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                            "TaskFailureContext 非法: canonicalArchiveDate 必须是 ISO-8601 yyyy-MM-dd");
                }
            }
        }
    }

    private static final TaskFailureContext EMPTY = new TaskFailureContext(null, null, null);

    /** 无业务上下文(源级任务, 如 fetch 失败)— RETRY_SCHEDULE 脚本不写上下文字段. */
    public static TaskFailureContext none() {
        return EMPTY;
    }

    /**
     * 构造完整收敛上下文(校验失败抛 {@link NonRetryableException}, 调用点 bug fail-closed).
     *
     * @param articleId           失败文章 ID(非空白必填)
     * @param canonicalArchiveDate ISO-8601 {@code yyyy-MM-dd}(可 null/空白)
     * @param failedStage          失败阶段({@link TaskFailureStage#tagValue()}, 非空白必填)
     */
    public static TaskFailureContext of(String articleId, String canonicalArchiveDate, String failedStage) {
        String date = canonicalArchiveDate == null || canonicalArchiveDate.isBlank()
                ? null : canonicalArchiveDate.trim();
        return new TaskFailureContext(articleId, date, failedStage);
    }

    /** 上下文是否携带业务收敛依据(决定 Lua 是否写上下文字段). */
    public boolean isEmpty() {
        return articleId == null || articleId.isBlank();
    }

    /**
     * 失败阶段闭式标识(Story 10.17) — 防止自由文本阶段名进入任务 state
     * 造成收敛路径漂移; 新失败阶段在此显式登记.
     */
    public enum TaskFailureStage {
        /** 微信正文媒体准备阶段(生成器 prepareMedia 可重试失败). */
        WECHAT_PREPARE("wechat_prepare");

        private final String tagValue;

        TaskFailureStage(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }

        /** 按标签值反查, 未登记值抛 {@link NonRetryableException}(fail-closed). */
        public static TaskFailureStage ofTagValue(String tagValue) {
            for (TaskFailureStage stage : values()) {
                if (stage.tagValue.equals(tagValue)) {
                    return stage;
                }
            }
            throw new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                    "TaskFailureContext 非法: failedStage 未登记(闭式枚举): " + tagValue);
        }
    }
}
