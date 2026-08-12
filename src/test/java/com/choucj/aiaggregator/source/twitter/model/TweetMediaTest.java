package com.choucj.aiaggregator.source.twitter.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 7.1: TweetMedia 字段扩展后的默认值与 toBuilder 行为验证.
 */
class TweetMediaTest {

    @Test
    void should_apply_lifecycle_defaults_when_built_minimal() {
        TweetMedia m = TweetMedia.builder().id("m1").build();

        assertThat(m.getType()).isEqualTo(TweetMediaType.UNKNOWN);
        assertThat(m.getDownloadStatus()).isEqualTo(MediaDownloadStatus.PENDING);
        assertThat(m.getUploadStatus()).isEqualTo(MediaUploadStatus.PENDING);
        assertThat(m.getPublishability()).isEqualTo(PublishabilityStatus.UNKNOWN);
        assertThat(m.getVariants()).isEmpty();
        assertThat(m.getLocalPath()).isNull();
        assertThat(m.getWechatUrl()).isNull();
        assertThat(m.getWechatMediaId()).isNull();
        assertThat(m.isAllowDownload()).isFalse();
    }

    @Test
    void should_toBuilder_preserve_fields_and_allow_override() {
        TweetMedia original = TweetMedia.builder()
                .id("m1")
                .type(TweetMediaType.PHOTO)
                .sourceUrl("u1")
                .build();

        TweetMedia updated = original.toBuilder()
                .localPath("media/twitter/2026-08-02/2083/m1.jpg")
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .build();

        // 保留
        assertThat(updated.getId()).isEqualTo("m1");
        assertThat(updated.getType()).isEqualTo(TweetMediaType.PHOTO);
        assertThat(updated.getSourceUrl()).isEqualTo("u1");
        // 覆盖
        assertThat(updated.getLocalPath()).isEqualTo("media/twitter/2026-08-02/2083/m1.jpg");
        assertThat(updated.getDownloadStatus()).isEqualTo(MediaDownloadStatus.DOWNLOADED);
        // 未设字段保留默认/原值
        assertThat(updated.getUploadStatus()).isEqualTo(MediaUploadStatus.PENDING);
    }
}
