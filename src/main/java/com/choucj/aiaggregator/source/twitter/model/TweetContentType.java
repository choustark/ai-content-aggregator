package com.choucj.aiaggregator.source.twitter.model;

/**
 * 推文内容类型。
 *
 * <p>仅表达内容形态，不直接驱动 publishability BLOCKED。
 */
public enum TweetContentType {
    POST,
    ARTICLE,
    THREAD,
    UNKNOWN
}
