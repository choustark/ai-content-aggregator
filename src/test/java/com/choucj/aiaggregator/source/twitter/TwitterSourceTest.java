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
import com.choucj.aiaggregator.source.twitter.model.TweetAccessStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetContentType;
import com.choucj.aiaggregator.source.twitter.model.TweetRestrictionReason;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
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
    void shouldMergeCachedOriginalStructureFieldsWithoutDroppingPartialMetadata() {
        Tweet partial = baseTweet("1", "summary");
        when(discoveryClient.discoverTweets("karpathy")).thenReturn(List.of(partial));
        Tweet cached = Tweet.builder()
                .id("1")
                .content("cached content")
                .rawText("raw cached content")
                .formattedText("formatted cached content")
                .imageUrls(List.of("https://img.example/cached.jpg"))
                .media(List.of(TweetMedia.builder()
                        .type(TweetMediaType.PHOTO)
                        .sourceUrl("https://img.example/cached.jpg")
                        .provider("cache")
                        .build()))
                .links(List.of("https://example.com"))
                .mentions(List.of("@openai"))
                .quotedTweetUrl("https://x.com/i/status/987")
                .quotedTweetText("quoted")
                .sourceAccessNote("provider note")
                .build();
        when(redisRepository.getObject(eq(RedisKeys.tweet("1")), eq(Tweet.class))).thenReturn(cached);

        List<Tweet> result = source.fetch();

        assertThat(result).hasSize(1);
        Tweet merged = result.get(0);
        assertThat(merged.getAuthor()).isEqualTo("@karpathy");
        assertThat(merged.getSummary()).isEqualTo("summary");
        assertThat(merged.getRawText()).isEqualTo("raw cached content");
        assertThat(merged.getFormattedText()).isEqualTo("formatted cached content");
        assertThat(merged.getMedia()).hasSize(1);
        assertThat(merged.getLinks()).containsExactly("https://example.com");
        assertThat(merged.getMentions()).containsExactly("@openai");
        assertThat(merged.getQuotedTweetUrl()).isEqualTo("https://x.com/i/status/987");
        assertThat(merged.getQuotedTweetText()).isEqualTo("quoted");
        assertThat(merged.getSourceAccessNote()).isNull();
        assertThat(merged.getAccessStatus()).isEqualTo(TweetAccessStatus.RESTRICTED);
        assertThat(merged.getRestrictionReason()).isEqualTo(TweetRestrictionReason.ACCESS_RESTRICTED);
        assertThat(merged.getRestrictionDetail()).isEqualTo("provider note");
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
                .rawText("raw from twscrape")
                .formattedText("formatted from twscrape")
                .imageUrls(List.of("https://img.example/tw.jpg"))
                .media(List.of(TweetMedia.builder()
                        .type(TweetMediaType.PHOTO)
                        .sourceUrl("https://img.example/tw.jpg")
                        .build()))
                .links(List.of("https://example.com/tw"))
                .mentions(List.of("@tw"))
                .quotedTweetUrl("https://x.com/i/status/222")
                .quotedTweetText("tw quote")
                .build();
        when(twscrapeClient.fetchTweetDetail(eq("1"), anyString())).thenReturn(twscrapeEnriched);

        List<Tweet> result = source.fetch();

        assertThat(result).hasSize(1);
        Tweet merged = result.get(0);
        assertThat(merged.getContent()).isEqualTo("from-twscrape");
        assertThat(merged.getReplyCount()).isEqualTo(11);
        assertThat(merged.getRetweetCount()).isEqualTo(22);
        assertThat(merged.getLikeCount()).isEqualTo(33);
        assertThat(merged.getRawText()).isEqualTo("raw from twscrape");
        assertThat(merged.getFormattedText()).isEqualTo("formatted from twscrape");
        assertThat(merged.getMedia()).hasSize(1);
        assertThat(merged.getLinks()).containsExactly("https://example.com/tw");
        assertThat(merged.getMentions()).containsExactly("@tw");
        assertThat(merged.getQuotedTweetUrl()).isEqualTo("https://x.com/i/status/222");
        assertThat(merged.getQuotedTweetText()).isEqualTo("tw quote");
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
                .id("1")
                .content("from-fx")
                .rawText("raw from fx")
                .formattedText("formatted from fx")
                .replyCount(1)
                .likeCount(2)
                .media(List.of(TweetMedia.builder()
                        .type(TweetMediaType.VIDEO)
                        .sourceUrl("https://video.example/fx.mp4")
                        .build()))
                .links(List.of("https://example.com/fx"))
                .mentions(List.of("@fx"))
                .quotedTweetUrl("https://x.com/i/status/333")
                .quotedTweetText("fx quote")
                .build();
        when(fxTwitterClient.fetchTweetDetail("1")).thenReturn(fxFallback);

        List<Tweet> result = source.fetch();

        assertThat(result).hasSize(1);
        Tweet merged = result.get(0);
        assertThat(merged.getContent()).isEqualTo("from-fx");
        assertThat(merged.getReplyCount()).isEqualTo(1);
        assertThat(merged.getRawText()).isEqualTo("raw from fx");
        assertThat(merged.getFormattedText()).isEqualTo("formatted from fx");
        assertThat(merged.getMedia()).hasSize(1);
        assertThat(merged.getLinks()).containsExactly("https://example.com/fx");
        assertThat(merged.getMentions()).containsExactly("@fx");
        assertThat(merged.getQuotedTweetUrl()).isEqualTo("https://x.com/i/status/333");
        assertThat(merged.getQuotedTweetText()).isEqualTo("fx quote");
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
        Tweet partial = baseTweet("1", "summary-rsshub").toBuilder()
                .rawText("partial raw")
                .formattedText("partial formatted")
                .media(List.of(TweetMedia.builder()
                        .type(TweetMediaType.PHOTO)
                        .sourceUrl("https://img.example/partial.jpg")
                        .build()))
                .links(List.of("https://example.com/partial"))
                .mentions(List.of("@partial"))
                .quotedTweetUrl("https://x.com/i/status/444")
                .quotedTweetText("partial quote")
                .build();
        when(discoveryClient.discoverTweets("karpathy")).thenReturn(List.of(partial));

        List<Tweet> result = source.fetch();

        assertThat(result).hasSize(1);
        Tweet merged = result.get(0);
        // 双链禁用 → 返回 RSSHub partial 字段, content null
        assertThat(merged.getSummary()).isEqualTo("summary-rsshub");
        assertThat(merged.getContent()).isNull();
        assertThat(merged.getRawText()).isEqualTo("partial raw");
        assertThat(merged.getFormattedText()).isEqualTo("partial formatted");
        assertThat(merged.getMedia()).hasSize(1);
        assertThat(merged.getLinks()).containsExactly("https://example.com/partial");
        assertThat(merged.getMentions()).containsExactly("@partial");
        assertThat(merged.getQuotedTweetUrl()).isEqualTo("https://x.com/i/status/444");
        assertThat(merged.getQuotedTweetText()).isEqualTo("partial quote");
        verify(redisRepository, never()).getObject(anyString(), eq(Tweet.class));
    }

    // ===================== 派生 sourceAccessNote 证伪修复 (2026-08-30 生产故障) =====================

    /**
     * 生产故障复现 (2026-08-30, tweetId=2058473664430129572): FxTwitter 返回空文本 (media 存在),
     * 打上派生 note "源文本为空或 provider 未返回文本"; mergeTweet 中最终文本回退到 RSSHub partial,
     * note 却被当作独立信号保留 — TweetPublishabilityGate 据此判推文级 BLOCKED (媒体全 PUBLISHABLE
     * 的矛盾日志根因)。合并后文本非空时, 派生 note 已被证伪, 必须清除。
     */
    @Test
    void shouldDropDerivedTextMissingNote_whenEnrichedTextBlankButMergedTextAvailable() {
        Tweet partial = baseTweet("1", "summary-rsshub").toBuilder()
                .content("rsshub content")
                .build();
        when(discoveryClient.discoverTweets("karpathy")).thenReturn(List.of(partial));
        when(redisRepository.getObject(eq(RedisKeys.tweet("1")), eq(Tweet.class))).thenReturn(null);
        Tweet enriched = Tweet.builder()
                .id("1")
                .replyCount(5).retweetCount(10).likeCount(100)
                .media(List.of(TweetMedia.builder()
                        .type(TweetMediaType.PHOTO)
                        .sourceUrl("https://img.example/1.jpg")
                        .build()))
                .sourceAccessNote(Tweet.SOURCE_TEXT_MISSING_NOTE)
                .build();
        when(fxTwitterClient.fetchTweetDetail("1")).thenReturn(enriched);

        List<Tweet> result = source.fetch();

        assertThat(result).hasSize(1);
        Tweet merged = result.get(0);
        assertThat(merged.getContent()).isEqualTo("rsshub content");
        assertThat(merged.getSourceAccessNote())
                .as("最终文本非空时, '源文本为空' 派生 note 已被证伪, 不得保留 (否则 gate 误判 BLOCKED)")
                .isNull();
    }

    /**
     * 反方向同类故障: discovery partial (如 Apify) 空文本打派生 note, FxTwitter 补全文本 —
     * enriched note 为 null 时合并回退到 partial 的派生 note, 同样证伪, 必须清除。
     */
    @Test
    void shouldDropDerivedTextMissingNote_whenPartialTextBlankButEnrichedTextAvailable() {
        Tweet partial = baseTweet("1", "summary-rsshub").toBuilder()
                .sourceAccessNote(Tweet.SOURCE_TEXT_MISSING_NOTE)
                .build();
        when(discoveryClient.discoverTweets("karpathy")).thenReturn(List.of(partial));
        when(redisRepository.getObject(eq(RedisKeys.tweet("1")), eq(Tweet.class))).thenReturn(null);
        Tweet enriched = Tweet.builder()
                .id("1").content("fx content").replyCount(5).retweetCount(10).likeCount(100)
                .build();
        when(fxTwitterClient.fetchTweetDetail("1")).thenReturn(enriched);

        List<Tweet> result = source.fetch();

        assertThat(result).hasSize(1);
        Tweet merged = result.get(0);
        assertThat(merged.getContent()).isEqualTo("fx content");
        assertThat(merged.getSourceAccessNote()).isNull();
    }

    /**
     * 历史 article note 只是类型标签，不是访问受限。合并时必须迁移为 contentType=ARTICLE，
     * 同时清空旧 note，避免后续 gate/renderer 误判 BLOCKED。
     */
    @Test
    void shouldMigrateLegacyArticleNoteToContentType_whenMergedTextAvailable() {
        Tweet partial = baseTweet("1", "summary-rsshub").toBuilder()
                .content("rsshub content")
                .sourceAccessNote(Tweet.SOURCE_ARTICLE_TYPE_NOTE)
                .build();
        when(discoveryClient.discoverTweets("karpathy")).thenReturn(List.of(partial));
        when(redisRepository.getObject(eq(RedisKeys.tweet("1")), eq(Tweet.class))).thenReturn(null);
        Tweet enriched = Tweet.builder()
                .id("1").content("fx content").replyCount(5).retweetCount(10).likeCount(100)
                .build();
        when(fxTwitterClient.fetchTweetDetail("1")).thenReturn(enriched);

        List<Tweet> result = source.fetch();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContentType()).isEqualTo(TweetContentType.ARTICLE);
        assertThat(result.get(0).getSourceAccessNote()).isNull();
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

    // ===================== 完整度短路 (x-author scraper provider 自带全文/互动数/媒体) =====================

    /**
     * discovery-providers=scraper 时推文已完整 (正文+互动数+媒体), 不得再调 twscrape/FxTwitter 双链,
     * 也不做缓存 I/O — 否则每条推文白烧一次 twscrape 子进程调用, 增加 X 风控暴露面.
     */
    @Test
    void shouldSkipEnrichmentWhenDiscoveryTweetAlreadyComplete() {
        Tweet scraperTweet = baseTweet("1", "scrape-title").toBuilder()
                .content("scrape full content")
                .replyCount(3).retweetCount(4).likeCount(5)
                .media(List.of(TweetMedia.builder()
                        .type(TweetMediaType.PHOTO)
                        .sourceUrl("https://img.example/s.jpg")
                        .build()))
                .build();
        when(discoveryClient.discoverTweets("karpathy")).thenReturn(List.of(scraperTweet));
        twscrapeProperties.setEnabled(true);

        List<Tweet> result = source.fetch();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("scrape full content");
        assertThat(result.get(0).getLikeCount()).isEqualTo(5);
        verify(twscrapeClient, never()).fetchTweetDetail(anyString(), anyString());
        verify(fxTwitterClient, never()).fetchTweetDetail(anyString());
        verify(redisRepository, never()).getObject(anyString(), any());
        verify(redisRepository, never()).setObject(anyString(), any(), any(Duration.class));
    }

    /**
     * 仅 content 非空但缺媒体/互动数 (RSSHub 偶发带正文) 不算完整 —
     * 仍须走双链补全, 保住媒体补齐与 2026-08-30 派生 note 清理语义.
     */
    @Test
    void shouldStillEnrichWhenContentPresentButMediaAndMetricsMissing() {
        Tweet partial = baseTweet("1", "summary-rsshub").toBuilder()
                .content("rsshub content")
                .build();
        when(discoveryClient.discoverTweets("karpathy")).thenReturn(List.of(partial));
        when(redisRepository.getObject(eq(RedisKeys.tweet("1")), eq(Tweet.class))).thenReturn(null);
        Tweet enriched = Tweet.builder()
                .id("1").replyCount(5).likeCount(100)
                .media(List.of(TweetMedia.builder()
                        .type(TweetMediaType.PHOTO)
                        .sourceUrl("https://img.example/1.jpg")
                        .build()))
                .build();
        when(fxTwitterClient.fetchTweetDetail("1")).thenReturn(enriched);

        List<Tweet> result = source.fetch();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMedia()).hasSize(1);
        verify(fxTwitterClient).fetchTweetDetail("1");
    }
}
