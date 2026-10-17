package com.choucj.aiaggregator.source.twitter.api;

/** 不含正文、完整 URL、令牌或外部响应体的安全摘要。 */
public record SpecifiedXContentResponse(String tweetId, int discovered, int filtered,
                                        int archived, int failed, boolean degraded,
                                        long elapsedMs) {
}
