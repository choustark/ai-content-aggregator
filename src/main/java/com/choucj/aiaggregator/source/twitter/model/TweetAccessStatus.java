package com.choucj.aiaggregator.source.twitter.model;

/**
 * 推文源访问状态。
 *
 * <p>仅表达“是否受限”的结构化事实，不承载内容类型语义。
 */
public enum TweetAccessStatus {
    ACCESSIBLE,
    RESTRICTED,
    DELETED,
    WITHHELD,
    UNKNOWN
}
