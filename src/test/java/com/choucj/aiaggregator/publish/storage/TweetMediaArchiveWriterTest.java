package com.choucj.aiaggregator.publish.storage;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.publish.storage.config.ArchiverProperties;
import com.choucj.aiaggregator.source.twitter.config.TwitterMediaConfig;
import com.choucj.aiaggregator.source.twitter.config.TwitterMediaProperties;
import com.choucj.aiaggregator.source.twitter.model.MediaDownloadStatus;
import com.choucj.aiaggregator.source.twitter.model.MediaUploadStatus;
import com.choucj.aiaggregator.source.twitter.model.PublishabilityStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 7.1: TweetMediaArchiveWriter 单元测试.
 *
 * <p>用真实 Writer + {@code @TempDir} (软失败测试范式 §1.7, 不 mock 整个 Writer);
 * IO 失败用「baseDirectory 指向文件」稳定触发 createDirectories 失败 (与 MarkdownArchiverTest 一致).
 */
class TweetMediaArchiveWriterTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @TempDir
    Path tempDir;

    private TwitterMediaProperties properties;
    private ArchiverProperties archiveProperties;
    private ObjectMapper objectMapper;
    private TweetMediaArchiveWriter writer;

    @BeforeEach
    void setUp() {
        properties = new TwitterMediaProperties();
        properties.setEnabled(true);
        properties.setBaseDirectory(tempDir.toString());
        archiveProperties = new ArchiverProperties();
        archiveProperties.setBaseDirectory(tempDir.toString());
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules(); // JavaTimeModule for LocalDateTime
        writer = new TweetMediaArchiveWriter(properties, archiveProperties, objectMapper);
    }

    private TweetMedia samplePhotoMedia(String id, String sourceUrl) {
        return TweetMedia.builder()
                .id(id)
                .type(TweetMediaType.PHOTO)
                .sourceUrl(sourceUrl)
                .previewImageUrl(sourceUrl)
                .order(0)
                .provider("scraper")
                .build();
    }

    // AC1: 目录确定性
    @Test
    void shouldResolveDeterministicArchiveDir() {
        LocalDateTime when = LocalDateTime.of(2026, 8, 2, 10, 0);

        Path dir = writer.resolveArchiveDir("2083615699260313955", when);

        Path expected = tempDir.resolve("media").resolve("twitter")
                .resolve("2026-08-02").resolve("2083615699260313955");
        assertThat(dir).isEqualTo(expected.normalize());
    }

    // AC1: publishedAt 决定日期段 (跨日重试归原日期)
    @Test
    void shouldUsePublishedAtForDateSegment() {
        Path dir1 = writer.resolveArchiveDir("id-a", LocalDateTime.of(2026, 7, 31, 23, 59));
        Path dir2 = writer.resolveArchiveDir("id-a", LocalDateTime.of(2026, 8, 1, 0, 1));

        assertThat(dir1.toString()).contains("2026-07-31");
        assertThat(dir2.toString()).contains("2026-08-01");
    }

    // AC1: publishedAt 为 null 时 fallback now()
    @Test
    void shouldFallbackToNowWhenPublishedAtNull() {
        Path dir = writer.resolveArchiveDir("id-x", null);

        String today = LocalDateTime.now().toLocalDate().format(DATE_FMT);
        assertThat(dir.toString()).contains(today);
    }

    // AC3 + AC4: write + read 往返, 字段完整
    @Test
    void shouldWriteAndReadSidecarRoundTrip() {
        LocalDateTime when = LocalDateTime.of(2026, 8, 2, 10, 0);
        TweetMedia m = samplePhotoMedia("m1", "https://x.com/p1.jpg");

        writer.writeSidecar("2083", when, List.of(m));

        Optional<MediaArchiveRecord> read = writer.readSidecar("2083", when);
        assertThat(read).isPresent();
        assertThat(read.get().getTweetId()).isEqualTo("2083");
        assertThat(read.get().getMedia()).hasSize(1);
        TweetMedia back = read.get().getMedia().get(0);
        assertThat(back.getId()).isEqualTo("m1");
        assertThat(back.getType()).isEqualTo(TweetMediaType.PHOTO);
        assertThat(back.getSourceUrl()).isEqualTo("https://x.com/p1.jpg");
        assertThat(back.getDownloadStatus()).isEqualTo(MediaDownloadStatus.PENDING);
        assertThat(back.getPublishability()).isEqualTo(PublishabilityStatus.UNKNOWN);
    }

    // AC3: sidecar JSON 含全部必需字段
    @Test
    void shouldSidecarContainAllRequiredFields() throws IOException {
        LocalDateTime when = LocalDateTime.of(2026, 8, 2, 10, 0);
        TweetMedia m = TweetMedia.builder()
                .id("m1").type(TweetMediaType.PHOTO)
                .sourceUrl("https://x.com/p1.jpg")
                .previewImageUrl("https://x.com/p1.jpg")
                .localPath("media/twitter/2026-08-02/2083/p1.jpg")
                .failureReason("HTTP 404")
                .build();

        writer.writeSidecar("2083", when, List.of(m));

        Path file = writer.resolveSidecarFile("2083", when);
        JsonNode root = objectMapper.readTree(Files.readString(file));
        assertThat(root.get("tweetId").asText()).isEqualTo("2083");
        assertThat(root.get("generatedAt")).isNotNull();
        JsonNode mediaNode = root.get("media").get(0);
        assertThat(mediaNode.get("sourceUrl").asText()).isEqualTo("https://x.com/p1.jpg");
        assertThat(mediaNode.get("type").asText()).isEqualTo("PHOTO");
        assertThat(mediaNode.get("previewImageUrl")).isNotNull();
        assertThat(mediaNode.get("localPath")).isNotNull();
        assertThat(mediaNode.get("downloadStatus")).isNotNull();
        assertThat(mediaNode.get("uploadStatus")).isNotNull();
        assertThat(mediaNode.get("publishability")).isNotNull();
        assertThat(mediaNode.get("failureReason").asText()).isEqualTo("HTTP 404");
    }

    // AC3: @JsonInclude(NON_NULL) — null 字段不出现在 sidecar
    @Test
    void shouldOmitNullFieldsFromSidecar() throws IOException {
        LocalDateTime when = LocalDateTime.of(2026, 8, 2, 10, 0);
        TweetMedia m = TweetMedia.builder().id("m1").type(TweetMediaType.PHOTO).build(); // localPath/failureReason=null

        writer.writeSidecar("2083", when, List.of(m));

        JsonNode mediaNode = objectMapper.readTree(Files.readString(writer.resolveSidecarFile("2083", when)))
                .get("media").get(0);
        assertThat(mediaNode.has("localPath")).isFalse();
        assertThat(mediaNode.has("failureReason")).isFalse();
    }

    // AC6: tweetId 路径穿越防御
    @Test
    void shouldRejectTweetIdWithTraversalChars() {
        assertThatThrownBy(() -> writer.resolveArchiveDir("../etc", LocalDateTime.now()))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("tweetId 非法");
    }

    @Test
    void shouldRejectBlankTweetId() {
        assertThatThrownBy(() -> writer.resolveArchiveDir("", LocalDateTime.now()))
                .isInstanceOf(NonRetryableException.class);
    }

    @Test
    void shouldRejectTweetIdWithSlash() {
        assertThatThrownBy(() -> writer.resolveArchiveDir("2083/../../etc", LocalDateTime.now()))
                .isInstanceOf(NonRetryableException.class);
    }

    // AC5: IO 失败映射 NonRetryable (baseDirectory 指向文件)
    @Test
    void shouldThrowNonRetryableOnIoFailure() throws IOException {
        Path occupied = tempDir.resolve("occupied-base");
        Files.writeString(occupied, "not a directory");
        properties.setBaseDirectory(occupied.toString());
        writer = new TweetMediaArchiveWriter(properties, archiveProperties, objectMapper);

        assertThatThrownBy(() -> writer.writeSidecar("2083", LocalDateTime.of(2026, 8, 2, 10, 0), List.of()))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("目录创建失败")
                .hasMessageContaining("tweetId=2083");
    }

    // AC4: read 不存在返回 empty (fail-open)
    @Test
    void shouldReadReturnEmptyWhenMissing() {
        Optional<MediaArchiveRecord> read = writer.readSidecar("never-archived", LocalDateTime.now());
        assertThat(read).isEmpty();
    }

    // AC4: updateMedia read-modify-write, 不覆盖其他项
    @Test
    void shouldUpdateSingleMediaWithoutOverwritingOthers() {
        LocalDateTime when = LocalDateTime.of(2026, 8, 2, 10, 0);
        TweetMedia m1 = samplePhotoMedia("m1", "u1");
        TweetMedia m2 = samplePhotoMedia("m2", "u2");
        writer.writeSidecar("2083", when, List.of(m1, m2));

        boolean updated = writer.updateMedia("2083", when, "m2", original ->
                original.toBuilder().localPath("downloaded/m2.jpg").build());

        assertThat(updated).isTrue();
        MediaArchiveRecord back = writer.readSidecar("2083", when).orElseThrow();
        assertThat(back.getMedia()).hasSize(2);
        assertThat(back.getMedia().get(0).getId()).isEqualTo("m1");
        assertThat(back.getMedia().get(0).getLocalPath()).isNull(); // 未被覆盖
        assertThat(back.getMedia().get(1).getId()).isEqualTo("m2");
        assertThat(back.getMedia().get(1).getLocalPath()).isEqualTo("downloaded/m2.jpg");
    }

    // AC4: updateMedia mediaId 未命中返回 false
    @Test
    void shouldReturnFalseWhenMediaIdNotFound() {
        LocalDateTime when = LocalDateTime.of(2026, 8, 2, 10, 0);
        writer.writeSidecar("2083", when, List.of(samplePhotoMedia("m1", "u1")));

        boolean updated = writer.updateMedia("2083", when, "nonexistent", m -> m);

        assertThat(updated).isFalse();
    }

    @Test
    void shouldReturnFalseWhenUpdatingAndSidecarMissing() {
        boolean updated = writer.updateMedia("no-sidecar", LocalDateTime.now(), "m1", m -> m);
        assertThat(updated).isFalse();
    }

    // AC5: datePattern 含 LocalDate 不支持的时间字段 (HH) 在构造期抛 NonRetryable (照搬 MarkdownArchiverTest 模式)
    @Test
    void shouldRejectIllegalDatePatternAtConstruction() {
        properties.setDatePattern("yyyy-MM-dd-HH"); // 字符集合法, 但 LocalDate.format 不支持 HH
        assertThatThrownBy(() -> new TweetMediaArchiveWriter(properties, archiveProperties, objectMapper))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("date-pattern 配置非法");
    }

    // AC8: 集成型开关 — enabled=false 时 Bean 不注册
    @Test
    void shouldNotRegisterWriterBeanWhenDisabled() {
        runnerForEnabled(false).run(ctx -> assertThat(ctx).doesNotHaveBean(TweetMediaArchiveWriter.class));
    }

    // AC8: enabled=true 时 Bean 注册
    @Test
    void shouldRegisterWriterBeanWhenEnabled() {
        runnerForEnabled(true).run(ctx -> assertThat(ctx).hasSingleBean(TweetMediaArchiveWriter.class));
    }

    private ApplicationContextRunner runnerForEnabled(boolean enabled) {
        TwitterMediaProperties props = new TwitterMediaProperties();
        ArchiverProperties archiverProps = new ArchiverProperties();
        return new ApplicationContextRunner()
                .withBean(TwitterMediaProperties.class, () -> props)
                .withBean(ArchiverProperties.class, () -> archiverProps)
                .withBean(ObjectMapper.class, () -> {
                    ObjectMapper om = new ObjectMapper();
                    om.findAndRegisterModules();
                    return om;
                })
                .withUserConfiguration(TweetMediaArchiveWriter.class)
                .withPropertyValues("twitter.media.enabled=" + enabled);
    }

    // AC7/N4: IO 失败 message 不含媒体 sourceUrl (不泄漏内容)
    @Test
    void shouldNotLeakMediaContentInExceptionMessage() throws IOException {
        Path occupied = tempDir.resolve("occupied-base-2");
        Files.writeString(occupied, "not a directory");
        properties.setBaseDirectory(occupied.toString());
        writer = new TweetMediaArchiveWriter(properties, archiveProperties, objectMapper);

        TweetMedia m = samplePhotoMedia("m1", "https://secret.example.com/leak.jpg");
        try {
            writer.writeSidecar("2083", LocalDateTime.of(2026, 8, 2, 10, 0), List.of(m));
        } catch (NonRetryableException e) {
            assertThat(e.getMessage()).doesNotContain("secret.example.com");
            assertThat(e.getMessage()).doesNotContain("leak.jpg");
        }
    }

    // AC6 + review patch-5: tweetId 长度上限 (≤64), 防止超长突破 OS 路径组件限制
    @Test
    void shouldRejectOverlongTweetId() {
        String overlong = "a".repeat(65); // 65 字符, 超过 64 上限
        assertThatThrownBy(() -> writer.resolveArchiveDir(overlong, LocalDateTime.now()))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("tweetId 非法");
    }

    @Test
    void shouldAcceptTweetIdAtMaxLength() {
        String max = "a".repeat(64);
        Path dir = writer.resolveArchiveDir(max, LocalDateTime.of(2026, 8, 2, 10, 0));
        assertThat(dir.getFileName().toString()).hasSize(64);
    }

    // review patch-6: null mediaId / mutation 快速失败 (编程错误, 不再静默返回 false)
    @Test
    void shouldThrowWhenMediaIdNull() {
        assertThatThrownBy(() -> writer.updateMedia("2083", LocalDateTime.now(), null, m -> m))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("mediaId");
    }

    @Test
    void shouldThrowWhenMutationNull() {
        assertThatThrownBy(() -> writer.updateMedia("2083", LocalDateTime.now(), "m1", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("mutation");
    }

    // review patch-3 + patch-8: 损坏 sidecar (JSON 解析失败) 与"文件不存在"区分,
    // 仍 fail-open 返回 empty 不抛异常 (重下载幂等, 安全降级)
    @Test
    void shouldReturnEmptyWhenSidecarCorrupted() throws IOException {
        LocalDateTime when = LocalDateTime.of(2026, 8, 2, 10, 0);
        Path file = writer.resolveSidecarFile("2083", when);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ this is not valid JSON }}}");

        Optional<MediaArchiveRecord> read = writer.readSidecar("2083", when);

        assertThat(read).isEmpty(); // fail-open, 不抛
    }

    // review patch-4: 旧/外部 sidecar 缺生命周期字段时, readSidecar backfill @Builder.Default 默认值
    @Test
    void shouldBackfillDefaultsWhenSidecarMissingStatusFields() throws IOException {
        LocalDateTime when = LocalDateTime.of(2026, 8, 2, 10, 0);
        Path file = writer.resolveSidecarFile("2083", when);
        Files.createDirectories(file.getParent());
        // 手写缺 downloadStatus/uploadStatus/publishability 的 sidecar (模拟旧 schema)
        Files.writeString(file, "{\"tweetId\":\"2083\",\"generatedAt\":\"2026-08-02T10:00:00\","
                + "\"media\":[{\"id\":\"m1\",\"type\":\"PHOTO\",\"sourceUrl\":\"https://x.com/p.jpg\"}]}");

        MediaArchiveRecord record = writer.readSidecar("2083", when).orElseThrow();
        TweetMedia m = record.getMedia().get(0);
        assertThat(m.getDownloadStatus()).isEqualTo(MediaDownloadStatus.PENDING);
        assertThat(m.getUploadStatus()).isEqualTo(MediaUploadStatus.PENDING);
        assertThat(m.getPublishability()).isEqualTo(PublishabilityStatus.UNKNOWN);
    }

    // review patch (recheck): updateMedia 防护 null 媒体列表 — backfillDefaults 不修复 list 本身为 null
    @Test
    void shouldReturnFalseWhenMediaListNull() throws IOException {
        LocalDateTime when = LocalDateTime.of(2026, 8, 2, 10, 0);
        Path file = writer.resolveSidecarFile("2083", when);
        Files.createDirectories(file.getParent());
        // 手写 media=null 的 sidecar (极端退化情况)
        Files.writeString(file, "{\"tweetId\":\"2083\",\"generatedAt\":\"2026-08-02T10:00:00\",\"media\":null}");

        boolean updated = writer.updateMedia("2083", when, "m1", m -> m);
        assertThat(updated).isFalse();
    }

    // review patch (recheck): updateMedia 防护 null 元素 — for-each 不因 null 元素 NPE
    @Test
    void shouldSkipNullMediaElementsInUpdateMedia() throws IOException {
        LocalDateTime when = LocalDateTime.of(2026, 8, 2, 10, 0);
        Path file = writer.resolveSidecarFile("2083", when);
        Files.createDirectories(file.getParent());
        // 手写含 null 元素的 sidecar (退化情况)
        Files.writeString(file, "{\"tweetId\":\"2083\",\"generatedAt\":\"2026-08-02T10:00:00\","
                + "\"media\":[null,{\"id\":\"m1\",\"type\":\"PHOTO\",\"sourceUrl\":\"u1\"},null]}");

        boolean updated = writer.updateMedia("2083", when, "m1", m ->
                m.toBuilder().localPath("downloaded/m1.jpg").build());
        assertThat(updated).isTrue();
    }

    // review patch-2: 并发 updateMedia 不丢失更新 (read-modify-write 加目录锁后串行)
    // 无锁时会因 TRUNCATE_EXISTING 全量重写交错而丢失部分 localPath 回写
    @Test
    void shouldNotLoseUpdateUnderConcurrentUpdateMedia() throws InterruptedException {
        LocalDateTime when = LocalDateTime.of(2026, 8, 2, 10, 0);
        int n = 20;
        List<TweetMedia> initial = new ArrayList<>();
        IntStream.range(0, n).forEach(i -> initial.add(samplePhotoMedia("m" + i, "u" + i)));
        writer.writeSidecar("2083", when, initial);

        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            for (int i = 0; i < n; i++) {
                final String mediaId = "m" + i;
                final String path = "downloaded/" + mediaId + ".jpg";
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        writer.updateMedia("2083", when, mediaId, original ->
                                original.toBuilder().localPath(path).build());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            ready.await();
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        MediaArchiveRecord back = writer.readSidecar("2083", when).orElseThrow();
        assertThat(back.getMedia()).hasSize(n);
        for (TweetMedia m : back.getMedia()) {
            assertThat(m.getLocalPath()).as("media %s 的 localPath 不应丢失", m.getId())
                    .isEqualTo("downloaded/" + m.getId() + ".jpg");
        }
    }

    // ===== Epic 8 retro 修复 (2026-08-29): sidecar 可靠性 hardening #1/#2/#3 =====

    // 修复 #2: updateMedia null 元素保位 — 持久化列表不因 null 元素收缩,
    // 防止后续按 index 回写错位 (上传 B 的 URL 写到 A 名下 / miss 后 URL 丢失)
    @Test
    void should_preserve_null_element_positions_when_update_media_writes_back() throws IOException {
        LocalDateTime when = LocalDateTime.of(2026, 8, 2, 10, 0);
        Path file = writer.resolveSidecarFile("2083", when);
        Files.createDirectories(file.getParent());
        // 手写含 null 元素的退化 sidecar (与既有 shouldSkipNullMediaElementsInUpdateMedia 同构造)
        Files.writeString(file, "{\"tweetId\":\"2083\",\"generatedAt\":\"2026-08-02T10:00:00\","
                + "\"media\":[null,{\"id\":\"m1\",\"type\":\"PHOTO\",\"sourceUrl\":\"u1\"},null]}");

        boolean updated = writer.updateMedia("2083", when, "m1", m ->
                m.toBuilder().localPath("downloaded/m1.jpg").build());

        assertThat(updated).isTrue();
        List<TweetMedia> back = writer.readSidecar("2083", when).orElseThrow().getMedia();
        assertThat(back).hasSize(3);        // 列表不收缩
        assertThat(back.get(0)).isNull();   // null 元素位置保留
        assertThat(back.get(1).getId()).isEqualTo("m1");
        assertThat(back.get(1).getLocalPath()).isEqualTo("downloaded/m1.jpg");
        assertThat(back.get(2)).isNull();
    }

    // 修复 #3: updateMedia 重复 id 只突变首个命中 — 同 id 多项不再被同一 mutation 全量覆盖
    @Test
    void should_mutate_only_first_match_when_duplicate_media_ids_exist() {
        LocalDateTime when = LocalDateTime.of(2026, 8, 2, 10, 0);
        TweetMedia dup1 = samplePhotoMedia("dup", "https://x.com/first.jpg");
        TweetMedia dup2 = samplePhotoMedia("dup", "https://x.com/second.jpg");
        TweetMedia other = samplePhotoMedia("other", "u-other");
        writer.writeSidecar("2083", when, List.of(dup1, dup2, other));

        boolean updated = writer.updateMedia("2083", when, "dup", m ->
                m.toBuilder().wechatUrl("https://mmbiz.qpic.cn/wx1").build());

        assertThat(updated).isTrue();
        List<TweetMedia> back = writer.readSidecar("2083", when).orElseThrow().getMedia();
        assertThat(back).hasSize(3);
        assertThat(back.get(0).getWechatUrl()).isEqualTo("https://mmbiz.qpic.cn/wx1"); // 首个命中被更新
        assertThat(back.get(1).getWechatUrl()).isNull(); // 同 id 第二项不被覆盖
        assertThat(back.get(1).getSourceUrl()).isEqualTo("https://x.com/second.jpg");  // 原字段保留
        assertThat(back.get(2).getWechatUrl()).isNull();  // 无关项不受影响
    }

    // 修复 #1: 原子写 — 成功写入后目录内只有 media.json, 不残留 .tmp 临时文件
    @Test
    void should_not_leave_temp_file_when_write_succeeds() throws IOException {
        LocalDateTime when = LocalDateTime.of(2026, 8, 2, 10, 0);
        writer.writeSidecar("2083", when, List.of(samplePhotoMedia("m1", "u1")));

        Path dir = writer.resolveArchiveDir("2083", when);
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .containsExactly("media.json");
        }
    }

    // 修复 #1: 原子写 — 临时文件写入失败时旧 sidecar 保持完整 (不被半写坏, 崩溃/失败留旧文件)
    @Test
    void should_keep_existing_sidecar_intact_when_temp_write_fails() throws IOException {
        LocalDateTime when = LocalDateTime.of(2026, 8, 2, 10, 0);
        // 先写一个完整的旧 sidecar
        writer.writeSidecar("2083", when, List.of(samplePhotoMedia("m1", "u1")));
        Path file = writer.resolveSidecarFile("2083", when);
        String oldContent = Files.readString(file);
        // 预置 .tmp 为目录 → 临时文件写入必然失败 (对目录 Files.write 抛 IOException)
        Files.createDirectories(file.resolveSibling("media.json.tmp"));

        assertThatThrownBy(() -> writer.writeSidecar("2083", when, List.of(samplePhotoMedia("m2", "u2"))))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("sidecar 写入失败");

        // 旧 sidecar 逐字节完整保留
        assertThat(Files.readString(file)).isEqualTo(oldContent);
    }
}
