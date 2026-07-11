package com.choucj.aiaggregator.source.github;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.repository.RedisRepository;
import com.choucj.aiaggregator.common.util.RedisKeys;
import com.choucj.aiaggregator.source.github.client.GitHubClient;
import com.choucj.aiaggregator.source.github.config.GitHubProperties;
import com.choucj.aiaggregator.source.github.model.GitHubRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 4.1 AC-5 {@link GitHubSource} 单测 — 软失败测试范式 (§1.7).
 *
 * <p>覆盖场景:
 * <ul>
 *   <li>AC-5: 缓存命中直接返回, 跳过 GitHubClient 调用</li>
 *   <li>AC-5: 缓存未命中调 GitHubClient + 写回缓存</li>
 *   <li>AC-5: Redis 读异常视为未命中, 走 GitHubClient</li>
 *   <li>AC-5: Redis 写异常不阻塞, 返回客户端结果</li>
 *   <li>AC-6: GitHubClient 抛 Retryable/NonRetryable 透传上层 (不写缓存)</li>
 *   <li>内部开关 disabled 返回空列表</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class GitHubSourceTest {

    @Mock
    private GitHubClient gitHubClient;
    @Mock
    private RedisRepository redisRepository;

    private GitHubSource source;
    private GitHubProperties properties;

    @BeforeEach
    void setUp() {
        properties = new GitHubProperties();
        properties.setEnabled(true);
        properties.getTrending().setLanguage("java");
        properties.getTrending().setLookbackDays(7);
        source = new GitHubSource(gitHubClient, redisRepository, properties);
    }

    @Test
    void shouldReturnCachedWhenRedisHit() {
        GitHubRepo[] cached = {repo("a/a"), repo("b/b")};
        when(redisRepository.getObject(RedisKeys.githubTrending("java", 7), GitHubRepo[].class))
                .thenReturn(cached);

        List<GitHubRepo> result = source.fetch();

        assertThat(result).hasSize(2);
        verify(gitHubClient, never()).fetchTrending();
        verify(redisRepository, never()).setObject(any(), any(), any(Duration.class));
    }

    @Test
    void shouldFetchFromClientAndWriteCacheWhenRedisMiss() {
        when(redisRepository.getObject(eq(RedisKeys.githubTrending("java", 7)), eq(GitHubRepo[].class)))
                .thenReturn(null);
        List<GitHubRepo> clientResult = List.of(repo("a/a"), repo("b/b"));
        when(gitHubClient.fetchTrending()).thenReturn(clientResult);

        List<GitHubRepo> result = source.fetch();

        assertThat(result).isSameAs(clientResult);
        verify(redisRepository).setObject(
                eq(RedisKeys.githubTrending("java", 7)), any(GitHubRepo[].class), eq(Duration.ofHours(1)));
    }

    @Test
    void shouldFallbackToClientWhenRedisReadThrows() {
        when(redisRepository.getObject(eq(RedisKeys.githubTrending("java", 7)), eq(GitHubRepo[].class)))
                .thenThrow(new RetryableException(com.choucj.aiaggregator.common.model.ErrorCode.REDIS_CONNECTION_ERROR,
                        "redis down"));
        when(gitHubClient.fetchTrending()).thenReturn(List.of(repo("a/a")));

        List<GitHubRepo> result = source.fetch();

        assertThat(result).hasSize(1);
        verify(redisRepository).setObject(any(), any(), any(Duration.class));
    }

    @Test
    void shouldNotFailWhenRedisWriteThrows() {
        when(redisRepository.getObject(any(), eq(GitHubRepo[].class))).thenReturn(null);
        when(gitHubClient.fetchTrending()).thenReturn(List.of(repo("a/a")));
        org.mockito.Mockito.doThrow(new NonRetryableException(
                        com.choucj.aiaggregator.common.model.ErrorCode.REDIS_DATA_ERROR, "write fail"))
                .when(redisRepository).setObject(any(), any(), any(Duration.class));

        List<GitHubRepo> result = source.fetch();

        assertThat(result).hasSize(1);
    }

    @Test
    void shouldPropagateRetryableExceptionFromClient() {
        when(redisRepository.getObject(any(), eq(GitHubRepo[].class))).thenReturn(null);
        when(gitHubClient.fetchTrending()).thenThrow(new RetryableException(
                com.choucj.aiaggregator.common.model.ErrorCode.EXTERNAL_API_ERROR, "429"));

        assertThatThrownBy(() -> source.fetch()).isInstanceOf(RetryableException.class);
        verify(redisRepository, never()).setObject(any(), any(), any(Duration.class));
    }

    @Test
    void shouldPropagateNonRetryableExceptionFromClient() {
        when(redisRepository.getObject(any(), eq(GitHubRepo[].class))).thenReturn(null);
        when(gitHubClient.fetchTrending()).thenThrow(new NonRetryableException(
                com.choucj.aiaggregator.common.model.ErrorCode.EXTERNAL_API_ERROR, "404"));

        assertThatThrownBy(() -> source.fetch()).isInstanceOf(NonRetryableException.class);
    }

    @Test
    void shouldReturnEmptyWhenInternalDisabled() {
        properties.setEnabled(false);
        assertThat(source.fetch()).isEmpty();
        verify(gitHubClient, never()).fetchTrending();
        verify(redisRepository, never()).getObject(any(), eq(GitHubRepo[].class));
    }

    private GitHubRepo repo(String fullName) {
        return GitHubRepo.builder().fullName(fullName).name(fullName.split("/")[1]).build();
    }
}
