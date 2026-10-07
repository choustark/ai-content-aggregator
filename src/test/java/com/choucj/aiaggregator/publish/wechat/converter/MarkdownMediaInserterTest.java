package com.choucj.aiaggregator.publish.wechat.converter;

import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.MediaPhaseState;
import com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus;
import com.choucj.aiaggregator.source.twitter.model.MediaUploadStatus;
import com.choucj.aiaggregator.source.twitter.model.PublishabilityStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 9.1 Task 4: {@link MarkdownMediaInserter} sidecar 快照筛选、稳定排序、去重和
 * Markdown 图片输出测试 (AC 4, 6, 7).
 *
 * <p>核心不变量 (AD-11):
 * <ul>
 *   <li>只消费 prepare 后重读的同一 sidecar 快照 (本组件无 IO, 快照由调用方传入)</li>
 *   <li>可嵌入谓词: type=PHOTO + uploadStatus=UPLOADED + wechatUrl 非空 + publishability != BLOCKED</li>
 *   <li>顺序使用 sidecar 稳定顺序; 去重键优先 media.id, 缺失用 sourceUrl</li>
 *   <li>只输出 Markdown image syntax; 不得输出 X/CDN URL、本地路径或 raw HTML</li>
 * </ul>
 */
class MarkdownMediaInserterTest {

    private final MarkdownMediaInserter inserter = new MarkdownMediaInserter();

    // ===== 可嵌入谓词 =====

    @Test
    void should_embed_only_photo_uploaded_with_wechat_url_and_not_blocked() {
        List<TweetMedia> sidecar = List.of(
                embeddablePhoto("m1", "https://mmbiz.qpic.cn/a.jpg"),
                video("m2"),
                blockedPhoto("m3"),
                photoPendingUpload("m4"));

        MarkdownMediaInserter.MarkdownMediaInsertionResult result =
                inserter.insert("正文", sidecar);

        assertThat(result.content())
                .isEqualTo("正文\n\n![原帖图片-1](https://mmbiz.qpic.cn/a.jpg)");
        assertThat(result.embeddedCount()).isEqualTo(1);
        // 4 个 sidecar 条目中 3 个未嵌入 (VIDEO/BLOCKED/PENDING) 计入降级审计
        assertThat(result.degradedCount()).isEqualTo(3);
    }

    @Test
    void should_keep_sidecar_stable_order() {
        List<TweetMedia> sidecar = List.of(
                embeddablePhoto("m2", "https://mmbiz.qpic.cn/second.jpg"),
                embeddablePhoto("m1", "https://mmbiz.qpic.cn/first.jpg"));

        MarkdownMediaInserter.MarkdownMediaInsertionResult result =
                inserter.insert("正文", sidecar);

        // 按 sidecar 列表顺序输出, 不按 id 排序
        assertThat(result.content()).isEqualTo(
                "正文\n\n![原帖图片-1](https://mmbiz.qpic.cn/second.jpg)"
                        + "\n\n![原帖图片-2](https://mmbiz.qpic.cn/first.jpg)");
        assertThat(result.embeddedCount()).isEqualTo(2);
    }

    @Test
    void should_not_embed_blank_wechat_url() {
        TweetMedia blankUrl = embeddablePhoto("m1", "  ");
        List<TweetMedia> sidecar = List.of(blankUrl);

        MarkdownMediaInserter.MarkdownMediaInsertionResult result =
                inserter.insert("正文", sidecar);

        assertThat(result.content()).isEqualTo("正文");
        assertThat(result.embeddedCount()).isZero();
        assertThat(result.degradedCount()).isEqualTo(1);
    }

    @Test
    void should_not_embed_null_wechat_url() {
        TweetMedia media = TweetMedia.builder()
                .id("m1")
                .type(TweetMediaType.PHOTO)
                .uploadStatus(MediaUploadStatus.UPLOADED)
                .wechatUrl(null)
                .build();

        MarkdownMediaInserter.MarkdownMediaInsertionResult result =
                inserter.insert("正文", List.of(media));

        assertThat(result.content()).isEqualTo("正文");
        assertThat(result.embeddedCount()).isZero();
    }

    // ===== 去重 =====

    @Test
    void should_deduplicate_by_media_id_keeping_first_occurrence() {
        List<TweetMedia> sidecar = List.of(
                embeddablePhoto("m1", "https://mmbiz.qpic.cn/first.jpg"),
                embeddablePhoto("m1", "https://mmbiz.qpic.cn/duplicate.jpg"));

        MarkdownMediaInserter.MarkdownMediaInsertionResult result =
                inserter.insert("正文", sidecar);

        // 重复 id 只嵌入首次出现的 wechatUrl, 不生成重复微信图片 (AD-11)
        assertThat(result.content())
                .isEqualTo("正文\n\n![原帖图片-1](https://mmbiz.qpic.cn/first.jpg)");
        assertThat(result.embeddedCount()).isEqualTo(1);
    }

    @Test
    void should_deduplicate_by_source_url_when_id_missing() {
        List<TweetMedia> sidecar = List.of(
                embeddablePhoto(null, "https://mmbiz.qpic.cn/same.jpg", "https://mmbiz.qpic.cn/w1"),
                embeddablePhoto(null, "https://mmbiz.qpic.cn/same.jpg", "https://mmbiz.qpic.cn/w2"));

        MarkdownMediaInserter.MarkdownMediaInsertionResult result =
                inserter.insert("正文", sidecar);

        // id 缺失时用 sourceUrl 兜底去重, 首个 wechatUrl 胜出
        assertThat(result.content())
                .isEqualTo("正文\n\n![原帖图片-1](https://mmbiz.qpic.cn/w1)");
        assertThat(result.embeddedCount()).isEqualTo(1);
    }

    @Test
    void should_not_deduplicate_distinct_ids_with_same_source_url() {
        List<TweetMedia> sidecar = List.of(
                embeddablePhoto("m1", "https://mmbiz.qpic.cn/same.jpg", "https://mmbiz.qpic.cn/w1"),
                embeddablePhoto("m2", "https://mmbiz.qpic.cn/same.jpg", "https://mmbiz.qpic.cn/w2"));

        MarkdownMediaInserter.MarkdownMediaInsertionResult result =
                inserter.insert("正文", sidecar);

        // id 存在且不同时, sourceUrl 相同也不去重 (id 优先)
        assertThat(result.embeddedCount()).isEqualTo(2);
        assertThat(result.content()).isEqualTo(
                "正文\n\n![原帖图片-1](https://mmbiz.qpic.cn/w1)"
                        + "\n\n![原帖图片-2](https://mmbiz.qpic.cn/w2)");
    }

    // ===== 输出边界 =====

    @Test
    void should_return_content_unchanged_when_sidecar_null_or_empty() {
        assertThat(inserter.insert("正文", null).content()).isEqualTo("正文");
        assertThat(inserter.insert("正文", List.of()).content()).isEqualTo("正文");
    }

    @Test
    void should_output_media_only_markdown_when_content_blank() {
        List<TweetMedia> sidecar = List.of(
                embeddablePhoto("m1", "https://mmbiz.qpic.cn/a.jpg"));

        MarkdownMediaInserter.MarkdownMediaInsertionResult result =
                inserter.insert(null, sidecar);

        assertThat(result.content())
                .isEqualTo("![原帖图片-1](https://mmbiz.qpic.cn/a.jpg)");
    }

    @Test
    void should_separate_multiple_images_when_content_blank() {
        List<TweetMedia> sidecar = List.of(
                embeddablePhoto("m1", "https://mmbiz.qpic.cn/a.jpg"),
                embeddablePhoto("m2", "https://mmbiz.qpic.cn/b.jpg"));

        MarkdownMediaInserter.MarkdownMediaInsertionResult result =
                inserter.insert("   ", sidecar);

        assertThat(result.content()).isEqualTo(
                "![原帖图片-1](https://mmbiz.qpic.cn/a.jpg)"
                        + "\n\n![原帖图片-2](https://mmbiz.qpic.cn/b.jpg)");
        assertThat(result.embeddedCount()).isEqualTo(2);
        assertThat(result.degradedCount()).isZero();
    }

    @Test
    void should_not_leak_source_url_local_path_or_raw_html() {
        TweetMedia media = TweetMedia.builder()
                .id("m1")
                .type(TweetMediaType.PHOTO)
                .sourceUrl("https://pbs.twimg.com/media/abc.jpg")
                .localPath("media/twitter/2026-08-30/123/1.jpg")
                .uploadStatus(MediaUploadStatus.UPLOADED)
                .publishability(PublishabilityStatus.PUBLISHABLE)
                .wechatUrl("https://mmbiz.qpic.cn/w1")
                .build();

        MarkdownMediaInserter.MarkdownMediaInsertionResult result =
                inserter.insert("正文", List.of(media));

        // AC 6: 输出只能是 Markdown image syntax + wechatUrl
        assertThat(result.content())
                .doesNotContain("pbs.twimg.com")
                .doesNotContain("media/twitter")
                .doesNotContain("<img");
        assertThat(result.content()).contains("![原帖图片-1](https://mmbiz.qpic.cn/w1)");
    }

    @Test
    void should_count_null_sidecar_entries_as_degraded() {
        // TweetMediaArchiveWriter null 保位语义: sidecar 可能含 null 元素
        java.util.ArrayList<TweetMedia> sidecar = new java.util.ArrayList<>();
        sidecar.add(null);
        sidecar.add(embeddablePhoto("m1", "https://mmbiz.qpic.cn/a.jpg"));

        MarkdownMediaInserter.MarkdownMediaInsertionResult result =
                inserter.insert("正文", sidecar);

        assertThat(result.embeddedCount()).isEqualTo(1);
        assertThat(result.degradedCount()).isEqualTo(1);
    }

    // ===== Story 10.11: VIDEO 候选 A 纯文本 mediaId 嵌入 =====

    @Test
    void should_append_video_media_id_as_plain_text_when_video_prepared() {
        MarkdownMediaInserter.MarkdownMediaInsertionResult result =
                inserter.insert("正文", List.of(preparedVideo("m2", "wxvid001mediaid")));

        // 候选 A 形态: 纯文本 mediaId 追加, 不输出 Markdown image/iframe/raw img
        assertThat(result.content()).isEqualTo("正文\n\n视频素材: wxvid001mediaid");
        assertThat(result.embeddedCount()).isEqualTo(1);
        assertThat(result.degradedCount()).isZero();
    }

    @Test
    void should_keep_photo_and_video_order_when_mixed() {
        List<TweetMedia> sidecar = List.of(
                embeddablePhoto("m1", "https://mmbiz.qpic.cn/a.jpg"),
                preparedVideo("m2", "wxvid001mediaid"));

        MarkdownMediaInserter.MarkdownMediaInsertionResult result =
                inserter.insert("正文", sidecar);

        assertThat(result.content()).isEqualTo(
                "正文\n\n![原帖图片-1](https://mmbiz.qpic.cn/a.jpg)"
                        + "\n\n视频素材: wxvid001mediaid");
        assertThat(result.embeddedCount()).isEqualTo(2);
    }

    /** PHOTO alt 序号用独立计数 — PHOTO/VIDEO 混排不断号 (embeddedCount 仍为总嵌入数). */
    @Test
    void should_number_photo_alt_by_photo_only_counter_when_mixed() {
        List<TweetMedia> sidecar = List.of(
                embeddablePhoto("m1", "https://mmbiz.qpic.cn/a.jpg"),
                preparedVideo("m2", "wxvid001mediaid"),
                embeddablePhoto("m3", "https://mmbiz.qpic.cn/b.jpg"));

        MarkdownMediaInserter.MarkdownMediaInsertionResult result =
                inserter.insert("正文", sidecar);

        assertThat(result.content()).isEqualTo(
                "正文\n\n![原帖图片-1](https://mmbiz.qpic.cn/a.jpg)"
                        + "\n\n视频素材: wxvid001mediaid"
                        + "\n\n![原帖图片-2](https://mmbiz.qpic.cn/b.jpg)");
        assertThat(result.embeddedCount()).isEqualTo(3);
    }

    @Test
    void should_not_embed_video_when_prepare_not_succeeded() {
        TweetMedia unprepared = preparedVideo("m2", "wxvid001mediaid").toBuilder()
                .wechatPrepare(MediaPhaseState.builder()
                        .status(MediaPhaseStatus.RETRY_SCHEDULED).attempt(1).build())
                .build();

        MarkdownMediaInserter.MarkdownMediaInsertionResult result =
                inserter.insert("正文", List.of(unprepared));

        assertThat(result.content()).isEqualTo("正文");
        assertThat(result.embeddedCount()).isZero();
        assertThat(result.degradedCount()).isEqualTo(1);
    }

    @Test
    void should_not_embed_video_when_media_id_missing() {
        TweetMedia noMediaId = preparedVideo("m2", null);

        MarkdownMediaInserter.MarkdownMediaInsertionResult result =
                inserter.insert("正文", List.of(noMediaId));

        assertThat(result.content()).isEqualTo("正文");
        assertThat(result.embeddedCount()).isZero();
        assertThat(result.degradedCount()).isEqualTo(1);
    }

    @Test
    void should_not_embed_blocked_video_even_when_prepared() {
        TweetMedia blocked = preparedVideo("m2", "wxvid001mediaid").toBuilder()
                .publishability(PublishabilityStatus.BLOCKED)
                .build();

        MarkdownMediaInserter.MarkdownMediaInsertionResult result =
                inserter.insert("正文", List.of(blocked));

        assertThat(result.content()).isEqualTo("正文");
        assertThat(result.embeddedCount()).isZero();
        assertThat(result.degradedCount()).isEqualTo(1);
    }

    @Test
    void should_deduplicate_prepared_video_by_id_keeping_first() {
        MarkdownMediaInserter.MarkdownMediaInsertionResult result =
                inserter.insert("正文", List.of(
                        preparedVideo("m2", "wxvid001mediaid"),
                        preparedVideo("m2", "wxvid002mediaid")));

        assertThat(result.content()).isEqualTo("正文\n\n视频素材: wxvid001mediaid");
        assertThat(result.embeddedCount()).isEqualTo(1);
        assertThat(result.degradedCount()).isEqualTo(1);
    }

    // ===== fixtures =====

    private static TweetMedia embeddablePhoto(String id, String wechatUrl) {
        return embeddablePhoto(id, "https://pbs.twimg.com/media/" + id + ".jpg", wechatUrl);
    }

    private static TweetMedia embeddablePhoto(String id, String sourceUrl, String wechatUrl) {
        return TweetMedia.builder()
                .id(id)
                .type(TweetMediaType.PHOTO)
                .sourceUrl(sourceUrl)
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .uploadStatus(MediaUploadStatus.UPLOADED)
                .publishability(PublishabilityStatus.PUBLISHABLE)
                .wechatUrl(wechatUrl)
                .build();
    }

    private static TweetMedia blockedPhoto(String id) {
        return TweetMedia.builder()
                .id(id)
                .type(TweetMediaType.PHOTO)
                .sourceUrl("https://pbs.twimg.com/media/" + id + ".jpg")
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .uploadStatus(MediaUploadStatus.UPLOADED)
                .publishability(PublishabilityStatus.BLOCKED)
                .wechatUrl("https://mmbiz.qpic.cn/" + id + ".jpg")
                .build();
    }

    private static TweetMedia photoPendingUpload(String id) {
        return TweetMedia.builder()
                .id(id)
                .type(TweetMediaType.PHOTO)
                .sourceUrl("https://pbs.twimg.com/media/" + id + ".jpg")
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .uploadStatus(MediaUploadStatus.PENDING)
                .publishability(PublishabilityStatus.PUBLISHABLE)
                .build();
    }

    private static TweetMedia video(String id) {
        return TweetMedia.builder()
                .id(id)
                .type(TweetMediaType.VIDEO)
                .sourceUrl("https://video.twimg.com/" + id + ".mp4")
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .uploadStatus(MediaUploadStatus.UPLOADED)
                .publishability(PublishabilityStatus.PUBLISHABLE)
                .wechatUrl("https://mmbiz.qpic.cn/" + id + ".jpg")
                .build();
    }

    /** Story 10.11: 已完成微信准备的 VIDEO (wechatPrepare=SUCCEEDED + mediaId 有值). */
    private static TweetMedia preparedVideo(String id, String wechatVideoMediaId) {
        return TweetMedia.builder()
                .id(id)
                .type(TweetMediaType.VIDEO)
                .sourceUrl("https://video.twimg.com/" + id + ".mp4")
                .downloadStatus(MediaDownloadStatus.DOWNLOADED)
                .uploadStatus(MediaUploadStatus.UPLOADED)
                .publishability(PublishabilityStatus.PUBLISHABLE)
                .wechatVideoMediaId(wechatVideoMediaId)
                .wechatPrepare(MediaPhaseState.builder()
                        .status(MediaPhaseStatus.SUCCEEDED).attempt(1).build())
                .build();
    }
}
