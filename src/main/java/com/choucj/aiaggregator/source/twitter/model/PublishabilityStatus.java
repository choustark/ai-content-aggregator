package com.choucj.aiaggregator.source.twitter.model;

/**
 * 可发布性状态，标识推文整体或单个媒体项能否进入微信草稿发布链路。
 *
 * <p>引用源: Story 7.1(创建，预留字段) / Story 7.5(Publishability Gate 计算填值) /
 * epics-x-media-fidelity Story 7.5 AC。
 */
public enum PublishabilityStatus {
    /** 未知（默认值，尚未由 publishability gate 评估）。 */
    UNKNOWN,
    /** 可发布，无合规/访问风险。 */
    PUBLISHABLE,
    /** 降级（单个媒体失败，但文本草稿仍可发布，附降级原因）。 */
    DEGRADED,
    /** 阻止发布（源文本不可用/内容删除/访问受限/合规风险，需人工确认）。 */
    BLOCKED
}
