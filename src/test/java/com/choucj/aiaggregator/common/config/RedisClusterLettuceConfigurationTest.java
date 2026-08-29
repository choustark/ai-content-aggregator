package com.choucj.aiaggregator.common.config;

import com.choucj.aiaggregator.common.repository.RedisRepository;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 序列化配置测试 — 验证 Java 8 时间类型正确序列化/反序列化.
 *
 * <p>修复 {@code Tweet.publishedAt} 字段 {@link LocalDateTime} 序列化失败问题:
 * Jackson 默认不支持 Java 8 时间类型,需要注册 {@code JavaTimeModule}.
 *
 * <p><b>测试场景:</b>
 * <ul>
 *   <li>序列化包含 {@link LocalDateTime} 的 Tweet 对象到 Redis</li>
 *   <li>从 Redis 反序列化并验证字段完整性</li>
 *   <li>确保日期时间值不变</li>
 * </ul>
 *
 * <p><b>跳过策略:</b> 与 {@code RedisClusterConnectivityTest} / {@code RedisJsonIntegrationTest}
 * 一致, 本地无 Redis Cluster 时通过 {@link Assumptions#assumeTrue(boolean, String)}
 * 跳过整套外部依赖测试, 避免 CI 或本地轻量回归被环境阻塞.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("external")
class RedisClusterLettuceConfigurationTest {

    @Autowired
    private RedisRepository redisRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeAll
    void requireRedisClusterOnline() {
        boolean reachable;
        try {
            String pong = redisTemplate.getConnectionFactory()
                    .getConnection()
                    .ping();
            reachable = "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable,
                "本地 Redis Cluster 不可用,跳过 Redis 序列化配置集成测试");
    }

    @Test
    void shouldSerializeAndDeserializeLocalDateTimeField() {
        // Given: 一个包含 LocalDateTime 字段的 Tweet 对象
        LocalDateTime originalPublishedAt = LocalDateTime.of(2026, 8, 16, 11, 40, 35);
        Tweet originalTweet = Tweet.builder()
                .id("2087893881496985988")
                .author("@testuser")
                .content("Test tweet content")
                .summary("Test summary")
                .url("https://x.com/testuser/status/2087893881496985988")
                .publishedAt(originalPublishedAt)
                .replyCount(10)
                .retweetCount(20)
                .likeCount(100)
                .imageUrls(List.of("https://example.com/image.jpg"))
                .build();

        String key = "tweet:serialization-test:" + originalTweet.getId();

        try {
            // When: 写入 Redis
            redisRepository.setObject(key, originalTweet, Duration.ofMinutes(5));

            // Then: 从 Redis 读取并验证
            Tweet deserializedTweet = redisRepository.getObject(key, Tweet.class);

            assertThat(deserializedTweet).isNotNull();
            assertThat(deserializedTweet.getId()).isEqualTo(originalTweet.getId());
            assertThat(deserializedTweet.getAuthor()).isEqualTo(originalTweet.getAuthor());
            assertThat(deserializedTweet.getContent()).isEqualTo(originalTweet.getContent());
            assertThat(deserializedTweet.getSummary()).isEqualTo(originalTweet.getSummary());
            assertThat(deserializedTweet.getUrl()).isEqualTo(originalTweet.getUrl());
            assertThat(deserializedTweet.getPublishedAt()).isEqualTo(originalPublishedAt);
            assertThat(deserializedTweet.getReplyCount()).isEqualTo(originalTweet.getReplyCount());
            assertThat(deserializedTweet.getRetweetCount()).isEqualTo(originalTweet.getRetweetCount());
            assertThat(deserializedTweet.getLikeCount()).isEqualTo(originalTweet.getLikeCount());
            assertThat(deserializedTweet.getImageUrls()).isEqualTo(originalTweet.getImageUrls());

        } finally {
            // Cleanup: 删除测试数据
            redisRepository.delete(key);
        }
    }

    @Test
    void shouldHandleNullLocalDateTimeField() {
        // Given: publishedAt 为 null 的 Tweet
        Tweet tweetWithNullDate = Tweet.builder()
                .id("null-date-test")
                .author("@testuser")
                .content("Tweet without publish date")
                .build();

        String key = "tweet:null-date-test";

        try {
            // When & Then: 不应抛出序列化异常
            redisRepository.setObject(key, tweetWithNullDate, Duration.ofMinutes(5));

            Tweet deserialized = redisRepository.getObject(key, Tweet.class);
            assertThat(deserialized).isNotNull();
            assertThat(deserialized.getPublishedAt()).isNull();

        } finally {
            redisRepository.delete(key);
        }
    }
}
