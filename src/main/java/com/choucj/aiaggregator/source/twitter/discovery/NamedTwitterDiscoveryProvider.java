package com.choucj.aiaggregator.source.twitter.discovery;

import com.choucj.aiaggregator.source.twitter.model.Tweet;

import java.util.List;

/**
 * Named Twitter/X discovery provider registered behind the configurable provider chain.
 */
public interface NamedTwitterDiscoveryProvider {

    /**
     * Stable configuration key used by {@code twitter.discovery-providers}.
     *
     * @return provider key such as {@code scraper}, {@code apify}, or {@code rsshub}
     */
    String providerName();

    /**
     * Discover tweets for one X account handle.
     *
     * @param username X account handle without {@code @}
     * @return discovered tweets, possibly empty
     */
    List<Tweet> discoverTweets(String username);
}
