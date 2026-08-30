package com.choucj.aiaggregator.processor;

import com.choucj.aiaggregator.common.exception.NonRetryableException;

/**
 * 推文级 publishability=BLOCKED 阻断信号 (Story 9.1 Task 2, AC 8).
 *
 * <p>是 {@link NonRetryableException} 的语义子类 — 保持三异常体系不变
 * (调用方按 NonRetryable 处理即可), 额外携带 "被 gate 阻断" 类型信息,
 * 供 {@code TwitterProcessor} 把该失败归类到 {@code rewriteWithMediaBlocked} 计数
 * (AC 10), 与其他 NonRetryable 失败 (配置缺失等) 区分。
 *
 * <p>引用源: Story 9.1 AC 8/10 / AD-8 (推文级 BLOCKED 阻断整篇草稿)。
 */
public class TweetPublishabilityBlockedException extends NonRetryableException {

    public TweetPublishabilityBlockedException(String message) {
        super(message);
    }
}
