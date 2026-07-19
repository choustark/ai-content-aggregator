package com.choucj.aiaggregator.source.twitter.discovery;

import com.choucj.aiaggregator.source.twitter.model.Tweet;

import java.util.List;

/**
 * Twitter/X 推文发现入口.
 *
 * <p>只负责按账号发现候选 Tweet 基础字段,补全仍由 TwitterSource 编排 twscrape/FxTwitter 完成.
 */
public interface TwitterDiscoveryClient {

    /**
     * 发现指定账号的最新推文.
     *
     * @param username Twitter/X handle, 不带 @
     * @return 候选 Tweet 列表
     */
    List<Tweet> discoverTweets(String username);
}
