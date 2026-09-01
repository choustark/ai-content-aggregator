package com.choucj.aiaggregator.source.twitter.discovery;

import com.choucj.aiaggregator.common.exception.DegradationException;
import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.source.twitter.config.ApifyTwitterProperties;
import com.choucj.aiaggregator.source.twitter.config.TwitterTargetProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApifyDiscoveryClientTest {

    private ApifyTwitterProperties properties;
    private TwitterTargetProperties targetProperties;
    private ApifyDiscoveryClient client;

    @BeforeEach
    void setUp() {
        properties = new ApifyTwitterProperties();
        properties.setToken("test-token");
        targetProperties = new TwitterTargetProperties();
        client = new ApifyDiscoveryClient(properties, mock(RestClient.class), new ObjectMapper(), targetProperties);
    }

    @Test
    void shouldParseCommonApifyTweetFields() {
        String body = """
                [
                  {
                    "id": "12345",
                    "url": "https://twitter.com/OpenAI/status/12345",
                    "username": "OpenAI",
                    "text": "hello from apify",
                    "createdAt": "2026-07-18T10:00:00Z",
                    "replyCount": 3,
                    "retweetCount": 4,
                    "likeCount": 5,
                    "images": ["https://img.example/a.jpg"]
                  }
                ]
                """;

        List<Tweet> tweets = client.parseResponse(body, "OpenAI");

        assertThat(tweets).hasSize(1);
        Tweet tweet = tweets.get(0);
        assertThat(tweet.getId()).isEqualTo("12345");
        assertThat(tweet.getAuthor()).isEqualTo("@OpenAI");
        assertThat(tweet.getContent()).isEqualTo("hello from apify");
        assertThat(tweet.getSummary()).isEqualTo("hello from apify");
        assertThat(tweet.getReplyCount()).isEqualTo(3);
        assertThat(tweet.getRetweetCount()).isEqualTo(4);
        assertThat(tweet.getLikeCount()).isEqualTo(5);
        assertThat(tweet.getImageUrls()).containsExactly("https://img.example/a.jpg");
    }

    @Test
    void shouldExtractIdFromUrlWhenIdMissing() {
        String body = """
                [
                  {
                    "tweetUrl": "https://twitter.com/OpenAI/status/67890?s=20",
                    "userName": "@OpenAI",
                    "fullText": "url only"
                  }
                ]
                """;

        List<Tweet> tweets = client.parseResponse(body, "OpenAI");

        assertThat(tweets).hasSize(1);
        assertThat(tweets.get(0).getId()).isEqualTo("67890");
        assertThat(tweets.get(0).getAuthor()).isEqualTo("@OpenAI");
    }

    /**
     * 终局清理 (2026-08-30): client 不再写派生 note "源文本为空或 provider 未返回文本" —
     * 它只是调用时文本快照, 跨源合并后会与最终文本矛盾 (gate 误判推文级 BLOCKED 的
     * 生产故障根因)。文本缺失判定权归 gate T1 独占, note 字段只留独立信号。
     */
    @Test
    void shouldNotSetSourceAccessNoteWhenTextIsEmpty() {
        String body = """
                [
                  {
                    "id": "99999",
                    "text": "",
                    "username": "OpenAI"
                  }
                ]
                """;

        List<Tweet> tweets = client.parseResponse(body, "OpenAI");

        assertThat(tweets).hasSize(1);
        assertThat(tweets.get(0).getContent()).isNull();
        assertThat(tweets.get(0).getSourceAccessNote()).isNull();
    }

    @Test
    void shouldFailFastWhenTokenMissing() {
        properties.setToken("");

        assertThatThrownBy(() -> client.discoverTweets("OpenAI"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("apify.twitter.token");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldMapRestClientResponseExtractionFailureToRetryable() {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        client = new ApifyDiscoveryClient(properties, restClient, new ObjectMapper(), targetProperties);

        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(org.mockito.ArgumentMatchers.anyString())).thenReturn(bodySpec);
        when(bodySpec.body(org.mockito.ArgumentMatchers.any(Map.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenThrow(new RestClientException("extract failed"));

        assertThatThrownBy(() -> client.discoverTweets("OpenAI"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("Apify 响应读取失败");
    }

    @Test
    void shouldKeepActorIdTildeUnescapedInRunUrl() {
        properties.setActorId("apidojo/tweet-scraper");

        String url = client.buildRunUrl();

        assertThat(url).contains("/v2/acts/apidojo~tweet-scraper/run-sync-get-dataset-items");
        assertThat(url).doesNotContain("apidojo%7Etweet-scraper");
    }

    @Test
    void shouldBuildSearchTermInputByDefault() {
        properties.setMaxTweetsPerAccount(50);
        properties.setUseSearchTerms(true);

        Map<String, Object> input = client.buildInput("@OpenAI");

        assertThat(input).containsEntry("searchTerms", List.of("from:OpenAI"));
        assertThat(input).containsEntry("maxItems", 50);
        assertThat(input).containsEntry("sort", "Latest");
        assertThat(input).doesNotContainKey("twitterHandles");
    }

    @Test
    void shouldBuildHandleInputWhenSearchTermsDisabled() {
        properties.setUseSearchTerms(false);

        Map<String, Object> input = client.buildInput("OpenAI");

        assertThat(input).containsEntry("usernames", List.of("OpenAI"));
        assertThat(input).doesNotContainKey("searchTerms");
    }

    @Test
    void shouldParseParseforgeActorFields() {
        String body = """
                [
                  {
                    "id": "1940000000000000000",
                    "twitterUrl": "https://x.com/OpenAI/status/1940000000000000000",
                    "fullText": "parseforge output",
                    "createdAt": "2026-07-18T10:00:00.000Z",
                    "replyCount": 1,
                    "retweetCount": 2,
                    "likeCount": 3,
                    "author": {
                      "userName": "OpenAI"
                    },
                    "media": [
                      {
                        "type": "photo",
                        "url": "https://pbs.twimg.com/media/example.jpg"
                      }
                    ]
                  }
                ]
                """;

        List<Tweet> tweets = client.parseResponse(body, "fallback");

        assertThat(tweets).hasSize(1);
        Tweet tweet = tweets.get(0);
        assertThat(tweet.getAuthor()).isEqualTo("@OpenAI");
        assertThat(tweet.getContent()).isEqualTo("parseforge output");
        assertThat(tweet.getImageUrls()).containsExactly("https://pbs.twimg.com/media/example.jpg");
    }

    @Test
    void shouldParseApifyExtendedMediaAndKeepVideoThumbnailOutOfImageUrls() {
        String body = """
                [
                  {
                    "id": "1812256998588662068",
                    "url": "https://x.com/elonmusk/status/1812256998588662068",
                    "text": "short text",
                    "fullText": "full text with media",
                    "author": {"userName": "elonmusk"},
                    "media": [
                      {
                        "type": "video",
                        "url": "https://pbs.twimg.com/amplify_video_thumb/thumb.jpg"
                      }
                    ],
                    "extendedEntities": {
                      "media": [
                        {
                          "type": "video",
                          "id_str": "1812256938383568896",
                          "media_url_https": "https://pbs.twimg.com/amplify_video_thumb/thumb.jpg",
                          "ext_media_availability": {"status": "Available"},
                          "allow_download_status": {"allow_download": true},
                          "original_info": {"width": 1280, "height": 720},
                          "video_info": {
                            "variants": [
                              {"content_type": "application/x-mpegURL", "url": "https://video.example/pl.m3u8"},
                              {"bitrate": 2176000, "content_type": "video/mp4", "url": "https://video.example/high.mp4"}
                            ]
                          }
                        }
                      ]
                    }
                  }
                ]
                """;

        List<Tweet> tweets = client.parseResponse(body, "fallback");

        Tweet tweet = tweets.get(0);
        assertThat(tweet.getContent()).isEqualTo("full text with media");
        assertThat(tweet.getRawText()).isEqualTo("full text with media");
        assertThat(tweet.getFormattedText()).isEqualTo("full text with media");
        assertThat(tweet.getImageUrls()).isEmpty();
        assertThat(tweet.getMedia()).hasSize(1);
        assertThat(tweet.getMedia().get(0).getType()).isEqualTo(TweetMediaType.VIDEO);
        assertThat(tweet.getMedia().get(0).getPreviewImageUrl())
                .isEqualTo("https://pbs.twimg.com/amplify_video_thumb/thumb.jpg");
        assertThat(tweet.getMedia().get(0).getSourceUrl()).isEqualTo("https://video.example/high.mp4");
        assertThat(tweet.getMedia().get(0).getVariants()).hasSize(2);
        assertThat(tweet.getMedia().get(0).getWidth()).isEqualTo(1280);
        assertThat(tweet.getMedia().get(0).isAllowDownload()).isTrue();
    }

    @Test
    void shouldProjectOnlyPhotoMediaToImageUrlsAndDefaultPhotoDownloadAllowed() {
        String body = """
                [
                  {
                    "id": "1812258574049157405",
                    "url": "https://x.com/elonmusk/status/1812258574049157405",
                    "fullText": "photo text",
                    "author": {"userName": "elonmusk"},
                    "extendedEntities": {
                      "media": [
                        {
                          "type": "photo",
                          "id_str": "1812258569280233472",
                          "media_url_https": "https://pbs.twimg.com/media/photo.jpg",
                          "ext_media_availability": {"status": "Available"},
                          "allow_download_status": null,
                          "original_info": {"width": 2048, "height": 1365}
                        }
                      ]
                    }
                  }
                ]
                """;

        Tweet tweet = client.parseResponse(body, "fallback").get(0);

        assertThat(tweet.getImageUrls()).containsExactly("https://pbs.twimg.com/media/photo.jpg");
        assertThat(tweet.getMedia()).hasSize(1);
        assertThat(tweet.getMedia().get(0).getType()).isEqualTo(TweetMediaType.PHOTO);
        assertThat(tweet.getMedia().get(0).getSourceUrl()).isEqualTo("https://pbs.twimg.com/media/photo.jpg");
        assertThat(tweet.getMedia().get(0).isAllowDownload()).isTrue();
    }

    @Test
    void shouldFallbackToLegacyImagesWhenMediaProjectionHasNoPhotoSource() {
        String body = """
                [
                  {
                    "id": "1812258574049157406",
                    "url": "https://x.com/elonmusk/status/1812258574049157406",
                    "fullText": "legacy image fallback",
                    "images": ["https://pbs.twimg.com/media/legacy.jpg"],
                    "extendedEntities": {
                      "media": [
                        {
                          "id_str": "unknown-1",
                          "url": "https://pbs.twimg.com/media/unknown-thumb.jpg"
                        }
                      ]
                    }
                  }
                ]
                """;

        Tweet tweet = client.parseResponse(body, "fallback").get(0);

        assertThat(tweet.getMedia()).hasSize(1);
        assertThat(tweet.getMedia().get(0).getType()).isEqualTo(TweetMediaType.UNKNOWN);
        assertThat(tweet.getImageUrls()).containsExactly("https://pbs.twimg.com/media/legacy.jpg");
    }

    @Test
    void shouldSkipNullUrlVariantsAndMarkUnavailableMedia() {
        String body = """
                [
                  {
                    "id": "1812258574049157407",
                    "url": "https://x.com/elonmusk/status/1812258574049157407",
                    "fullText": "unavailable video",
                    "extendedEntities": {
                      "media": [
                        {
                          "type": "video",
                          "id_str": "video-1",
                          "media_url_https": "https://pbs.twimg.com/video_thumb.jpg",
                          "ext_media_availability": {"status": "Unavailable"},
                          "video_info": {
                            "variants": [
                              {"content_type": "video/mp4"},
                              {"bitrate": 832000, "content_type": "video/mp4", "url": "https://video.example/low.mp4"}
                            ]
                          }
                        }
                      ]
                    }
                  }
                ]
                """;

        Tweet tweet = client.parseResponse(body, "fallback").get(0);

        assertThat(tweet.getMedia().get(0).getVariants()).hasSize(1);
        assertThat(tweet.getMedia().get(0).getSourceUrl()).isEqualTo("https://video.example/low.mp4");
        assertThat(tweet.getMedia().get(0).getAvailability()).isEqualTo("Unavailable");
        assertThat(tweet.getMedia().get(0).getFailureReason()).contains("Unavailable");
    }

    // ---- Story 6.4: 指定内容入口 (twitter.target.*) ----

    @Test
    void shouldNormalizePurePostIdTwitterDomainAndBlank() {
        assertThat(client.normalizeTarget("1234567890"))
                .isEqualTo("https://x.com/i/status/1234567890");
        assertThat(client.normalizeTarget("https://twitter.com/OpenAI/status/123"))
                .isEqualTo("https://x.com/OpenAI/status/123");
        assertThat(client.normalizeTarget("https://www.twitter.com/OpenAI/status/123"))
                .isEqualTo("https://x.com/OpenAI/status/123");
        assertThat(client.normalizeTarget("http://twitter.com/OpenAI/status/123?s=20"))
                .isEqualTo("https://x.com/OpenAI/status/123");
        assertThat(client.normalizeTarget("https://x.com/OpenAI/status/123"))
                .isEqualTo("https://x.com/OpenAI/status/123");
        assertThat(client.normalizeTarget("   ")).isNull();
        assertThat(client.normalizeTarget(null)).isNull();
        // Article URL (spike Q1b 未测变体) best-effort 透传
        assertThat(client.normalizeTarget("https://x.com/i/article/abc"))
                .isEqualTo("https://x.com/i/article/abc");
        // Review patch: 非 X URL / 伪 host 一律本地跳过, 不进入 Apify startUrls 消耗配额.
        assertThat(client.normalizeTarget("https://example.com/?ref=twitter.com")).isNull();
        assertThat(client.normalizeTarget("https://twitter.com.evil/OpenAI/status/123")).isNull();
        // CR patch: 无法识别的非空输入 (非数字、非 http(s) URL) 本地跳过 (Task 3.3), 不进入 startUrls
        assertThat(client.normalizeTarget("not-a-url")).isNull();
        assertThat(client.normalizeTarget("hello world")).isNull();
    }

    @Test
    void shouldBuildStartUrlsInputAsObjectArrayWithXcomUrls() {
        // inputDefaults 为 usernames 发现路径调优(含 sort), startUrls 分支必须剥离这些字段 (Task 4.3)
        properties.getInputDefaults().put("sort", "Latest");
        properties.getInputDefaults().put("customDefault", "keep-me");

        Map<String, Object> input = client.buildStartUrlsInput(List.of(
                "https://x.com/OpenAI/status/111",
                "222",
                "https://twitter.com/AnthropicAI/status/333",
                "  ",
                "https://x.com/i/article/zzz"));

        Object startUrls = input.get("startUrls");
        assertThat(startUrls).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> urls = (List<Map<String, String>>) startUrls;
        assertThat(urls).hasSize(4);
        assertThat(urls.get(0)).containsEntry("url", "https://x.com/OpenAI/status/111");
        assertThat(urls.get(1)).containsEntry("url", "https://x.com/i/status/222");
        assertThat(urls.get(2)).containsEntry("url", "https://x.com/AnthropicAI/status/333");
        assertThat(urls.get(3)).containsEntry("url", "https://x.com/i/article/zzz");
        assertThat(input).containsEntry("maxItems", 4);
        // spike F2 / Task 4.3: 指定内容路径不写 sort/usernames/searchTerms
        assertThat(input).doesNotContainKey("sort");
        assertThat(input).doesNotContainKey("usernames");
        assertThat(input).doesNotContainKey("searchTerms");
        // 通用 inputDefaults 仍透传
        assertThat(input).containsEntry("customDefault", "keep-me");
    }

    @Test
    void shouldNotCallApifyWhenTargetEntryDisabled() {
        targetProperties.setEnabled(false);
        RestClient restClient = mock(RestClient.class);
        // 必须用同一个 restClient 构造 client, verifyNoInteractions 才有意义 (CR patch: 原断言对未注入 mock 必然通过)
        client = new ApifyDiscoveryClient(properties, restClient, new ObjectMapper(), targetProperties);

        List<Tweet> result = client.discoverSpecifiedTweets(List.of("https://x.com/OpenAI/status/111"));

        assertThat(result).isEmpty();
        // enabled=false 不得调用 Apify
        org.mockito.Mockito.verifyNoInteractions(restClient);
    }

    @Test
    void shouldReturnEmptyWhenTargetsEmpty() {
        targetProperties.setEnabled(true);

        assertThat(client.discoverSpecifiedTweets(List.of())).isEmpty();
        assertThat(client.discoverSpecifiedTweets(null)).isEmpty();
    }

    @Test
    void shouldReturnEmptyWhenAllTargetsUnrecognized() {
        targetProperties.setEnabled(true);
        // CR patch: blank 与无法识别的非空 garbage 都本地跳过, 不调 Apify
        RestClient restClient = mock(RestClient.class);
        client = new ApifyDiscoveryClient(properties, restClient, new ObjectMapper(), targetProperties);

        List<Tweet> result = client.discoverSpecifiedTweets(List.of("  ", "not-a-url", "hello world"));

        assertThat(result).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(restClient);
    }

    @Test
    void shouldDiscoverSpecifiedTweetsAndReuseFidelityParser() {
        targetProperties.setEnabled(true);
        String body = """
                [
                  {
                    "id": "1940000000000000001",
                    "url": "https://x.com/OpenAI/status/1940000000000000001",
                    "fullText": "specified tweet body",
                    "author": {"userName": "OpenAI"}
                  }
                ]
                """;
        client = new ApifyDiscoveryClient(properties, buildPostStub(body), new ObjectMapper(), targetProperties);

        List<Tweet> tweets = client.discoverSpecifiedTweets(List.of("1940000000000000001"));

        assertThat(tweets).hasSize(1);
        Tweet tweet = tweets.get(0);
        assertThat(tweet.getId()).isEqualTo("1940000000000000001");
        assertThat(tweet.getContent()).isEqualTo("specified tweet body");
        // 复用 6.3 保真解析
        assertThat(tweet.getRawText()).isEqualTo("specified tweet body");
        assertThat(tweet.getFormattedText()).isEqualTo("specified tweet body");
    }

    @Test
    void shouldMap403QuotaToDegradationWithoutLeakingBody() {
        targetProperties.setEnabled(true);
        String fullBody = "{\"error\":{\"type\":\"usage-limit\","
                + "\"message\":\"Monthly usage hard limit exceeded (token=SECRET_VALUE)" + "\"}}";
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatusCode.valueOf(403), "Forbidden", new HttpHeaders(),
                fullBody.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        client = new ApifyDiscoveryClient(properties, buildPostStub(ex), new ObjectMapper(), targetProperties);

        assertThatThrownBy(() -> client.discoverSpecifiedTweets(List.of("111")))
                .isInstanceOf(DegradationException.class)
                .hasMessageContaining("配额/授权不可用")
                .hasMessageNotContaining("SECRET_VALUE")
                // CR patch: 不携带含完整 response body 的 HttpClientErrorException 作 cause (N4)
                .extracting(Throwable::getCause).isNull();
    }

    @Test
    void shouldMapUnknownClientErrorToAccurateTypeTag() {
        // CR patch: 未知 error.type 不再被误标为 invalid-input, tag 反映真实 type (Patch 5)
        targetProperties.setEnabled(true);
        String body = "{\"error\":{\"type\":\"auth-failed\",\"message\":\"token revoked\"}}";
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatusCode.valueOf(401), "Unauthorized", new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        client = new ApifyDiscoveryClient(properties, buildPostStub(ex), new ObjectMapper(), targetProperties);

        assertThatThrownBy(() -> client.discoverSpecifiedTweets(List.of("111")))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("type=auth-failed")
                .hasMessageNotContaining("invalid-input")
                .hasMessageNotContaining("token revoked")
                .extracting(Throwable::getCause).isNull();
    }

    @Test
    void shouldMapNonQuota403ToNonRetryable() {
        targetProperties.setEnabled(true);
        String body = "{\"error\":{\"type\":\"auth-failed\",\"message\":\"token revoked SECRET_VALUE\"}}";
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatusCode.valueOf(403), "Forbidden", new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        client = new ApifyDiscoveryClient(properties, buildPostStub(ex), new ObjectMapper(), targetProperties);

        assertThatThrownBy(() -> client.discoverSpecifiedTweets(List.of("111")))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("type=auth-failed")
                .hasMessageNotContaining("SECRET_VALUE")
                .extracting(Throwable::getCause).isNull();
    }

    @Test
    void shouldMapErrorWithoutTypeToUnknownTag() {
        targetProperties.setEnabled(true);
        // 无 error.type 字段时 tag=unknown (非 invalid-input)
        String body = "{\"unexpected\":\"shape\"}";
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatusCode.valueOf(418), "I'm a teapot", new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        client = new ApifyDiscoveryClient(properties, buildPostStub(ex), new ObjectMapper(), targetProperties);

        assertThatThrownBy(() -> client.discoverSpecifiedTweets(List.of("111")))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("type=unknown")
                .extracting(Throwable::getCause).isNull();
    }

    @Test
    void shouldMapInvalidInputToNonRetryable() {
        targetProperties.setEnabled(true);
        String body = "{\"error\":{\"type\":\"invalid-input\","
                + "\"message\":\"Items in input.startUrls at positions [0] do not contain valid URLs\"}}";
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatusCode.valueOf(400), "Bad Request", new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        client = new ApifyDiscoveryClient(properties, buildPostStub(ex), new ObjectMapper(), targetProperties);

        // 用合法 target (numeric ID) 才能触达 Apify 调用并拿到 mock 的 invalid-input 响应;
        // garbage target 现在本地跳过 (Task 3.3), 不会触达 Apify.
        assertThatThrownBy(() -> client.discoverSpecifiedTweets(List.of("111")))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("invalid-input")
                .extracting(Throwable::getCause).isNull();
    }

    @Test
    void shouldMapRunFailedToNonRetryableConsumingQuota() {
        targetProperties.setEnabled(true);
        String body = "{\"error\":{\"type\":\"run-failed\","
                + "\"message\":\"Actor run did not succeed (run ID: abc, status: FAILED).\"}}";
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatusCode.valueOf(400), "Bad Request", new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        client = new ApifyDiscoveryClient(properties, buildPostStub(ex), new ObjectMapper(), targetProperties);

        assertThatThrownBy(() -> client.discoverSpecifiedTweets(List.of("111")))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("run-failed")
                .hasMessageContaining("消耗配额")
                .extracting(Throwable::getCause).isNull();
    }

    @Test
    void shouldMap429ToRetryableOnSpecifiedPath() {
        targetProperties.setEnabled(true);
        String body = "{\"error\":{\"type\":\"rate-limit\",\"message\":\"SECRET_VALUE\"}}";
        HttpClientErrorException ex = HttpClientErrorException.create(
                HttpStatusCode.valueOf(429), "Too Many Requests", new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        client = new ApifyDiscoveryClient(properties, buildPostStub(ex), new ObjectMapper(), targetProperties);

        assertThatThrownBy(() -> client.discoverSpecifiedTweets(List.of("111")))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("限流(429)")
                .hasMessageNotContaining("SECRET_VALUE")
                .extracting(Throwable::getCause).isNull();
    }

    @Test
    void shouldMap5xxWithoutLeakingBodyAsCauseOnSpecifiedPath() {
        targetProperties.setEnabled(true);
        String body = "{\"error\":{\"message\":\"server failed SECRET_VALUE\"}}";
        HttpServerErrorException ex = HttpServerErrorException.create(
                HttpStatusCode.valueOf(500), "Internal Server Error", new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        client = new ApifyDiscoveryClient(properties, buildPostStub(ex), new ObjectMapper(), targetProperties);

        assertThatThrownBy(() -> client.discoverSpecifiedTweets(List.of("111")))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("服务端错误")
                .hasMessageNotContaining("SECRET_VALUE")
                .extracting(Throwable::getCause).isNull();
    }

    @Test
    void shouldKeepUsernameBuildInputUnchangedAfterTargetStory() {
        // AC6 回归: 新增 target 入口后, 既有账号发现 buildInput(username) 行为不变
        properties.setUseSearchTerms(false);

        Map<String, Object> input = client.buildInput("OpenAI");

        assertThat(input).containsEntry("usernames", List.of("OpenAI"));
        assertThat(input).containsEntry("sort", "Latest");
        assertThat(input).doesNotContainKey("startUrls");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private RestClient buildPostStub(Object bodyOrException) {
        RestClient restClient = mock(RestClient.class);
        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(ArgumentMatchers.anyString())).thenReturn(bodySpec);
        when(bodySpec.body(ArgumentMatchers.any(Map.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        if (bodyOrException instanceof Throwable t) {
            when(responseSpec.body(String.class)).thenThrow(t);
        } else {
            when(responseSpec.body(String.class)).thenReturn((String) bodyOrException);
        }
        return restClient;
    }
}
