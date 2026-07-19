package com.choucj.aiaggregator.source.twitter.discovery;

import com.choucj.aiaggregator.source.twitter.client.RSSHubClient;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RSSHub-backed Twitter/X 发现实现.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "twitter", name = "discovery-provider", havingValue = "rsshub", matchIfMissing = true)
public class RSSHubDiscoveryClient implements TwitterDiscoveryClient {

    private final RSSHubClient rssHubClient;

    @Override
    public List<Tweet> discoverTweets(String username) {
        return rssHubClient.discoverTweets(username);
    }
}
