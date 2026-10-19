package com.choucj.aiaggregator.source.twitter.discovery;

import com.choucj.aiaggregator.common.observability.TestSlowOperationRecorder;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.source.twitter.config.ScraperProperties;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.choucj.aiaggregator.source.twitter.model.TweetAccessStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetContentType;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class XAuthorScraperDiscoveryClientTest {

    private static final String BASE_URL = "http://127.0.0.1:3100";

    private ScraperProperties properties;
    private MockRestServiceServer server;
    private XAuthorScraperDiscoveryClient client;

    @BeforeEach
    void setUp() {
        properties = new ScraperProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setPollIntervalMs(100);
        properties.setMaxWaitSeconds(1);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new XAuthorScraperDiscoveryClient(properties, builder.build(), new ObjectMapper(), TestSlowOperationRecorder.create());
    }

    @Test
    void shouldBuildScraperJobInputForUsername() {
        properties.setIncludeArticles(true);
        properties.setMaxItemsPerAuthor(3);

        Map<String, Object> input = client.buildInput("openai");

        assertThat(input).containsEntry("usernames", List.of("openai"));
        assertThat(input).containsEntry("includePosts", true);
        assertThat(input).containsEntry("includeArticles", true);
        assertThat(input).containsEntry("maxItemsPerAuthor", 3);
        assertThat(input).containsEntry("proxyConfiguration", Map.of("useApifyProxy", false));
    }

    @Test
    void shouldDiscoverOneSpecifiedPostWithExactJobContract() {
        XPostTargetNormalizer.Target target = new XPostTargetNormalizer.Target(
                "https://x.com/u/status/123", "123");
        server.expect(requestTo(BASE_URL + "/v1/jobs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"postUrls":["https://x.com/u/status/123"],"includePosts":true,"includeArticles":false}
                        """, true))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"job\":{\"id\":\"job-post\",\"status\":\"queued\"}}"));
        server.expect(requestTo(BASE_URL + "/v1/jobs/job-post"))
                .andRespond(withSuccess("{\"job\":{\"id\":\"job-post\",\"status\":\"completed\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/jobs/job-post/results?offset=0&limit=100"))
                .andRespond(withSuccess("""
                        {"total":1,"items":[{"id":"123","text":"body","author":{"username":"u"},
                        "quotedPostId":"456","metrics":{"likes":2}}]}
                        """, MediaType.APPLICATION_JSON));

        List<Tweet> tweets = client.discoverSpecifiedPost(target);

        assertThat(tweets).singleElement().satisfies(tweet -> {
            assertThat(tweet.getId()).isEqualTo("123");
            assertThat(tweet.getAuthor()).isEqualTo("@u");
            assertThat(tweet.getQuotedTweetUrl()).isEqualTo("https://x.com/i/status/456");
        });
        server.verify();
    }

    @Test
    void shouldFailClosedWhenSpecifiedResultHasDifferentId() {
        XPostTargetNormalizer.Target target = new XPostTargetNormalizer.Target(
                "https://x.com/u/status/123", "123");
        server.expect(requestTo(BASE_URL + "/v1/jobs"))
                .andRespond(withSuccess("{\"job\":{\"id\":\"job-wrong\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/jobs/job-wrong"))
                .andRespond(withSuccess("{\"job\":{\"status\":\"completed\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/jobs/job-wrong/results?offset=0&limit=100"))
                .andRespond(withSuccess("{\"total\":1,\"items\":[{\"id\":\"999\",\"text\":\"wrong\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.discoverSpecifiedPost(target))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("POST_ID_MISMATCH")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("https://"));
        server.verify();
    }

    @Test
    void shouldTreatZeroTotalAsPermanentPostNotFound() {
        expectSpecifiedJob("job-empty", "completed", null);
        server.expect(requestTo(BASE_URL + "/v1/jobs/job-empty/results?offset=0&limit=100"))
                .andRespond(withSuccess("{\"total\":0,\"items\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.discoverSpecifiedPost(target()))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("POST_NOT_FOUND");
        server.verify();
    }

    @Test
    void shouldTreatSessionAndRateLimitFailuresAsPermanent() {
        for (String code : List.of("INVALID_POST_URL", "LOGIN_REQUIRED", "RATE_LIMITED",
                "PROTECTED_POST", "POST_NOT_FOUND", "POST_ID_MISMATCH")) {
            setUp();
            expectSpecifiedJob("job-" + code, "failed", code);
            assertThatThrownBy(() -> client.discoverSpecifiedPost(target()))
                    .isInstanceOf(NonRetryableException.class)
                    .hasMessageContaining(code);
            server.verify();
        }
    }

    @Test
    void shouldMapPollJobNotFoundToRetryableWithoutChangingUsernameMapping() {
        server.expect(requestTo(BASE_URL + "/v1/jobs"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"job\":{\"id\":\"missing-job\"}}"));
        server.expect(requestTo(BASE_URL + "/v1/jobs/missing-job"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"JOB_NOT_FOUND\"}}"));

        assertThatThrownBy(() -> client.discoverSpecifiedPost(target()))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("JOB_NOT_FOUND");
        server.verify();
    }

    @Test
    void shouldTimeoutWhenSpecifiedJobRemainsQueued() {
        properties.setMaxWaitSeconds(1);
        server.expect(requestTo(BASE_URL + "/v1/jobs"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"job\":{\"id\":\"queued-job\"}}"));
        server.expect(ExpectedCount.manyTimes(), requestTo(BASE_URL + "/v1/jobs/queued-job"))
                .andRespond(withSuccess("{\"job\":{\"status\":\"queued\"}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.discoverSpecifiedPost(target()))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("轮询超时");
        server.verify();
    }

    @Test
    void shouldTreatInconsistentOrTruncatedResultsAsRetryableStructureErrors() {
        for (String body : List.of(
                "{\"total\":2,\"items\":[{\"id\":\"999\",\"text\":\"wrong\"}]}",
                "{\"total\":101,\"items\":[]}")) {
            setUp();
            expectSpecifiedJob("job-structure", "completed", null);
            server.expect(requestTo(BASE_URL + "/v1/jobs/job-structure/results?offset=0&limit=100"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> client.discoverSpecifiedPost(target()))
                    .isInstanceOf(RetryableException.class)
                    .hasMessageContaining("结果结构非法");
            server.verify();
        }
    }

    @Test
    void shouldRetainMediaOnlyPostAndRejectNullTargetBeforeHttp() throws Exception {
        Tweet tweet = client.parseItem(new ObjectMapper().readTree("""
                {"id":"123","media":[{"type":"photo","url":"https://media.example/image.jpg"}]}
                """), null);
        assertThat(tweet).isNotNull();
        assertThat(tweet.getContent()).isNull();
        assertThat(tweet.getMedia()).hasSize(1);

        assertThatThrownBy(() -> client.discoverSpecifiedPost(null))
                .isInstanceOf(NonRetryableException.class);
        server.verify();
    }

    @Test
    void shouldTreatJobLifecycleTechnicalFailuresAsRetryable() {
        for (String code : List.of("JOB_NOT_FOUND", "ACTOR_FAILED")) {
            setUp();
            expectSpecifiedJob("job-" + code, "failed", code);
            assertThatThrownBy(() -> client.discoverSpecifiedPost(target()))
                    .isInstanceOf(RetryableException.class)
                    .hasMessageContaining(code);
            server.verify();
        }
        setUp();
        expectSpecifiedJob("job-cancelled", "cancelled", null);
        assertThatThrownBy(() -> client.discoverSpecifiedPost(target()))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("CANCELLED");
        server.verify();
    }

    @Test
    void shouldTreatUnknownStatusAndInvalidResultStructureAsRetryable() {
        expectSpecifiedJob("job-unknown", "mystery", null);
        assertThatThrownBy(() -> client.discoverSpecifiedPost(target()))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("状态非法");
        server.verify();

        setUp();
        expectSpecifiedJob("job-invalid-result", "completed", null);
        server.expect(requestTo(BASE_URL + "/v1/jobs/job-invalid-result/results?offset=0&limit=100"))
                .andRespond(withSuccess("{\"total\":\"secret body\",\"items\":{}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.discoverSpecifiedPost(target()))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("结果结构非法")
                .satisfies(error -> assertSafe(error.getMessage()));
        server.verify();
    }

    @Test
    void shouldNotLeakSensitiveResponseDataInExceptionOrLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(XAuthorScraperDiscoveryClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        String sensitive = "https://x.com/private/status/123 body Authorization=Bearer leak Cookie=auth_token=abc; ct0=xyz";
        try {
            server.expect(requestTo(BASE_URL + "/v1/jobs"))
                    .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"error\":{\"code\":\"INVALID_POST_URL\",\"message\":\"" + sensitive + "\"}}"));
            assertThatThrownBy(() -> client.discoverSpecifiedPost(target()))
                    .isInstanceOf(NonRetryableException.class)
                    .satisfies(error -> assertSafe(error.getMessage()));
            String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + right);
            assertSafe(logs);
        } finally {
            logger.detachAppender(appender);
        }
        server.verify();
    }

    private void expectSpecifiedJob(String jobId, String status, String code) {
        server.expect(requestTo(BASE_URL + "/v1/jobs"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"job\":{\"id\":\"" + jobId + "\"}}"));
        String error = code == null ? "" : ",\"error\":{\"code\":\"" + code + "\"}";
        server.expect(requestTo(BASE_URL + "/v1/jobs/" + jobId))
                .andRespond(withSuccess("{\"job\":{\"status\":\"" + status + "\"" + error + "}}",
                        MediaType.APPLICATION_JSON));
    }

    private XPostTargetNormalizer.Target target() {
        return new XPostTargetNormalizer.Target("https://x.com/u/status/123", "123");
    }

    private void assertSafe(String value) {
        assertThat(value).doesNotContain("https://x.com/private", "secret body", "Authorization", "Cookie",
                "auth_token", "ct0", "Bearer leak");
    }

    @Test
    void shouldFetchTweetsThroughLocalJobApi() {
        server.expect(requestTo(BASE_URL + "/v1/jobs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"job":{"id":"job-1","status":"queued","resultCount":0}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/jobs/job-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"job":{"id":"job-1","status":"completed","resultCount":1}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/jobs/job-1/results?limit=50"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "items": [
                            {
                              "id": "2079508890610290965",
                              "url": "https://x.com/vbjby3/status/2079508890610290965",
                              "contentType": "article",
                              "requestedUsername": "vbjby3",
                              "createdAt": "2026-07-21T10:08:49.000Z",
                              "article": {
                                "title": "Article title",
                                "body": "Article body",
                                "images": [{"src": "https://img.example/a.jpg"}]
                              },
                              "metrics": {"replyCount": 1, "retweetCount": 2, "likeCount": 3},
                              "links": ["https://example.com"]
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<Tweet> tweets = client.discoverTweets("@vbjby3");

        assertThat(tweets).hasSize(1);
        Tweet tweet = tweets.get(0);
        assertThat(tweet.getId()).isEqualTo("2079508890610290965");
        assertThat(tweet.getAuthor()).isEqualTo("@vbjby3");
        assertThat(tweet.getContent()).isEqualTo("Article body");
        assertThat(tweet.getSummary()).isEqualTo("Article title");
        assertThat(tweet.getReplyCount()).isEqualTo(1);
        assertThat(tweet.getRetweetCount()).isEqualTo(2);
        assertThat(tweet.getLikeCount()).isEqualTo(3);
        assertThat(tweet.getImageUrls()).containsExactly("https://img.example/a.jpg");
        assertThat(tweet.getMedia()).hasSize(1);
        assertThat(tweet.getLinks()).containsExactly("https://example.com");
        assertThat(tweet.getContentType()).isEqualTo(TweetContentType.ARTICLE);
        assertThat(tweet.getAccessStatus()).isEqualTo(TweetAccessStatus.ACCESSIBLE);
        assertThat(tweet.getSourceAccessNote()).isNull();
        server.verify();
    }

    @Test
    void shouldMapSessionFailureJobToNonRetryable() {
        server.expect(requestTo(BASE_URL + "/v1/jobs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"job":{"id":"job-2","status":"queued","resultCount":0}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/jobs/job-2"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "job": {
                            "id": "job-2",
                            "status": "failed",
                            "error": {"code": "LOGIN_REQUIRED", "message": "login required"}
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.discoverTweets("openai"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("LOGIN_REQUIRED")
                .hasMessageContaining("jobId=job-2")
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain("login required"));
        server.verify();
    }

    @Test
    void shouldIncludeJobSummaryWhenActorFailed() {
        server.expect(requestTo(BASE_URL + "/v1/jobs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"job":{"id":"job-actor-failed","status":"queued","resultCount":0}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/v1/jobs/job-actor-failed"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "job": {
                            "id": "job-actor-failed",
                            "status": "failed",
                            "resultCount": 0,
                            "error": {"code": "ACTOR_FAILED", "message": "Actor exited with code 91."},
                            "summary": {
                              "articleDiscoveries": [
                                {"username": "dotey", "articleCount": 0}
                              ],
                              "failedArticleDiscoveries": [
                                {
                                  "username": "dotey",
                                  "code": "ARTICLE_DISCOVERY_FAILED",
                                  "message": "page.goto: net::ERR_CONNECTION_CLOSED at https://x.com/dotey/articles\\nCall log: very long details"
                                }
                              ],
                              "succeededArticles": [],
                              "failedArticles": [],
                              "itemCount": 0
                            }
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.discoverTweets("dotey"))
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("ACTOR_FAILED")
                .hasMessageContaining("resultCount=0")
                .hasMessageContaining("itemCount=0")
                .hasMessageContaining("articleDiscoveries=[dotey:articleCount=0]")
                .hasMessageContaining("failedArticleDiscoveries=[dotey:ARTICLE_DISCOVERY_FAILED:page.goto: net::ERR_CONNECTION_CLOSED")
                .hasMessageContaining("succeededArticles=[]")
                .hasMessageContaining("failedArticles=[]");
        server.verify();
    }

    @Test
    void shouldSkipItemWhenBodyMissing() throws Exception {
        Tweet tweet = client.parseItem(new ObjectMapper().readTree("""
                {
                  "id": "1",
                  "url": "https://x.com/openai/status/1",
                  "requestedUsername": "openai",
                  "article": {"title": "No body"}
                }
                """), "openai");

        assertThat(tweet).isNull();
    }

    /**
     * Story 7.3 T3.4 (AC3): parser 识别 dataset media 的 type=video, 保留 videoUrl/width/height + variants.
     *
     * <p>fixture 参照 local-apify-actor-readiness-20260802.md zhongying14 video media shape:
     * {@code type=video + videoUrl + width=1440 + height=2560}, media 字段契约 (无 alt).
     */
    @Test
    void shouldParseVideoMediaFromDataset() throws Exception {
        Tweet tweet = client.parseItem(new ObjectMapper().readTree("""
                {
                  "id": "100",
                  "url": "https://x.com/u/status/100",
                  "requestedUsername": "u",
                  "body": "video body text",
                  "media": [
                    {
                      "type": "video",
                      "url": "https://pbs.twimg.com/thumb_v.jpg",
                      "videoUrl": "https://video.twimg.com/ext_tw_video/100/pu/vid/1440x2560/abc.mp4",
                      "width": 1440,
                      "height": 2560,
                      "variants": [
                        {
                          "url": "https://video.twimg.com/ext_tw_video/100/pu/vid/1440x2560/high.mp4",
                          "contentType": "video/mp4",
                          "bitrate": 832000,
                          "width": 1440,
                          "height": 2560
                        },
                        {
                          "url": "https://video.twimg.com/ext_tw_video/100/pu/vid/720x1280/low.mp4",
                          "content_type": "video/mp4",
                          "bit_rate": 432000
                        }
                      ]
                    }
                  ]
                }
                """), "u");

        assertThat(tweet).isNotNull();
        assertThat(tweet.getMedia()).hasSize(1);
        var video = tweet.getMedia().get(0);
        assertThat(video.getType()).isEqualTo(TweetMediaType.VIDEO);
        assertThat(video.getSourceUrl()).isEqualTo(
                "https://video.twimg.com/ext_tw_video/100/pu/vid/1440x2560/abc.mp4");
        assertThat(video.getPreviewImageUrl()).isEqualTo("https://pbs.twimg.com/thumb_v.jpg");
        assertThat(video.getWidth()).isEqualTo(1440);
        assertThat(video.getHeight()).isEqualTo(2560);
        assertThat(video.getOrder()).isZero();
        assertThat(video.isAllowDownload()).isFalse();
        assertThat(video.getProvider()).isEqualTo("x-author-scraper");
        assertThat(video.getVariants()).hasSize(2);
        assertThat(video.getVariants().get(0).getUrl()).isEqualTo(
                "https://video.twimg.com/ext_tw_video/100/pu/vid/1440x2560/high.mp4");
        assertThat(video.getVariants().get(0).getContentType()).isEqualTo("video/mp4");
        assertThat(video.getVariants().get(0).getBitrate()).isEqualTo(832000L);
        assertThat(video.getVariants().get(1).getUrl()).isEqualTo(
                "https://video.twimg.com/ext_tw_video/100/pu/vid/720x1280/low.mp4");
        assertThat(video.getVariants().get(1).getBitrate()).isEqualTo(432000L);
        assertThat(video.getVariants().get(1).getWidth()).isEqualTo(1440);
        assertThat(video.getVariants().get(1).getHeight()).isEqualTo(2560);
        // imageUrls 只含 PHOTO, VIDEO 不混入 (AC6)
        assertThat(tweet.getImageUrls()).isEmpty();
        // 摘要已写入, 不含 URL
        assertThat(video.getProviderRawSummary()).isEqualTo("video:variants=2");
    }

    /**
     * Story 7.3 parser: GIF (animated_gif) 识别 + 字段保真.
     */
    @Test
    void shouldParseAnimatedGifMediaFromDataset() throws Exception {
        Tweet tweet = client.parseItem(new ObjectMapper().readTree("""
                {
                  "id": "101",
                  "url": "https://x.com/u/status/101",
                  "requestedUsername": "u",
                  "body": "gif body",
                  "media": [
                    {
                      "type": "animated_gif",
                      "url": "https://pbs.twimg.com/thumb_g.jpg",
                      "videoUrl": "https://video.twimg.com/tweet_video/xyz.mp4",
                      "width": 480,
                      "height": 270
                    }
                  ]
                }
                """), "u");

        assertThat(tweet.getMedia()).hasSize(1);
        var gif = tweet.getMedia().get(0);
        assertThat(gif.getType()).isEqualTo(TweetMediaType.GIF);
        assertThat(gif.getSourceUrl()).isEqualTo("https://video.twimg.com/tweet_video/xyz.mp4");
        assertThat(gif.getPreviewImageUrl()).isEqualTo("https://pbs.twimg.com/thumb_g.jpg");
        assertThat(gif.getWidth()).isEqualTo(480);
        assertThat(gif.getHeight()).isEqualTo(270);
        assertThat(gif.getVariants()).hasSize(1);
    }

    @Test
    void shouldNotUsePreviewUrlAsVideoVariant_whenVideoUrlMissing() throws Exception {
        Tweet tweet = client.parseItem(new ObjectMapper().readTree("""
                {
                  "id": "104",
                  "url": "https://x.com/u/status/104",
                  "requestedUsername": "u",
                  "body": "video without playable url",
                  "media": [
                    {
                      "type": "video",
                      "url": "https://pbs.twimg.com/thumb_only.jpg",
                      "width": 640,
                      "height": 360
                    }
                  ]
                }
                """), "u");

        assertThat(tweet).isNotNull();
        var video = tweet.getMedia().get(0);
        assertThat(video.getType()).isEqualTo(TweetMediaType.VIDEO);
        assertThat(video.getPreviewImageUrl()).isEqualTo("https://pbs.twimg.com/thumb_only.jpg");
        assertThat(video.getSourceUrl()).isNull();
        assertThat(video.getVariants()).isEmpty();
        assertThat(tweet.getImageUrls()).isEmpty();
    }

    /**
     * Story 7.3 T3.5 (AC3): photo 媒体 + type 缺失均走 buildPhoto (回归保护, 保守降级不丢媒体).
     */
    @Test
    void shouldParsePhotoAndFallbackWhenTypeMissing() throws Exception {
        Tweet tweet = client.parseItem(new ObjectMapper().readTree("""
                {
                  "id": "102",
                  "url": "https://x.com/u/status/102",
                  "requestedUsername": "u",
                  "body": "photo body",
                  "media": [
                    {"type": "photo", "url": "https://pbs.twimg.com/p1.jpg"},
                    {"url": "https://pbs.twimg.com/p2.jpg"}
                  ]
                }
                """), "u");

        assertThat(tweet.getMedia()).hasSize(2);
        assertThat(tweet.getMedia()).allSatisfy(m ->
                assertThat(m.getType()).isEqualTo(TweetMediaType.PHOTO));
        assertThat(tweet.getImageUrls()).containsExactly(
                "https://pbs.twimg.com/p1.jpg", "https://pbs.twimg.com/p2.jpg");
    }

    /**
     * Story 7.3: VIDEO + PHOTO 混合, imageUrls 只含 PHOTO, media 含 VIDEO+PHOTO.
     */
    @Test
    void shouldSeparateVideoAndPhotoInMixedMedia() throws Exception {
        Tweet tweet = client.parseItem(new ObjectMapper().readTree("""
                {
                  "id": "103",
                  "url": "https://x.com/u/status/103",
                  "requestedUsername": "u",
                  "body": "mixed",
                  "media": [
                    {"type": "video", "url": "https://pbs.twimg.com/tv.jpg",
                     "videoUrl": "https://video.twimg.com/v.mp4", "width": 640, "height": 360},
                    {"type": "photo", "url": "https://pbs.twimg.com/photo.jpg"}
                  ]
                }
                """), "u");

        assertThat(tweet.getMedia()).hasSize(2);
        assertThat(tweet.getMedia().get(0).getType()).isEqualTo(TweetMediaType.VIDEO);
        assertThat(tweet.getMedia().get(1).getType()).isEqualTo(TweetMediaType.PHOTO);
        assertThat(tweet.getImageUrls()).containsExactly("https://pbs.twimg.com/photo.jpg");
    }
}
