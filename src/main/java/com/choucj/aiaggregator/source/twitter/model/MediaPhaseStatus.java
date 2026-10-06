package com.choucj.aiaggregator.source.twitter.model;

/** 媒体阶段的有限状态集合，用于让 sidecar 成为可恢复的权威状态。 */
public enum MediaPhaseStatus {
    NOT_STARTED,
    IN_PROGRESS,
    SUCCEEDED,
    FAILED_TERMINAL,
    RETRY_SCHEDULED,
    ENVIRONMENT_BLOCKED,
    DEFERRED
}
