package com.choucj.aiaggregator.source.twitter.media;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.repository.RedisRepository;
import com.choucj.aiaggregator.common.util.RedisKeys;
import com.choucj.aiaggregator.source.twitter.media.model.MediaRuntimeItem;
import com.choucj.aiaggregator.source.twitter.media.model.MediaRuntimeState;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Story 7.4: MediaRuntimeStateRepository 单元测试.
 *
 * <p>软失败范式 §1.7: 真实 Service + mock RedisRepository (不 mock 整个 Repository 链),
 * 验证快照写入 (TTL/软失败) 与读取 (透传/miss) 行为.
 */
@ExtendWith(MockitoExtension.class)
class MediaRuntimeStateRepositoryTest {

    private static final String TWEET_ID = "1234567890";

    @Mock
    private RedisRepository redisRepository;

    private MediaRuntimeStateRepository repository;

    @BeforeEach
    void setUp() {
        repository = new MediaRuntimeStateRepository(redisRepository);
    }

    /** T5.2 (AC1): saveSnapshot 调用 setObject + 正确 key + TTL 30 天. */
    @Test
    void shouldSaveSnapshotWithTtlThirtyDays() {
        List<MediaRuntimeItem> items = List.of(
                MediaRuntimeItem.builder().mediaId("m1").type(TweetMediaType.PHOTO)
                        .downloadStatus(MediaDownloadStatus.DOWNLOADED).localPath("media/twitter/2026-08-02/t1/f.jpg")
                        .build(),
                MediaRuntimeItem.builder().mediaId("m2").type(TweetMediaType.VIDEO)
                        .downloadStatus(MediaDownloadStatus.SKIPPED)
                        .failureReason("视频/GIF 复现路径待 Story 8.2 spike 决定，暂不下载").build());

        repository.saveSnapshot(TWEET_ID, items);

        ArgumentCaptor<MediaRuntimeState> stateCaptor = ArgumentCaptor.forClass(MediaRuntimeState.class);
        verify(redisRepository).setObject(eq(RedisKeys.tweetMedia(TWEET_ID)), stateCaptor.capture(),
                eq(Duration.ofDays(30)));
        MediaRuntimeState state = stateCaptor.getValue();
        assertThat(state.getTweetId()).isEqualTo(TWEET_ID);
        assertThat(state.getGeneratedAt()).isNotNull();
        assertThat(state.getMediaStates()).hasSize(2);
        assertThat(state.getMediaStates().get(0).getMediaId()).isEqualTo("m1");
        assertThat(state.getMediaStates().get(0).getDownloadStatus()).isEqualTo(MediaDownloadStatus.DOWNLOADED);
        // N4: MediaRuntimeItem 无 URL 字段 (编译期保证), 值不含 variant URL
        assertThat(state.getMediaStates().get(1).getFailureReason()).doesNotContain("http");
    }

    /** T5.2 (AC5): saveSnapshot 软失败 — RetryableException 不抛. */
    @Test
    void shouldSwallowRetryableExceptionOnSave() {
        org.mockito.Mockito.doThrow(new RetryableException("Redis 连接失败"))
                .when(redisRepository).setObject(any(), any(), any());

        repository.saveSnapshot(TWEET_ID, List.of());

        // 不抛异常即为通过 (软失败, log.warn 内部吞掉)
    }

    /** T5.2 (AC5): saveSnapshot 软失败 — NonRetryableException 不抛. */
    @Test
    void shouldSwallowNonRetryableExceptionOnSave() {
        org.mockito.Mockito.doThrow(new NonRetryableException("序列化失败"))
                .when(redisRepository).setObject(any(), any(), any());

        repository.saveSnapshot(TWEET_ID, List.of());

        // 不抛异常即为通过
    }

    /** T5.3 (AC3): getSnapshot 命中返回对象. */
    @Test
    void shouldReturnSnapshotOnHit() {
        MediaRuntimeState state = MediaRuntimeState.builder()
                .tweetId(TWEET_ID)
                .mediaStates(List.of(MediaRuntimeItem.builder().mediaId("m1")
                        .downloadStatus(MediaDownloadStatus.DOWNLOADED).build()))
                .build();
        when(redisRepository.getObject(RedisKeys.tweetMedia(TWEET_ID), MediaRuntimeState.class))
                .thenReturn(state);

        Optional<MediaRuntimeState> result = repository.getSnapshot(TWEET_ID);

        assertThat(result).isPresent();
        assertThat(result.get().getMediaStates()).hasSize(1);
    }

    /** T5.3 (AC3): getSnapshot miss 返回 empty. */
    @Test
    void shouldReturnEmptyOnMiss() {
        when(redisRepository.getObject(RedisKeys.tweetMedia(TWEET_ID), MediaRuntimeState.class))
                .thenReturn(null);

        assertThat(repository.getSnapshot(TWEET_ID)).isEmpty();
    }

    /** T5.3 (AC5): getSnapshot 透传异常 (不软失败 — 调用方需区分查询失败与未找到). */
    @Test
    void shouldPropagateExceptionOnGet() {
        when(redisRepository.getObject(RedisKeys.tweetMedia(TWEET_ID), MediaRuntimeState.class))
                .thenThrow(new RetryableException("Redis 连接失败"));

        assertThatThrownBy(() -> repository.getSnapshot(TWEET_ID))
                .isInstanceOf(RetryableException.class);
    }

    /** T5.2 边界: tweetId blank 时跳过写入 (防御, 不触 Redis). */
    @Test
    void shouldSkipSaveWhenTweetIdBlank() {
        repository.saveSnapshot(" ", List.of());

        verify(redisRepository, org.mockito.Mockito.never()).setObject(any(), any(), any());
    }
}
