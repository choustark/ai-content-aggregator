package com.choucj.aiaggregator.source.twitter;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.repository.RedisRepository;
import com.choucj.aiaggregator.common.util.RedisKeys;
import com.choucj.aiaggregator.source.twitter.client.FxTwitterClient;
import com.choucj.aiaggregator.source.twitter.client.TwscrapeClient;
import com.choucj.aiaggregator.source.twitter.config.FxTwitterProperties;
import com.choucj.aiaggregator.source.twitter.config.TwitterProperties;
import com.choucj.aiaggregator.source.twitter.config.TwscrapeProperties;
import com.choucj.aiaggregator.source.twitter.discovery.TwitterDiscoveryClient;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 2.2a/2.2b {@link TwitterSource} 单测 — 覆盖编排、双链降级、缓存命中、失败跳过.
 *
 * <p>Story 2.2b 在 2.2a 单链(FxTwitter)基础上引入 twscrape 主路径 + FxTwitter 降级备路径,
 * 由 {@code twscrapeProperties.isEnabled()} 控制路径切换. 测试分两组:
 * <ul>
 *   <li>2.2a 兼容(twscrape.enabled=false): 仅走 FxTwitter, 等价单链行为</li>
 *   <li>2.2b 双链(twscrape.enabled=true): twscrape 主 → FxTwitter 备, 含命中缓存短路</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TwitterSourceTest {

    @Mock
    private TwitterDiscoveryClient discoveryClient;
    @Mock
    private FxTwitterClient fxTwitterClient;
    @Mock
    private TwscrapeClient twscrapeClient;
    @Mock
    private RedisRepository redisRepository;

    private TwitterSource source;
    private TwitterProperties twitterProperties;
    private FxTwitterProperties fxTwitterProperties;
    private TwscrapeProperties twscrapeProperties;

    @BeforeEach
    void setUp() {
        twitterProperties = new TwitterProperties();
        twitterProperties.setAccounts(List.of("karpathy"));
        fxTwitterProperties = new FxTwitterProperties();
        fxTwitterProperties.setEnabled(true);
        fxTwitterProperties.setInstance("https://api.fxtwitter.com");
        // 默认关闭 twscrape — 让 2.2a 兼容测试无须额外配置即可跑通
        twscrapeProperties = new TwscrapeProperties();
        twscrapeProperties.setEnabled(false);
        source = new TwitterSource(discoveryClient, fxTwitterClient, twscrapeClient, redisRepository,
                twitterProperties, fxTwitterProperties, twscrapeProperties);
    }

    // ===================== Story 2.2a 单链行为(twscrape 禁用) =====================

    @Test
    void shouldReturnEmptyWhenAccountsEmpty() {
        twitterProperties.setAccounts(List.of());

        assertThat(source.fetch()).isEmpty();
        verify(discoveryClient, never()).discoverTweets(anyString());
    }

    @Test
    void shouldMergeFxTwitterFieldsOnHappyPath() {
        Tweet partial = baseTweet("1", "summary-rsshub");
        when(discoveryClient.discoverTweets("karpathy")).thenReturn(List.of(partial));
        when(redisRepository.getObject(eq(RedisKeys.tweet("1")), eq(Tweet.class))).thenReturn(null);
        Tweet enriched = Tweet.builder()
                .id("1").content("full content").replyCount(5).retweetCount(10).likeCount(100)
                .imageUrls(List.of("https://img.example/1.jpg")).build();
        when(fxTwitterClient.fetchTweetDetail("1")).thenReturn(enriched);

        List<Tweet> result = source.fetch();

        assertThat(result).hasSize(1);
        Tweet merged = result.get(0);
        assertThat(merged.getId()).isEqualTo("1");
        assertThat(merged.getSummary()).isEqualTo("summary-rsshub");
        assertThat(merged.getAuthor()).isEqualTo("@karpathy");
        assertThat(merged.getContent()).isEqualTo("full content");
        assertThat(merged.getReplyCount()).isEqualTo(5);
        assertThat(merged.getLikeCount()).isEqualTo(100);
        assertThat(merged.getImageUrls()).containsExactly("https://img.example/1.jpg");
        verify(redisRepository).setObject(eq(RedisKeys.tweet("1")), any(Tweet.class), eq(Duration.ofHours(24)));
    }

    @Test
    void shouldUseCacheWhenHitAndSkipFxTwitterCall() {
        Tweet partial = baseTweet("1", "summary");
        when(discoveryClient.discoverTweets("karpathy")).thenReturn(List.of(partial));
        Tweet cached = Tweet.builder()
                .id("1").content("cached content").replyCount(7).retweetCount(8).likeCount(9)
                .imageUrls(List.of("https://img.example/c.jpg")).build();
        when(redisRepository.getObject(eq(RedisKeys.tweet("1")), eq(Tweet.class))).thenReturn(cached);

        List<Tweet> result = source.fetch();

        assertThat(result).hasSize(1);
        Tweet merged = result.get(0);
        assertThat(merged.getContent()).isEqualTo("cached content");
        assertThat(merged.getReplyCount()).isEqualTo(7);
        verify(fxTwitterClient, never()).fetchTweetDetail(anyString());
        verify(twscrapeClient, never()).fetchTweetDetail(anyString(), anyString());
        verify(redisRepository, never()).setObject(anyString(), any(), any(Duration.class));
    }

    @Test
    void shouldSkipTweetWhenFxTwitterFailsAndNotWriteCache() {
        Tweet partial = baseTweet("1", "summary");
        when(discoveryClient.discoverTweets("karpathy")).thenReturn(List.of(partial));
        when(redisRepository.getObject(eq(RedisKeys.tweet("1")), eq(Tweet.class))).thenReturn(null);
        when(fxTwitterClient.fetchTweetDetail("1"))
                .thenThrow(new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR, "404"));

        List<Tweet> result = source.fetch();

        assertThat(result).isEmpty();
        verify(redisRepository, never()).setObject(anyString(), any(), any(Duration.class));
    }

    @Test
    void shouldReturnPartialWhenBothClientsDisabled() {
        fxTwitterProperties.setEnabled(false);
        Tweet partial = baseTweet("1", "summary");
        when(discoveryClient.discoverTweets("karpathy")).thenReturn(List.of(partial));

        List<Tweet> result = source.fetch();

        assertThat(result).hasSize(1);
        Tweet t = result.get(0);
        assertThat(t.getContent()).isNull();
        assertThat(t.getReplyCount()).isZero();
        verify(fxTwitterClient, never()).fetchTweetDetail(anyString());
        verify(twscrapeClient, never()).fetchTweetDetail(anyString(), anyString());
        verify(redisRepository, never()).getObject(anyString(), eq(Tweet.class));
    }

    @Test
    void shouldSkipAccountWhenRssHubFails() {
        twitterProperties.setAccounts(List.of("good", "bad"));
        when(discoveryClient.discoverTweets("good")).thenReturn(List.of(baseTweet("1", "s1")));
        when(discoveryClient.discoverTweets("bad"))
                .thenThrow(new RetryableException(ErrorCode.EXTERNAL_API_ERROR, "5xx"));
        when(redisRepository.getObject(anyString(), eq(Tweet.class))).thenReturn(null);
        when(fxTwitterClient.fetchTweetDetail(anyString()))
                .thenReturn(Tweet.builder().id("1").content("c").build());

        List<Tweet> result = source.fetch();

        assertThat(result).hasSize(1);
        verify(discoveryClient, times(1)).discoverTweets("good");
        verify(discoveryClient, times(1)).discoverTweets("bad");
    }

    @Test
    void shouldTreatCacheReadFailureAsMissAndContinue() {
        Tweet partial = baseTweet("1", "summary");
        when(discoveryClient.discoverTweets("karpathy")).thenReturn(List.of(partial));
        when(redisRepository.getObject(eq(RedisKeys.tweet("1")), eq(Tweet.class)))
                .thenThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "down"));
        Tweet enriched = Tweet.builder().id("1").content("c").replyCount(1).build();
        when(fxTwitterClient.fetchTweetDetail("1")).thenReturn(enriched);

        List<Tweet> result = source.fetch();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("c");
    }

    @Test
    void shouldIgnoreCacheWriteFailure() {
        Tweet partial = baseTweet("1", "summary");
        when(discoveryClient.discoverTweets("karpathy")).thenReturn(List.of(partial));
        when(redisRepository.getObject(anyString(), eq(Tweet.class))).thenReturn(null);
        when(fxTwitterClient.fetchTweetDetail("1"))
                .thenReturn(Tweet.builder().id("1").content("c").build());
        org.mockito.Mockito.doThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "down"))
                .when(redisRepository).setObject(anyString(), any(Tweet.class), any(Duration.class));

        List<Tweet> result = source.fetch();

        assertThat(result).hasSize(1);
    }

    @Test
    void shouldProcessMultipleTweetsFromOneAccount() {
        when(discoveryClient.discoverTweets("karpathy")).thenReturn(List.of(
                baseTweet("1", "s1"), baseTweet("2", "s2")));
        when(redisRepository.getObject(anyString(), eq(Tweet.class))).thenReturn(null);
        when(fxTwitterClient.fetchTweetDetail("1"))
                .thenReturn(Tweet.builder().id("1").content("c1").build());
        when(fxTwitterClient.fetchTweetDetail("2"))
                .thenReturn(Tweet.builder().id("2").content("c2").build());

        List<Tweet> result = source.fetch();

        assertThat(result).hasSize(2);
    }

    // ===================== Story 2.2b 双链降级(twscrape 主 + FxTwitter 备) =====================

    @Test
    void shouldUseTwscrapeAsPrimaryWhenEnabled() {
        twscrapeProperties.setEnabled(true);
        Tweet partial = baseTweet("1", "summary");
        when(discoveryClient.discoverTweets("karpathy")).thenReturn(List.of(partial));
        when(redisRepository.getObject(eq(RedisKeys.tweet("1")), eq(Tweet.class))).thenReturn(null);
        Tweet twscrapeEnriched = Tweet.builder()
                .id("1").content("from-twscrape").replyCount(11).retweetCount(22).likeCount(33)
                .imageUrls(List.of("https://img.example/tw.jpg")).build();
        when(twscrapeClient.fetchTweetDetail(eq("1"), anyString())).thenReturn(twscrapeEnriched);

        List<Tweet> result = source.fetch();

        assertThat(result).hasSize(1);
        Tweet merged = result.get(0);
        assertThat(merged.getContent()).isEqualTo("from-twscrape");
        assertThat(merged.getReplyCount()).isEqualTo(11);
        assertThat(merged.getRetweetCount()).isEqualTo(22);
        assertThat(merged.getLikeCount()).isEqualTo(33);
        // twscrape 成功 → 不调 FxTwitter + 写缓存
        verify(fxTwitterClient, never()).fetchTweetDetail(anyString());
        verify(redisRepository).setObject(eq(RedisKeys.tweet("1")), any(Tweet.class), eq(Duration.ofHours(24)));
    }

    @Test
    void shouldFallbackToFxTwitterWhenTwscrapeFails() {
        twscrapeProperties.setEnabled(true);
        Tweet partial = baseTweet("1", "summary");
        when(discoveryClient.discoverTweets("karpathy")).thenReturn(List.of(partial));
        when(redisRepository.getObject(eq(RedisKeys.tweet("1")), eq(Tweet.class))).thenReturn(null);
        when(twscrapeClient.fetchTweetDetail(eq("1"), anyString()))
                .thenThrow(new RetryableException(ErrorCode.EXTERNAL_API_ERROR, "twscrape timeout"));
        Tweet fxFallback = Tweet.builder()
                .id("1").content("from-fx").replyCount(1).likeCount(2).build();
        when(fxTwitterClient.fetchTweetDetail("1")).thenReturn(fxFallback);

        List<Tweet> result = source.fetch();

        assertThat(result).hasSize(1);
        Tweet merged = result.get(0);
        assertThat(merged.getContent()).isEqualTo("from-fx");
        assertThat(merged.getReplyCount()).isEqualTo(1);
        // 双链均尝试 + 缓存写入(用 FxTwitter 结果)
        verify(twscrapeClient, times(1)).fetchTweetDetail(eq("1"), anyString());
        verify(fxTwitterClient, times(1)).fetchTweetDetail("1");
        verify(redisRepository).setObject(eq(RedisKeys.tweet("1")), any(Tweet.class), eq(Duration.ofHours(24)));
    }

    @Test
    void shouldDropTweetWhenTwscrapeAndFxTwitterBothFail() {
        twscrapeProperties.setEnabled(true);
        Tweet partial = baseTweet("1", "summary");
        when(discoveryClient.discoverTweets("karpathy")).thenReturn(List.of(partial));
        when(redisRepository.getObject(eq(RedisKeys.tweet("1")), eq(Tweet.class))).thenReturn(null);
        when(twscrapeClient.fetchTweetDetail(eq("1"), anyString()))
                .thenThrow(new RetryableException(ErrorCode.EXTERNAL_API_ERROR, "twscrape 5xx"));
        when(fxTwitterClient.fetchTweetDetail("1"))
                .thenThrow(new NonRetryableException(ErrorCode.EXTERNAL_API_ERROR, "fx 404"));

        List<Tweet> result = source.fetch();

        assertThat(result).isEmpty();
        // 双链失败 → 不写缓存(避免 24h 中毒)
        verify(redisRepository, never()).setObject(anyString(), any(), any(Duration.class));
    }

    @Test
    void shouldDropTweetWhenTwscrapeFailsAndFxTwitterDisabled() {
        twscrapeProperties.setEnabled(true);
        fxTwitterProperties.setEnabled(false);
        Tweet partial = baseTweet("1", "summary");
        when(discoveryClient.discoverTweets("karpathy")).thenReturn(List.of(partial));
        when(redisRepository.getObject(eq(RedisKeys.tweet("1")), eq(Tweet.class))).thenReturn(null);
        when(twscrapeClient.fetchTweetDetail(eq("1"), anyString()))
                .thenThrow(new RetryableException(ErrorCode.EXTERNAL_API_ERROR, "twscrape down"));

        List<Tweet> result = source.fetch();

        assertThat(result).isEmpty();
        verify(fxTwitterClient, never()).fetchTweetDetail(anyString());
        verify(redisRepository, never()).setObject(anyString(), any(), any(Duration.class));
    }

    @Test
    void shouldSkipBothClientsWhenCacheHitOnDualChain() {
        twscrapeProperties.setEnabled(true);
        Tweet partial = baseTweet("1", "summary");
        when(discoveryClient.discoverTweets("karpathy")).thenReturn(List.of(partial));
        Tweet cached = Tweet.builder()
                .id("1").content("cached").replyCount(9).likeCount(99).build();
        when(redisRepository.getObject(eq(RedisKeys.tweet("1")), eq(Tweet.class))).thenReturn(cached);

        List<Tweet> result = source.fetch();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("cached");
        verify(twscrapeClient, never()).fetchTweetDetail(anyString(), anyString());
        verify(fxTwitterClient, never()).fetchTweetDetail(anyString());
        verify(redisRepository, never()).setObject(anyString(), any(), any(Duration.class));
    }

    @Test
    void shouldFallbackToPartialWhenBothChainsDisabled() {
        twscrapeProperties.setEnabled(false);
        fxTwitterProperties.setEnabled(false);
        Tweet partial = baseTweet("1", "summary-rsshub");
        when(discoveryClient.discoverTweets("karpathy")).thenReturn(List.of(partial));

        List<Tweet> result = source.fetch();

        assertThat(result).hasSize(1);
        Tweet merged = result.get(0);
        // 双链禁用 → 返回 RSSHub partial 字段, content null
        assertThat(merged.getSummary()).isEqualTo("summary-rsshub");
        assertThat(merged.getContent()).isNull();
        verify(redisRepository, never()).getObject(anyString(), eq(Tweet.class));
    }

    private Tweet baseTweet(String id, String summary) {
        return Tweet.builder()
                .id(id)
                .author("@karpathy")
                .summary(summary)
                .url("https://twitter.com/karpathy/status/" + id)
                .publishedAt(LocalDateTime.now())
                .build();
    }
}
