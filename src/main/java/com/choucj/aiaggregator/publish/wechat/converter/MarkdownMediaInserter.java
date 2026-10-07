package com.choucj.aiaggregator.publish.wechat.converter;

import com.choucj.aiaggregator.source.twitter.model.MediaPhaseStatus;
import com.choucj.aiaggregator.source.twitter.model.MediaUploadStatus;
import com.choucj.aiaggregator.source.twitter.model.PublishabilityStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Story 9.1 Task 4: sidecar 快照媒体 → Markdown image syntax 确定性插入器.
 *
 * <p>本组件是纯文本处理器 — 无 IO、无 WeChat API、无 sidecar 读写; sidecar 快照由调用方
 * (MediaAwareRewriteArticleGenerator) 在 {@code WeChatMediaPreparer.prepareMedia} 之后
 * 通过 {@code TweetMediaArchiveWriter.readSidecar} 重读传入 (AC 6: 只消费 prepare 后重读的
 * 同一个 sidecar 快照)。不得依赖 processor 包 (装配边界, Story 9.1 AD-12)。
 *
 * <p><b>可嵌入谓词 (AD-11, AC 6):</b> 全部满足才嵌入 —
 * <ul>
 *   <li>PHOTO: {@code type == PHOTO} + {@code uploadStatus == UPLOADED} + {@code wechatUrl}
 *       非空白 + {@code publishability != BLOCKED} — 微信正文图片必须使用微信上传返回 URL</li>
 *   <li>VIDEO (Story 10.11, 候选 A): {@code wechatPrepare == SUCCEEDED} +
 *       {@code wechatVideoMediaId} 非空白 + 非 BLOCKED — 以纯文本 mediaId 追加, 不走图片路径</li>
 *   <li>GIF/UNKNOWN 不嵌入 (降级审计计数)</li>
 * </ul>
 *
 * <p><b>顺序与去重 (AD-11):</b> 使用 sidecar 稳定顺序 (列表迭代序, 不重排);
 * 去重键优先 {@code media.id}, id 缺失/空白时用 {@code sourceUrl} 兜底, 首次出现胜出,
 * 防同一媒体生成重复微信图片。
 *
 * <p><b>输出边界 (AC 6):</b> PHOTO 只输出 Markdown image syntax
 * ({@code ![原帖图片-N](wechatUrl)}); VIDEO 只输出纯文本 mediaId (Story 10.11 候选 A);
 * 不得输出 X/CDN sourceUrl、本地 localPath 或 raw HTML {@code <img>} 标签。图片初始插入位置固定为 LLM Markdown 正文之后、
 * converter footer 之前 (Story 9.1 guardrail; 图文穿插属后续 Story 9.2)。
 *
 * <p>引用源: Story 9.1 Task 4 / architecture-rewrite-with-media AD-11 + AD-12.
 */
@Component
public class MarkdownMediaInserter {

    /** 嵌入图片 alt 文本前缀 — 序号从 1 开始, 按嵌入顺序递增。 */
    private static final String IMAGE_ALT_PREFIX = "原帖图片-";

    /** Story 10.11 (候选 A): VIDEO 纯文本 mediaId 直嵌标签 (Markdown 形态, 与 Renderer 口径一致)。 */
    private static final String VIDEO_EMBED_LABEL = "视频素材: ";

    /**
     * 在改写 Markdown 正文后追加可嵌入媒体 image syntax.
     *
     * <p>正文 null/空白时只输出媒体 Markdown (无媒体时可生成纯文字 REWRITE_WITH_MEDIA
     * 草稿, AC 9); sidecar null/空列表时正文原样返回。
     *
     * @param rewrittenMarkdown LLM 改写正文 (nullable)
     * @param sidecarMedia prepare 后重读的 sidecar 媒体快照 (可能含 null 元素, 保位语义)
     * @return 插入结果: 最终 content + 嵌入数 + 降级数 (嵌入/降级计数供 processor summary,
     *         AC 10)
     */
    public MarkdownMediaInsertionResult insert(String rewrittenMarkdown, List<TweetMedia> sidecarMedia) {
        StringBuilder content = new StringBuilder(
                rewrittenMarkdown == null ? "" : rewrittenMarkdown.strip());
        int embeddedCount = 0;
        int degradedCount = 0;
        // PHOTO alt 序号用独立计数 — embeddedCount 是 PHOTO/VIDEO 共享总嵌入数, 混排时
        // 直接复用会断号 (原帖图片-1/-3) (CR Story 10.11)
        int photoAltIndex = 0;
        Set<String> seenKeys = new HashSet<>();
        StringBuilder mediaMarkdown = new StringBuilder();

        if (sidecarMedia != null) {
            for (TweetMedia media : sidecarMedia) {
                boolean embeddablePhoto = media != null && isEmbeddablePhoto(media);
                boolean embeddableVideo = media != null && isEmbeddableVideo(media);
                if (media == null || !(embeddablePhoto || embeddableVideo) || !markSeen(media, seenKeys)) {
                    degradedCount++;
                    continue;
                }
                embeddedCount++;
                // 首张图片无前导空行; 后续图片始终以空行分隔, 即使正文为空也保持合法 Markdown
                mediaMarkdown.append(content.isEmpty() && mediaMarkdown.isEmpty() ? "" : "\n\n");
                if (embeddableVideo) {
                    // Story 10.11 (候选 A): VIDEO 以纯文本 mediaId 追加 — Spike 10.9 实测
                    // addDraft 接受且草稿回读幸存; 不输出 <img>/iframe。
                    mediaMarkdown.append(VIDEO_EMBED_LABEL)
                            .append(media.getWechatVideoMediaId().trim());
                } else {
                    photoAltIndex++;
                    mediaMarkdown.append("![").append(IMAGE_ALT_PREFIX).append(photoAltIndex)
                            .append("](").append(media.getWechatUrl().trim()).append(")");
                }
            }
        }

        content.append(mediaMarkdown);
        return new MarkdownMediaInsertionResult(content.toString(), embeddedCount, degradedCount);
    }

    /** 可嵌入谓词 (AD-11): PHOTO + UPLOADED + wechatUrl 非空 + 非 BLOCKED。 */
    private static boolean isEmbeddablePhoto(TweetMedia media) {
        return media.getType() == TweetMediaType.PHOTO
                && media.getUploadStatus() == MediaUploadStatus.UPLOADED
                && media.getWechatUrl() != null
                && !media.getWechatUrl().isBlank()
                && media.getPublishability() != PublishabilityStatus.BLOCKED;
    }

    /**
     * Story 10.11: VIDEO 可嵌入谓词 — wechatPrepare=SUCCEEDED + mediaId 非空 + 非 BLOCKED
     * (候选 A 嵌入按 {@code wechatVideoMediaId} 判定, 不复用 PHOTO 的 wechatUrl)。
     */
    private static boolean isEmbeddableVideo(TweetMedia media) {
        return media.getType() == TweetMediaType.VIDEO
                && media.getWechatPrepare() != null
                && media.getWechatPrepare().getStatus() == MediaPhaseStatus.SUCCEEDED
                && media.getWechatVideoMediaId() != null
                && !media.getWechatVideoMediaId().isBlank()
                && media.getPublishability() != PublishabilityStatus.BLOCKED;
    }

    /**
     * 去重标记: 键优先 {@code media.id}, 缺失/空白用 {@code sourceUrl} 兜底 (AD-11);
     * 二者均缺失时按未见过处理 (sidecar 正常数据必有其一, 防御性放行)。
     *
     * @return true=首次出现可嵌入; false=重复条目跳过
     */
    private static boolean markSeen(TweetMedia media, Set<String> seenKeys) {
        String key = (media.getId() != null && !media.getId().isBlank())
                ? "id:" + media.getId()
                : (media.getSourceUrl() != null && !media.getSourceUrl().isBlank()
                        ? "url:" + media.getSourceUrl()
                        : null);
        return key == null || seenKeys.add(key);
    }

    /**
     * 插入结果: 最终正文 + 观测计数 (processor summary 用, AC 10 / W11 日志).
     *
     * @param content LLM Markdown 正文 + 媒体 Markdown image syntax
     * @param embeddedCount 嵌入正文的微信图片数 (去重后)
     * @param degradedCount 未嵌入的 sidecar 条目数 (VIDEO/GIF/UNKNOWN、下载/上传失败、
     *                      BLOCKED、重复条目、null 元素均计入降级审计, AC 8)
     */
    public record MarkdownMediaInsertionResult(String content, int embeddedCount, int degradedCount) {
    }
}
