package com.choucj.aiaggregator.source.twitter.discovery;

import com.choucj.aiaggregator.source.twitter.client.RSSHubClient;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RSSHub-backed Twitter/X 发现实现.
 */
@Component
@RequiredArgsConstructor
public class RSSHubDiscoveryClient implements NamedTwitterDiscoveryProvider {

    private final RSSHubClient rssHubClient;

    @Override
    public String providerName() {
        return "rsshub";
    }

    @Override
    public List<Tweet> discoverTweets(String username) {
        return rssHubClient.discoverTweets(username);
    }
}
