package com.choucj.aiaggregator.source.twitter.model;

/**
 * 推文访问受限的结构化原因。
 *
 * <p>用于驱动发布性判定与 reviewer-readable 文案，不再依赖自由文本协议。
 */
public enum TweetRestrictionReason {
    NONE,
    ACCESS_RESTRICTED,
    DELETED_BY_AUTHOR,
    WITHHELD_BY_PLATFORM,
    LEGACY_OTHER
}
