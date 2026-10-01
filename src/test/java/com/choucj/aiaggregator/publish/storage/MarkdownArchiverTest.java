package com.choucj.aiaggregator.publish.storage;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ContentGenerationMode;
import com.choucj.aiaggregator.monitoring.TaskMetrics;
import com.choucj.aiaggregator.publish.status.ArticleStatus;
import com.choucj.aiaggregator.publish.storage.config.ArchiverProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Story 2.5 {@link MarkdownArchiver} 单测.
 *
 * <p>覆盖 AC-1 ~ AC-8 — 路径规则 / 同日追加 + 分隔符 / 元数据块格式 / IO 异常映射 /
 * 自动创建目录 / 幂等查重 / 配置开关 / 日志规范 (W11).
 *
 * <p>使用 {@link TempDir} 注入临时目录, 每 test 独立目录避免相互污染.
 *
 * <p>Story 10.6 增量: 跨日幂等(快照命中不追加新日期文件)/快照缺失补落/快照读取失败
 * fail-closed 不追加/幂等指标旁路化. 注意: 经 {@link ArticleArchiveRepository} 的用例
 * 必须使用 {@code tw-}/{@code gh-} 前缀 articleId(仓库侧 {@code validateArticleId} 校验).
 */
@ExtendWith(OutputCaptureExtension.class)
class MarkdownArchiverTest {

    @TempDir
    Path tempDir;

    private ArchiverProperties properties;
    private MarkdownArchiver archiver;

    @BeforeEach
    void setUp() {
        properties = new ArchiverProperties();
        properties.setBaseDirectory(tempDir.resolve("archive").toString());
        properties.setEnabled(true);
        properties.setDatePattern("yyyy-MM-dd");
        properties.setFileSuffix(".md");
        properties.setSeparator("\n\n---\n\n");
        archiver = new MarkdownArchiver(properties);
        archiver.initFormatter();
    }

    private Article sampleArticle(String id, String title, LocalDateTime createdAt) {
        return Article.builder()
                .id(id)
                .title(title)
                .content("这是正文内容.")
                .digest("摘要")
                .source("来源:@karpathy")
                .aiGenerated(true)
                .createdAt(createdAt)
                .innovationScore(8)
                .originalUrl("https://twitter.com/karpathy/status/" + id)
                .build();
    }

    // AC-1, AC-3: 首次写入 + 路径规则 + 元数据格式

    @Test
    void shouldCreateFileOnFirstPublish() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 28, 15, 30);
        Article article = sampleArticle("abc-123", "测试标题", createdAt);

        archiver.publish(article);

        Path expected = tempDir.resolve("archive").resolve("2026-06-28.md");
        assertThat(expected).exists();
        String content = readAll(expected);
        assertThat(content).contains("<!-- article-id: abc-123 -->");
        assertThat(content).contains("## 测试标题");
        assertThat(content).contains("- **日期**: 2026-06-28 15:30");
        assertThat(content).contains("- **来源**: 来源:@karpathy");
        assertThat(content).contains("- **AI 生成**: 是");
        assertThat(content).contains("- **原文链接**: https://twitter.com/karpathy/status/abc-123");
        assertThat(content).contains("这是正文内容.");
        assertThat(content).contains("> 本文由 AI 辅助生成");
    }

    // AC-1: 路径规则使用 createdAt 非当前日期

    @Test
    void shouldResolveFileByCreatedAtNotNow() {
        LocalDateTime pastDate = LocalDateTime.of(2025, 1, 15, 9, 0);
        Article article = sampleArticle("id-past", "历史文章", pastDate);

        archiver.publish(article);

        Path expected = tempDir.resolve("archive").resolve("2025-01-15.md");
        assertThat(expected).exists();
    }

    // AC-2: 同日追加到同一文件

    @Test
    void shouldAppendToSameFileForSameDay() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 28, 10, 0);
        Article a1 = sampleArticle("id-1", "文章一", createdAt);
        Article a2 = sampleArticle("id-2", "文章二", createdAt);

        archiver.publish(a1);
        archiver.publish(a2);

        Path file = tempDir.resolve("archive").resolve("2026-06-28.md");
        String content = readAll(file);
        assertThat(content).contains("<!-- article-id: id-1 -->");
        assertThat(content).contains("<!-- article-id: id-2 -->");
        assertThat(content).contains("## 文章一");
        assertThat(content).contains("## 文章二");
    }

    // AC-2: 分隔符正确

    @Test
    void shouldUseSeparatorBetweenBlocks() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 28, 10, 0);
        archiver.publish(sampleArticle("id-1", "文章一", createdAt));
        archiver.publish(sampleArticle("id-2", "文章二", createdAt));

        Path file = tempDir.resolve("archive").resolve("2026-06-28.md");
        String content = readAll(file);
        assertThat(content).contains("\n\n---\n\n");
    }

    // AC-2: 不同日生成不同文件

    @Test
    void shouldCreateDifferentFilesForDifferentDays() {
        archiver.publish(sampleArticle("id-1", "文章一", LocalDateTime.of(2026, 6, 28, 10, 0)));
        archiver.publish(sampleArticle("id-2", "文章二", LocalDateTime.of(2026, 6, 29, 10, 0)));

        assertThat(tempDir.resolve("archive").resolve("2026-06-28.md")).exists();
        assertThat(tempDir.resolve("archive").resolve("2026-06-29.md")).exists();
    }

    // AC-3: originalUrl null 时省略原文链接行

    @Test
    void shouldOmitOriginalUrlLineWhenNull() {
        Article article = Article.builder()
                .id("id-null-url")
                .title("无链接文章")
                .content("正文")
                .source("来源:@test")
                .aiGenerated(true)
                .createdAt(LocalDateTime.of(2026, 6, 28, 10, 0))
                .originalUrl(null)
                .build();

        archiver.publish(article);

        Path file = tempDir.resolve("archive").resolve("2026-06-28.md");
        String content = readAll(file);
        assertThat(content).doesNotContain("原文链接");
    }

    // AC-3: aiGenerated=false 时不追加 AI 声明

    @Test
    void shouldNotAppendAiDisclaimerWhenNotAiGenerated() {
        Article article = Article.builder()
                .id("id-no-ai")
                .title("人工文章")
                .content("正文")
                .source("来源:@test")
                .aiGenerated(false)
                .createdAt(LocalDateTime.of(2026, 6, 28, 10, 0))
                .build();

        archiver.publish(article);

        Path file = tempDir.resolve("archive").resolve("2026-06-28.md");
        String content = readAll(file);
        assertThat(content).contains("- **AI 生成**: 否");
        assertThat(content).doesNotContain("本文由 AI 辅助生成");
    }

    // AC-5: 自动创建多级目录

    @Test
    void shouldCreateNestedDirectories() {
        properties.setBaseDirectory(tempDir.resolve("a/b/c/deep").toString());
        archiver = new MarkdownArchiver(properties);
        archiver.initFormatter();

        archiver.publish(sampleArticle("id-1", "深路径", LocalDateTime.of(2026, 6, 28, 10, 0)));

        assertThat(tempDir.resolve("a/b/c/deep").resolve("2026-06-28.md")).exists();
    }

    // AC-5: 目录已存在幂等不报错

    @Test
    void shouldNotFailWhenDirectoryAlreadyExists() {
        Path dir = tempDir.resolve("archive");
        // 第一次创建
        archiver.publish(sampleArticle("id-1", "首篇", LocalDateTime.of(2026, 6, 28, 10, 0)));
        assertThat(dir).exists();
        // 第二次 — 目录已存在
        archiver.publish(sampleArticle("id-2", "次篇", LocalDateTime.of(2026, 6, 28, 11, 0)));
        Path file = dir.resolve("2026-06-28.md");
        String content = readAll(file);
        assertThat(content).contains("<!-- article-id: id-1 -->");
        assertThat(content).contains("<!-- article-id: id-2 -->");
    }

    // AC-6: 幂等 — 相同 articleId 跳过写入

    @Test
    void shouldSkipWhenArticleIdAlreadyArchived() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 28, 10, 0);
        Article article = sampleArticle("dup-id", "重复文章", createdAt);

        archiver.publish(article);
        // 第二次 — 同 id
        archiver.publish(article);

        Path file = tempDir.resolve("archive").resolve("2026-06-28.md");
        String content = readAll(file);
        // 只应出现一次 article-id 标记
        assertThat(content.split("<!-- article-id: dup-id -->").length).isEqualTo(2);
    }

    // AC-6: 相同 articleId 调用 log.warn

    @Test
    void shouldLogWarnWhenArticleIdAlreadyArchived(CapturedOutput output) {
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 28, 10, 0);
        Article article = sampleArticle("dup-id-warn", "重复文章", createdAt);

        archiver.publish(article);
        archiver.publish(article);

        assertThat(output.getOut()).contains("文章已归档, 跳过");
        assertThat(output.getOut()).contains("dup-id-warn");
    }

    // AC-6: 不同 articleId 不误判

    @Test
    void shouldNotSkipForDifferentArticleId() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 28, 10, 0);
        archiver.publish(sampleArticle("id-A", "文章A", createdAt));
        archiver.publish(sampleArticle("id-B", "文章B", createdAt));

        Path file = tempDir.resolve("archive").resolve("2026-06-28.md");
        String content = readAll(file);
        assertThat(content).contains("<!-- article-id: id-A -->");
        assertThat(content).contains("<!-- article-id: id-B -->");
    }

    // AC-4: IO 异常映射 NonRetryableException (目录创建失败路径)

    @Test
    void shouldThrowNonRetryableOnIoFailure() throws IOException {
        // 用“目标目录位置已被普通文件占用”稳定触发 createDirectories 失败，避免依赖 POSIX 权限位。
        Path occupiedPath = tempDir.resolve("occupied-base-directory");
        Files.writeString(occupiedPath, "not a directory");
        properties.setBaseDirectory(occupiedPath.toString());
        archiver = new MarkdownArchiver(properties);
        archiver.initFormatter();

        assertThatThrownBy(() ->
                archiver.publish(sampleArticle("id-1", "测试",
                        LocalDateTime.of(2026, 6, 28, 10, 0))))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("归档目录创建失败")
                .hasMessageContaining("articleId=id-1");
    }

    /**
     * Story 2.5 第二轮代码审查 Patch-8: datePattern 含 LocalDate 不支持的时间字段 (HH/mm/ss)
     * 应在 initFormatter 启动期抛 NonRetryableException, 而非延迟到 publish() 运行时
     * 抛 UnsupportedTemporalTypeException.
     */
    @Test
    void shouldRejectDatePatternWithTimeFieldsAtInit() {
        properties.setDatePattern("yyyy-MM-dd-HH"); // 通过 @Pattern 字符集 + DateTimeFormatter 构造, 但 LocalDate 不支持 HH
        archiver = new MarkdownArchiver(properties);

        assertThatThrownBy(() -> archiver.initFormatter())
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("归档 datePattern 配置非法");
    }

    // AC-4: 异常 message 不含正文 (N4 模式)

    @Test
    void shouldNotIncludeContentInExceptionMessage() {
        // 直接调用 appendToFile 触发异常路径 — 写入不存在的目录
        Path invalidPath = tempDir.resolve("nonexistent-deep").resolve("file.md");
        String sensitiveContent = "敏感正文不应该泄漏到日志";

        assertThatThrownBy(() ->
                archiver.appendToFile(invalidPath, sensitiveContent, "id-1"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageNotContaining(sensitiveContent);
    }

    // AC-4: 异常 message 含 articleId 和文件路径

    @Test
    void shouldIncludeArticleIdAndPathInExceptionMessage() {
        Path invalidPath = tempDir.resolve("no-such-dir").resolve("file.md");
        assertThatThrownBy(() ->
                archiver.appendToFile(invalidPath, "内容", "my-article-id"))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("articleId=my-article-id")
                .hasMessageContaining(invalidPath.toString());
    }

    // AC-8: 成功时 log.info 含 articleId + 文件 + 大小

    @Test
    void shouldLogInfoWithKeyIdentifiersOnSuccess(CapturedOutput output) {
        archiver.publish(sampleArticle("log-id", "日志测试",
                LocalDateTime.of(2026, 6, 28, 10, 0)));

        assertThat(output.getOut()).contains("归档成功");
        assertThat(output.getOut()).contains("articleId=log-id");
        assertThat(output.getOut()).contains("大小=");
        assertThat(output.getOut()).contains("bytes");
    }

    // AC-1: 路径 normalize 防穿越

    @Test
    void shouldNormalizeBaseDirectoryPath() {
        properties.setBaseDirectory(tempDir.toString() + "/../" + tempDir.getFileName() + "/archive-norm");
        archiver = new MarkdownArchiver(properties);
        archiver.initFormatter();

        Path resolved = archiver.resolveArchiveFile(LocalDateTime.of(2026, 6, 28, 10, 0));

        assertThat(resolved.toString()).doesNotContain("..");
    }

    // buildArticleBlock: 完整字段验证

    @Test
    void shouldBuildFullArticleBlock() {
        Article article = sampleArticle("full-id", "完整字段标题",
                LocalDateTime.of(2026, 6, 28, 15, 30));

        String block = archiver.buildArticleBlock(article);

        assertThat(block).startsWith("<!-- article-id: full-id -->");
        assertThat(block).contains("## 完整字段标题");
        assertThat(block).contains("- **日期**: 2026-06-28 15:30");
        assertThat(block).contains("- **来源**: 来源:@karpathy");
        assertThat(block).contains("- **AI 生成**: 是");
        assertThat(block).contains("- **原文链接**:");
        assertThat(block).contains("这是正文内容.");
        assertThat(block).contains("> 本文由 AI 辅助生成");
        assertThat(block).endsWith("\n\n---\n\n");
    }

    // buildArticleBlock: 标题含特殊 Markdown 字符不破坏结构

    @Test
    void shouldHandleTitleWithSpecialMarkdownChars() {
        Article article = Article.builder()
                .id("special-id")
                .title("标题含 [链接] 和 # 符号")
                .content("正文")
                .source("来源:@test")
                .aiGenerated(true)
                .createdAt(LocalDateTime.of(2026, 6, 28, 10, 0))
                .build();

        String block = archiver.buildArticleBlock(article);

        assertThat(block).contains("## 标题含 [链接] 和 # 符号");
    }

    // truncateForLog: R3-1 修复 — 返回值 ≤ max codepoint

    @Test
    void shouldTruncateForLogRespectingMaxBudget() {
        String longTitle = "a".repeat(100);
        String truncated = MarkdownArchiver.truncateForLog(longTitle, 50);

        assertThat(truncated.codePointCount(0, truncated.length())).isLessThanOrEqualTo(50);
        assertThat(truncated).endsWith("...");
    }

    @Test
    void shouldNotTruncateShortStringForLog() {
        String shortTitle = "短标题";
        assertThat(MarkdownArchiver.truncateForLog(shortTitle, 50)).isEqualTo("短标题");
    }

    @Test
    void shouldHandleNullForLog() {
        assertThat(MarkdownArchiver.truncateForLog(null, 50)).isEmpty();
    }

    // 边界: article null 抛 NPE

    @Test
    void shouldThrowNpeWhenArticleIsNull() {
        assertThatThrownBy(() -> archiver.publish(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("article 不能为 null");
    }

    // 边界: article.id null 抛 NPE

    @Test
    void shouldThrowNpeWhenArticleIdIsNull() {
        Article article = Article.builder()
                .title("无 ID")
                .content("正文")
                .source("来源:@test")
                .createdAt(LocalDateTime.of(2026, 6, 28, 10, 0))
                .build();

        assertThatThrownBy(() -> archiver.publish(article))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("article.id 不能为 null");
    }

    private String readAll(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ===== Story 8.6 Task 6.2: 生成模式行 + 媒体审计段 (AC4/AC5/D-E) =====

    @Test
    void should_contain_generation_mode_line_and_media_audit_table_when_preserve_original() {
        String auditTable = "#### 媒体审计 (media.json sidecar 权威状态)\n\n"
                + "| # | 类型 | 下载状态 | 上传状态 | 可发布性 | 微信 URL | 本地路径 | 失败原因 |\n"
                + "|---|---|---|---|---|---|---|---|\n"
                + "| 1 | PHOTO | DOWNLOADED | UPLOADED | PUBLISHABLE | https://mmbiz.qpic.cn/mmbiz/x "
                + "| media/twitter/2026-08-29/123/photo-1.jpg | - |\n";
        Article article = preserveArticle("tw-123", "原帖复现标题", auditTable);

        archiver.publish(article);

        Path expected = tempDir.resolve("archive")
                .resolve(article.getCreatedAt().toLocalDate() + ".md");
        String content = readAll(expected);
        // 模式行 (AC4)
        assertThat(content).contains("- **生成模式**: 原帖复现");
        // 媒体审计表 verbatim 插入 (D-E), 位于 content 之后、AI 声明之前 — aiGenerated=false 无 AI 声明
        assertThat(content).contains(auditTable);
        assertThat(content.indexOf("<p>原帖正文</p>")).isLessThan(content.indexOf("媒体审计"));
        assertThat(content).doesNotContain("本文由 AI 辅助生成");
    }

    @Test
    void should_contain_generation_mode_line_only_when_rewrite() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 28, 15, 30);
        Article article = sampleArticle("rewrite-mode-id", "改写文章", createdAt);

        archiver.publish(article);

        String content = readAll(tempDir.resolve("archive").resolve("2026-06-28.md"));
        // REWRITE 仅新增模式行 (AC4: 其余格式不变), 不插媒体审计段
        assertThat(content).contains("- **生成模式**: AI 改写");
        assertThat(content).doesNotContain("媒体审计");
    }

    @Test
    void should_not_insert_media_audit_section_when_media_audit_markdown_is_null() {
        Article article = preserveArticle("tw-null-audit", "无审计表", null);

        archiver.publish(article);

        Path expected = tempDir.resolve("archive")
                .resolve(article.getCreatedAt().toLocalDate() + ".md");
        String content = readAll(expected);
        assertThat(content).contains("- **生成模式**: 原帖复现");
        assertThat(content).doesNotContain("媒体审计");
    }

    // ===== Story 9.1 Task 5: AI 改写+媒体 模式标签 + 审计位置 (AC 1, 7, 8) =====

    @Test
    void should_label_ai_rewrite_with_media_mode_and_insert_audit_before_disclaimer() {
        String auditTable = "#### 媒体审计 (media.json sidecar 权威状态)\n\n"
                + "| # | 类型 | 下载状态 | 上传状态 | 可发布性 | 微信 URL | 本地路径 | 失败原因 |\n"
                + "|---|---|---|---|---|---|---|---|\n"
                + "| 1 | PHOTO | DOWNLOADED | UPLOADED | PUBLISHABLE | https://mmbiz.qpic.cn/mmbiz/x "
                + "| media/twitter/2026-08-30/123/photo-1.jpg | - |\n";
        Article article = Article.builder()
                .id("tw-media-1")
                .title("改写+媒体标题")
                .content("改写正文\n\n![原帖图片-1](https://mmbiz.qpic.cn/w1)")
                .source("来源:@karpathy")
                .aiGenerated(true)
                .createdAt(LocalDateTime.of(2026, 8, 30, 10, 0))
                .originalUrl("https://x.com/karpathy/status/123")
                .generationMode(ContentGenerationMode.REWRITE_WITH_MEDIA)
                .mediaAuditMarkdown(auditTable)
                .build();

        archiver.publish(article);

        String content = readAll(tempDir.resolve("archive").resolve("2026-08-30.md"));
        // Task 5: 三模式标签 — 新增 AI 改写+媒体, 不再落入 else 的 "AI 改写"
        assertThat(content).contains("- **生成模式**: AI 改写+媒体");
        // 审计位置不变: content 之后、AI 声明之前 (D-E/AC4)
        assertThat(content).contains(auditTable);
        assertThat(content.indexOf("改写正文")).isLessThan(content.indexOf("媒体审计"));
        assertThat(content.indexOf("媒体审计")).isLessThan(content.indexOf("本文由 AI 辅助生成"));
        // aiGenerated=true → AI 声明仍追加 (AR8)
        assertThat(content).contains("本文由 AI 辅助生成");
    }

    /** PRESERVE_ORIGINAL 模式 Article fixture (模拟 PreserveOriginalArticleGenerator 产出)。 */
    private Article preserveArticle(String id, String title, String mediaAuditMarkdown) {
        return Article.builder()
                .id(id)
                .title(title)
                .content("<p>原帖正文</p>")
                .source("来源:@karpathy")
                .aiGenerated(false)
                .createdAt(LocalDateTime.of(2026, 8, 29, 10, 0))
                .originalUrl("https://x.com/karpathy/status/123")
                .generationMode(ContentGenerationMode.PRESERVE_ORIGINAL)
                .mediaAuditMarkdown(mediaAuditMarkdown)
                .build();
    }

    // ===== Story 10.6: 跨日幂等(快照权威判定)/补落/fail-closed/指标旁路 =====

    /** 带真实 {@link ArticleArchiveRepository} 的归档器(共享同 baseDirectory). */
    private record RepoBackedArchiver(MarkdownArchiver archiver, ArticleArchiveRepository repository) {
    }

    private RepoBackedArchiver repoBackedArchiver() {
        ArticleArchiveRepository repository = new ArticleArchiveRepository(
                properties, new ObjectMapper().findAndRegisterModules());
        MarkdownArchiver archiver = new MarkdownArchiver(properties, repository);
        archiver.initFormatter();
        return new RepoBackedArchiver(archiver, repository);
    }

    private Path dayFile(String date) {
        return tempDir.resolve("archive").resolve(date + ".md");
    }

    private Path snapshotFile(String articleId) {
        return tempDir.resolve("archive").resolve("articles").resolve(articleId + ".json");
    }

    private int markerCount(String articleId) throws IOException {
        return readAll(dayFile("2026-06-28"))
                .split("<!-- article-id: " + articleId + " -->").length - 1;
    }

    @Test
    void should_skip_cross_day_duplicate_and_not_append_new_date_file(CapturedOutput output) throws IOException {
        RepoBackedArchiver backed = repoBackedArchiver();
        backed.archiver().publish(sampleArticle("tw-dup-1", "首日文章",
                LocalDateTime.of(2026, 6, 28, 10, 0)));
        assertThat(snapshotFile("tw-dup-1")).exists();

        // 次日重复投递同一 articleId — 快照命中, 不得追加新日期文件
        backed.archiver().publish(sampleArticle("tw-dup-1", "首日文章",
                LocalDateTime.of(2026, 6, 29, 10, 0)));

        assertThat(dayFile("2026-06-29")).doesNotExist();
        assertThat(markerCount("tw-dup-1")).isEqualTo(1);
        // 快照未被次日投递改写(archiveFile 仍指向首日文件)
        ArchivedArticle snapshot = backed.repository().findByArticleId("tw-dup-1").orElseThrow();
        assertThat(snapshot.getArchiveFile()).endsWith("2026-06-28.md");
        // 结构化审计日志含 articleId
        assertThat(output.getOut()).contains("跨日/重复投递命中归档快照");
        assertThat(output.getOut()).contains("tw-dup-1");
    }

    @Test
    void should_skip_same_day_duplicate_via_snapshot_without_append(CapturedOutput output) throws IOException {
        RepoBackedArchiver backed = repoBackedArchiver();
        Article article = sampleArticle("tw-dup-2", "同日重复",
                LocalDateTime.of(2026, 6, 28, 10, 0));

        backed.archiver().publish(article);
        backed.archiver().publish(article);

        assertThat(markerCount("tw-dup-2")).isEqualTo(1);
        assertThat(output.getOut()).contains("跨日/重复投递命中归档快照");
    }

    @Test
    void should_rebuild_missing_snapshot_when_same_day_already_archived(CapturedOutput output) throws IOException {
        RepoBackedArchiver backed = repoBackedArchiver();
        TaskMetrics metrics = mock(TaskMetrics.class);
        MarkdownArchiver archiver = new MarkdownArchiver(properties, backed.repository(),
                Optional.of(metrics));
        archiver.initFormatter();
        Article article = sampleArticle("tw-dup-3", "快照被删",
                LocalDateTime.of(2026, 6, 28, 10, 0));
        archiver.publish(article);
        Files.delete(snapshotFile("tw-dup-3"));

        // 快照缺失但当日 marker 命中 — 跳过追加 + saveSnapshot 补落(补落路径同样计幂等指标)
        archiver.publish(article);

        assertThat(markerCount("tw-dup-3")).isEqualTo(1);
        ArchivedArticle rebuilt = backed.repository().findByArticleId("tw-dup-3").orElseThrow();
        assertThat(rebuilt.getStatus()).isEqualTo(ArticleStatus.CREATED);
        assertThat(rebuilt.getArchiveFile()).endsWith("2026-06-28.md");
        assertThat(output.getOut()).contains("已补落缺失归档快照");
        verify(metrics).recordIdempotent(TaskMetrics.IdempotentKind.ARCHIVE);
    }

    @Test
    void should_fail_closed_and_not_append_when_snapshot_read_fails() throws IOException {
        RepoBackedArchiver backed = repoBackedArchiver();
        Files.createDirectories(snapshotFile("tw-dup-4").getParent());
        Files.writeString(snapshotFile("tw-dup-4"), "{corrupted json");

        // fail-closed: 快照读取失败抛 NonRetryableException(fail-closed, 由 L2.5 隔离计 failure),
        // 绝不 fail-open 追加
        assertThatThrownBy(() -> backed.archiver().publish(sampleArticle("tw-dup-4", "损坏快照",
                LocalDateTime.of(2026, 6, 28, 10, 0))))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("读取文章归档快照失败");
        assertThat(dayFile("2026-06-28")).doesNotExist();
    }

    @Test
    void should_record_idempotent_metric_on_snapshot_hit_skip() {
        RepoBackedArchiver backed = repoBackedArchiver();
        TaskMetrics metrics = mock(TaskMetrics.class);
        MarkdownArchiver archiver = new MarkdownArchiver(properties, backed.repository(),
                Optional.of(metrics));
        archiver.initFormatter();
        archiver.publish(sampleArticle("tw-dup-5", "指标验证",
                LocalDateTime.of(2026, 6, 28, 10, 0)));

        archiver.publish(sampleArticle("tw-dup-5", "指标验证",
                LocalDateTime.of(2026, 6, 29, 10, 0)));

        verify(metrics).recordIdempotent(TaskMetrics.IdempotentKind.ARCHIVE);
    }

    @Test
    void should_keep_skip_result_when_metric_recording_fails(CapturedOutput output) {
        RepoBackedArchiver backed = repoBackedArchiver();
        TaskMetrics metrics = mock(TaskMetrics.class);
        doThrow(new IllegalStateException("registry down")).when(metrics)
                .recordIdempotent(any());
        MarkdownArchiver archiver = new MarkdownArchiver(properties, backed.repository(),
                Optional.of(metrics));
        archiver.initFormatter();
        archiver.publish(sampleArticle("tw-dup-6", "指标故障",
                LocalDateTime.of(2026, 6, 28, 10, 0)));

        // 指标旁路化: 观测故障只降级告警, 不改变幂等 skip 结果
        assertThatCode(() -> archiver.publish(sampleArticle("tw-dup-6", "指标故障",
                LocalDateTime.of(2026, 6, 29, 10, 0)))).doesNotThrowAnyException();
        assertThat(dayFile("2026-06-29")).doesNotExist();
        assertThat(output.getOut()).contains("幂等跳过指标记录失败");
    }
}
