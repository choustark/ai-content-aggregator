package com.choucj.aiaggregator.publish.storage;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.common.model.ContentGenerationMode;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.common.util.TextTruncateUtil;
import com.choucj.aiaggregator.publish.ContentPublisher;
import com.choucj.aiaggregator.publish.storage.config.ArchiverProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Story 2.5: Markdown 归档器 — 文件系统侧持久化实现.
 *
 * <p>实现 {@link ContentPublisher}, 将 {@link Article} 以 Markdown 格式按天归档到
 * {@code {base-directory}/{yyyy-MM-dd}.md}. 同日多篇文章追加到同一文件, 用
 * {@link ArchiverProperties#getSeparator()} 分隔. 满足 PRD NFR4 双重存储的文件系统侧
 * (对端为 {@code RedisRepository}, Story 1.5a).
 *
 * <p><b>关键设计决策:</b>
 * <ul>
 *   <li><b>IO 异常映射 NonRetryable</b> — 文件系统错误通常是永久的 (磁盘满/权限拒绝),
 *       重试无意义; 临时锁场景在单线程调度下不发生 (Story 1.6 ContentScheduler 默认单线程).
 *       见 Story 1.4 异常分类法</li>
 *   <li><b>幂等性实现: {@code <!-- article-id: {id} -->} HTML 注释</b> — 每个文章块首行加隐藏注释,
 *       {@link #isAlreadyArchived} 读取当日文件检测重复. Pipeline 2.6 重试场景下避免重复写入</li>
 *   <li><b>路径解析用 {@code createdAt} 而非 {@code LocalDate.now()}</b> — 保证 Pipeline 跨日重试时
 *       归档到原日期, 不污染新日期文件</li>
 *   <li><b>AR8 合规自动追加</b> — {@link Article#isAiGenerated()} 默认 {@code true} (via
 *       {@code @Builder.Default}), 归档时末尾自动追加 {@code > 本文由 AI 辅助生成} 声明</li>
 *   <li><b>扁平包结构</b> — 与 Story 2.3b/2.4 一致 ({@code config/ArchiverProperties} +
 *       {@code MarkdownArchiver} 同级), 偏离架构原文 {@code config/ + service/} 双子目录 (架构 delta)</li>
 * </ul>
 *
 * <p><b>Story 2.4 review lessons 复用:</b>
 * <ul>
 *   <li>N2 — {@link #truncateByCodePoints} 按 code point 截断 (非 char), 防 UTF-16 代理对切断乱码</li>
 *   <li>W11 — {@code log.info} 含 {@code articleId + 文件 + 大小} 关键标识符便于运维定位</li>
 *   <li>N4 — 异常 message 不含正文 (防泄漏/撑爆日志), 只含 {@code articleId + 文件路径 + cause}</li>
 *   <li>Patch-5 — {@code log.error} 最后参数传 exception, SLF4J 自动展开堆栈</li>
 *   <li>R3-1 — {@link #truncateForLog} 用 {@code truncateByCodePoints(s, max - 3) + "..."} 让
 *       {@code "..."} 占 max 预算, 返回值 ≤ max codepoint</li>
 * </ul>
 *
 * <p>引用源: Story 2.5 (实现) / Story 2.6 (Pipeline 编排调用) / Story 5.x (NFR4 双重存储策略).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "archive.enabled", havingValue = "true", matchIfMissing = true)
public class MarkdownArchiver implements ContentPublisher {

    /** 元信息日期格式 (固定, 与文件名 datePattern 解耦). */
    private static final DateTimeFormatter META_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 日志中标题/路径截断长度 (防超长撑爆日志). */
    private static final int LOG_TITLE_MAX_LENGTH = 50;

    /** 日志中异常 message 截断长度. */
    private static final int LOG_MSG_MAX_LENGTH = 200;

    /** 幂等查重标记模板 — HTML 注释, Markdown 渲染不可见. */
    private static final String ARTICLE_ID_MARKER_TEMPLATE = "<!-- article-id: %s -->";

    /** AR8 合规声明 — AI 生成内容末尾追加. */
    private static final String AI_DISCLAIMER = "\n> 本文由 AI 辅助生成\n";

    private final ArchiverProperties properties;

    /** 缓存日期格式器 (从 properties.datePattern 构造, @PostConstruct 初始化防重复创建开销). */
    private DateTimeFormatter fileNameDateFormatter;

    public MarkdownArchiver(ArchiverProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void initFormatter() {
        try {
            this.fileNameDateFormatter = DateTimeFormatter.ofPattern(properties.getDatePattern());
            LocalDate.of(2000, 1, 2).format(fileNameDateFormatter);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "归档 datePattern 配置非法: " + properties.getDatePattern()
                            + " (仅允许 [-_a-zA-Z0-9], 且需为合法 DateTimeFormatter pattern)", e);
        }
    }

    @Override
    public void publish(Article article) {
        Objects.requireNonNull(article, "article 不能为 null");
        Objects.requireNonNull(article.getId(), "article.id 不能为 null");
        Objects.requireNonNull(article.getCreatedAt(), "article.createdAt 不能为 null");

        Path archiveFile = resolveArchiveFile(article.getCreatedAt());
        ensureDirectoryExists(archiveFile.getParent(), article.getId());

        if (isAlreadyArchived(archiveFile, article.getId())) {
            log.warn("文章已归档, 跳过: articleId={}, 文件={}", article.getId(), archiveFile);
            return;
        }

        String block = buildArticleBlock(article);
        int bytes = appendToFile(archiveFile, block, article.getId());

        log.info("归档成功: articleId={}, 标题={}, 文件={}, 大小={}bytes",
                article.getId(), truncateForLog(article.getTitle(), LOG_TITLE_MAX_LENGTH),
                archiveFile, bytes);
    }

    /**
     * 解析归档文件路径: {@code {base-directory}/{createdAt:yyyy-MM-dd}.md}.
     *
     * <p>用 {@code createdAt} 而非 {@code LocalDate.now()} — 保证 Pipeline 跨日重试时归档到原日期.
     * {@code .normalize()} 消除 {@code ../} 路径穿越, 并断言结果仍以 {@code baseDirectory} 为前缀
     * (双重防御 — 与 {@code ArchiverProperties.@Pattern} 校验联合防逃逸).
     *
     * @throws NonRetryableException 若 normalize 后路径逃逸出 {@code baseDirectory} (配置被恶意篡改场景)
     */
    Path resolveArchiveFile(LocalDateTime createdAt) {
        String datePart = createdAt.toLocalDate().format(fileNameDateFormatter);
        String fileName = datePart + properties.getFileSuffix();
        Path baseNormalized = Path.of(properties.getBaseDirectory()).normalize();
        Path resolved = baseNormalized.resolve(fileName).normalize();
        if (!resolved.startsWith(baseNormalized)) {
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "归档路径逃逸 baseDirectory: base=" + baseNormalized + " resolved=" + resolved, null);
        }
        return resolved;
    }

    /**
     * 递归创建归档目录 (AC-5). 幂等 — 目录已存在不报错.
     *
     * <p>{@code IOException} / {@code SecurityException} 均包装为 {@link NonRetryableException}
     * (永久错误, 需人工介入修复权限/磁盘).
     *
     * @param dir 归档目录
     * @param articleId 文章 ID (用于 log.error 关键标识符, AC-8 W11 — 第 3 轮审查 L688 Decision 修复)
     */
    void ensureDirectoryExists(Path dir, String articleId) {
        try {
            Files.createDirectories(dir);
        } catch (IOException | SecurityException e) {
            log.error("归档目录创建失败 (永久错误): articleId={}, 目录={}", articleId, dir, e);
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "归档目录创建失败: articleId=" + articleId + " 目录=" + dir, e);
        }
    }

    /**
     * 幂等查重 (AC-6): 检测当日归档文件是否已包含指定 articleId 的标记.
     *
     * <p><b>fail-open 策略</b>: 读取异常时假定未归档继续写入 (避免阻塞主流程).
     * 最坏情况是重复写入, 由人工去重 — 但 Pipeline 2.6 单线程调度下几乎不会触发.
     */
    boolean isAlreadyArchived(Path file, String articleId) {
        if (!Files.exists(file)) {
            return false;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return content.contains(String.format(ARTICLE_ID_MARKER_TEMPLATE, articleId));
        } catch (IOException e) {
            log.warn("幂等检查读取失败, 假定未归档继续写入: 文件={}, error={}",
                    file, truncateForLog(getRootMessage(e), LOG_MSG_MAX_LENGTH));
            return false;
        }
    }

    /**
     * 构造单篇文章的 Markdown 块 (AC-3).
     *
     * <p>结构:
     * <pre>
     * &lt;!-- article-id: {id} --&gt;
     *
     * ## {title}
     *
     * - **日期**: {createdAt:yyyy-MM-dd HH:mm}
     * - **来源**: {source}
     * - **AI 生成**: {是|否}
     * - **生成模式**: {AI 改写|原帖复现}   ← Story 8.6 新增
     * - **原文链接**: {originalUrl}     ← 仅 non-null 时输出
     *
     * {content}
     *
     * {mediaAuditMarkdown}              ← Story 8.6: 仅 non-null 时 verbatim 插入 (AC4)
     *
     * &gt; 本文由 AI 辅助生成            ← 仅 aiGenerated=true 时追加 (AR8)
     *
     * ---
     *
     * </pre>
     *
     * <p>末尾追加 {@link ArchiverProperties#getSeparator()} 便于后续文章追加 (最后一个块也加,
     * 简化追加逻辑 — 不需要判断是否首篇).
     */
    String buildArticleBlock(Article article) {
        StringBuilder sb = new StringBuilder();

        sb.append(String.format(ARTICLE_ID_MARKER_TEMPLATE, article.getId())).append("\n\n");
        sb.append("## ").append(article.getTitle()).append("\n\n");

        sb.append("- **日期**: ").append(article.getCreatedAt().format(META_DATE_FORMAT)).append("\n");
        sb.append("- **来源**: ").append(article.getSource()).append("\n");
        sb.append("- **AI 生成**: ").append(article.isAiGenerated() ? "是" : "否").append("\n");
        // Story 8.6 Task 6.1: 生成模式标注行 (AC4) — REWRITE=AI 改写 (既有块新增此行),
        // PRESERVE_ORIGINAL=原帖复现
        sb.append("- **生成模式**: ")
                .append(article.getGenerationMode() == ContentGenerationMode.PRESERVE_ORIGINAL
                        ? "原帖复现"
                        : "AI 改写")
                .append("\n");
        if (article.getOriginalUrl() != null) {
            sb.append("- **原文链接**: ").append(article.getOriginalUrl()).append("\n");
        }

        sb.append("\n").append(article.getContent()).append("\n");

        // Story 8.6 Task 6.1: 媒体审计表 verbatim 插入 (content 之后、AI 声明之前, AC4/D-E);
        // REWRITE 恒 null 不插入 (既有格式不变)
        if (article.getMediaAuditMarkdown() != null && !article.getMediaAuditMarkdown().isBlank()) {
            sb.append("\n").append(article.getMediaAuditMarkdown());
        }

        if (article.isAiGenerated()) {
            sb.append(AI_DISCLAIMER);
        }

        sb.append(properties.getSeparator());
        return sb.toString();
    }

    /**
     * 追加写入文件 (AC-2, AC-4).
     *
     * <p>使用 {@code Files.writeString} + {@code CREATE + APPEND} 选项: 文件不存在创建,
     * 存在则追加. 显式指定 {@link StandardCharsets#UTF_8} 防平台默认编码差异.
     *
     * @return 写入字节数 (用于 log.info 的 size 字段)
     */
    int appendToFile(Path file, String content, String articleId) {
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            Files.write(file, bytes,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE);
            return bytes.length;
        } catch (IOException e) {
            log.error("归档失败 (永久错误): articleId={}, 文件={}",
                    articleId, file, e);
            throw new NonRetryableException(ErrorCode.NON_RETRYABLE_ERROR,
                    "归档失败 articleId=" + articleId + " 文件=" + file
                            + " cause=" + truncateForLog(getRootMessage(e), LOG_MSG_MAX_LENGTH), e);
        }
    }

    /**
     * 按 code point 截断 (N2 模式, 复用 Story 2.4 实现).
     */
    static String truncateByCodePoints(String content, int maxCodePoints) {
        return TextTruncateUtil.truncateByCodePoints(content, maxCodePoints);
    }

    /**
     * 日志字符串截断 — 返回值总 codepoint 数 ≤ max (R3-1 修复, 复用 Story 2.4 实现).
     */
    static String truncateForLog(String s, int max) {
        return TextTruncateUtil.truncateForLog(s, max);
    }

    private static String getRootMessage(Throwable e) {
        Throwable cursor = e;
        String last = e.getMessage();
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
            if (cursor.getMessage() != null) {
                last = cursor.getMessage();
            }
        }
        return last == null ? e.getClass().getSimpleName() : last;
    }
}
