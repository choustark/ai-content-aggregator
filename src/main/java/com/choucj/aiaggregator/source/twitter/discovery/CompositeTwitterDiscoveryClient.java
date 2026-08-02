package com.choucj.aiaggregator.source.twitter.discovery;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.source.twitter.config.TwitterProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Twitter/X discovery provider chain.
 *
 * <p>Provider beans are all registered and this class is the single {@link TwitterDiscoveryClient}
 * seen by {@code TwitterSource}. Runtime order is configured through {@code twitter.discovery-providers};
 * the legacy {@code twitter.discovery-provider} property remains as a single-provider fallback.
 */
@Slf4j
@Component
public class CompositeTwitterDiscoveryClient implements TwitterDiscoveryClient {

    private final TwitterProperties properties;
    private final Map<String, NamedTwitterDiscoveryProvider> providersByName;

    public CompositeTwitterDiscoveryClient(TwitterProperties properties,
                                           List<NamedTwitterDiscoveryProvider> providers) {
        this.properties = properties;
        this.providersByName = new LinkedHashMap<>();
        for (NamedTwitterDiscoveryProvider provider : providers) {
            this.providersByName.put(provider.providerName(), provider);
        }
    }

    @Override
    public List<Tweet> discoverTweets(String username) {
        List<String> providerNames = properties.effectiveDiscoveryProviders();
        if (providerNames.isEmpty()) {
            log.warn("Twitter discovery provider 链为空, 跳过抓取: username={}", username);
            return List.of();
        }

        RuntimeException firstFailure = null;
        boolean attempted = false;
        for (String providerName : providerNames) {
            NamedTwitterDiscoveryProvider provider = providersByName.get(providerName);
            if (provider == null) {
                log.warn("Twitter discovery provider 未注册, 跳过: provider={}, username={}",
                        providerName, username);
                continue;
            }
            attempted = true;
            try {
                List<Tweet> tweets = provider.discoverTweets(username);
                if (!tweets.isEmpty()) {
                    log.info("Twitter discovery provider 命中: provider={}, username={}, 条数={}",
                            providerName, username, tweets.size());
                    return tweets;
                }
                log.info("Twitter discovery provider 返回空, 尝试下一 provider: provider={}, username={}",
                        providerName, username);
            } catch (RetryableException | NonRetryableException e) {
                if (firstFailure == null) {
                    firstFailure = e;
                }
                log.warn("Twitter discovery provider 失败, 尝试下一 provider: provider={}, username={}, error={}",
                        providerName, username, e.getMessage());
            }
        }

        if (attempted) {
            if (firstFailure != null) {
                throw firstFailure;
            }
            return List.of();
        }
        throw new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR,
                "未找到可用 Twitter discovery provider: configured=" + providerNames);
    }
}
