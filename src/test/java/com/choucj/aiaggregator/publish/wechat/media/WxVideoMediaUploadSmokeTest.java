package com.choucj.aiaggregator.publish.wechat.media;

import com.binarywang.spring.starter.wxjava.mp.properties.WxMpProperties;
import com.choucj.aiaggregator.common.exception.AggregatorException;
import com.choucj.aiaggregator.publish.wechat.WeChatThumbMediaIdResolver;
import me.chanjar.weixin.common.api.WxConsts;
import me.chanjar.weixin.common.bean.result.WxMediaUploadResult;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.draft.WxMpAddDraft;
import me.chanjar.weixin.mp.bean.draft.WxMpDraftArticles;
import me.chanjar.weixin.mp.bean.draft.WxMpDraftInfo;
import me.chanjar.weixin.mp.bean.material.WxMpMaterial;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialUploadResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Story 10.9: 微信视频素材上传与草稿引用能力 spike smoke test。
 *
 * <p>覆盖 I/O 矩阵全场景: 临时/永久上传、materialVideoInfo 回读、容量边界(>10MB)、
 * 格式边界(非 mp4)、草稿引用实测(addDraft + draft/get 回读)。
 *
 * <p>默认使用仓库内测试 MP4 样例; 边界样例位于 {@code target/spike-10.9/}
 * (ffmpeg 生成, 缺失时对应用例自动 skip); 如需覆盖主样例, 可设置 {@code WECHAT_VIDEO_PATH}。
 *
 * <p>成功创建的永久素材/草稿完整 mediaId 追加写入 {@code target/spike-10.9/created-artifacts.txt}
 * (文件不入库), 供人工后台清理。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "wechat.mp.enabled=true",
        "archive.enabled=false",
        "schedule.run-on-startup=false"
})
@Tag("external")
class WxVideoMediaUploadSmokeTest {

    private static final Path DEFAULT_VIDEO_PATH = Path.of(
            "target/spike-8.2/wechat-video-test.mp4");
    private static final Path OVER_10MB_VIDEO_PATH = Path.of(
            "target/spike-10.9/wechat-video-over-10mb.mp4");
    private static final Path NON_MP4_VIDEO_PATH = Path.of(
            "target/spike-10.9/wechat-video-boundary.avi");
    private static final Path CREATED_ARTIFACTS_PATH = Path.of(
            "target/spike-10.9/created-artifacts.txt");

    @Autowired(required = false)
    private WeChatVideoMediaUploadProbe uploadProbe;

    @Autowired(required = false)
    private WxMpService wxMpService;

    @Autowired(required = false)
    private WeChatThumbMediaIdResolver thumbMediaIdResolver;

    @Autowired
    private WxMpProperties wxMpProperties;

    @Test
    @Timeout(60)
    void shouldUploadTempVideoWhenRealCredentialsAvailable() {
        assumeThat(uploadProbe)
                .as("wechat.mp.enabled=true 时视频上传 probe 应注册")
                .isNotNull();
        assumeThat(hasRealCredentials())
                .as("需要真实的微信公众号 app-id / secret")
                .isTrue();

        Path videoPath = resolveVideoPath();
        assumeThat(Files.isRegularFile(videoPath))
                .as("需要可用的本地视频样例")
                .isTrue();

        WeChatVideoMediaUploadProbe.UploadedTempVideo uploaded;
        try {
            uploaded = uploadProbe.uploadTempVideo(videoPath);
        } catch (AggregatorException e) {
            skipIfEnvironmentBlocked(e);
            throw e;
        }

        System.out.printf("[spike-10.9] 临时视频上传成功: fileName=%s, sizeBytes=%d, mediaIdPrefix=%s%n",
                uploaded.fileName(), uploaded.sizeBytes(), prefix(uploaded.mediaId(), 8));
        assertThat(uploaded.mediaId()).isNotBlank();
        assertThat(uploaded.fileName()).isEqualTo(videoPath.getFileName().toString());
        assertThat(uploaded.sizeBytes()).isPositive();
    }

    @Test
    @Timeout(60)
    void shouldUploadPermanentVideoWhenRealCredentialsAvailable() {
        assumeThat(uploadProbe)
                .as("wechat.mp.enabled=true 时视频上传 probe 应注册")
                .isNotNull();
        assumeThat(hasRealCredentials())
                .as("需要真实的微信公众号 app-id / secret")
                .isTrue();

        Path videoPath = resolveVideoPath();
        assumeThat(Files.isRegularFile(videoPath))
                .as("需要可用的本地视频样例")
                .isTrue();

        WeChatVideoMediaUploadProbe.UploadedPermanentVideo uploaded;
        try {
            uploaded = uploadProbe.uploadPermanentVideo(videoPath, "story-10.9-spike-video", "story-10.9-spike");
        } catch (AggregatorException e) {
            skipIfEnvironmentBlocked(e);
            throw e;
        }

        recordCreatedArtifact("shouldUploadPermanentVideoWhenRealCredentialsAvailable", uploaded.mediaId());
        System.out.printf("[spike-10.9] 永久视频上传成功(已记入 created-artifacts.txt 待人工后台清理): fileName=%s, sizeBytes=%d, mediaIdPrefix=%s%n",
                uploaded.fileName(), uploaded.sizeBytes(), prefix(uploaded.mediaId(), 8));
        assertThat(uploaded.mediaId()).isNotBlank();
        assertThat(uploaded.fileName()).isEqualTo(videoPath.getFileName().toString());
        assertThat(uploaded.sizeBytes()).isPositive();
    }

    @Test
    @Timeout(120)
    void shouldQueryPermanentVideoInfoAfterPermanentUpload() {
        assumeThat(uploadProbe)
                .as("wechat.mp.enabled=true 时视频上传 probe 应注册")
                .isNotNull();
        assumeThat(hasRealCredentials())
                .as("需要真实的微信公众号 app-id / secret")
                .isTrue();

        Path videoPath = resolveVideoPath();
        assumeThat(Files.isRegularFile(videoPath))
                .as("需要可用的本地视频样例")
                .isTrue();

        WeChatVideoMediaUploadProbe.UploadedPermanentVideo uploaded;
        try {
            uploaded = uploadProbe.uploadPermanentVideo(videoPath, "story-10.9-spike-video-info", "story-10.9-spike");
        } catch (AggregatorException e) {
            skipIfEnvironmentBlocked(e);
            throw e;
        }
        assertThat(uploaded.mediaId()).isNotBlank();
        recordCreatedArtifact("shouldQueryPermanentVideoInfoAfterPermanentUpload", uploaded.mediaId());

        // 微信视频可能有异步转码: 回读失败(字段为空)时按固定间隔重试并记录最终状态。
        WeChatVideoMediaUploadProbe.PermanentVideoInfo info = null;
        int maxAttempts = 3;
        int actualAttempts = 0;
        long retryIntervalMs = 3_000L;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            actualAttempts = attempt;
            try {
                info = uploadProbe.queryPermanentVideoInfo(uploaded.mediaId());
            } catch (AggregatorException e) {
                skipIfEnvironmentBlocked(e);
                throw e;
            }
            if (StringUtils.hasText(info.title())) {
                break;
            }
            if (attempt < maxAttempts) {
                System.out.printf("[spike-10.9] materialVideoInfo 回读字段为空(attempt=%d/%d), 等待 %dms 后重试%n",
                        attempt, maxAttempts, retryIntervalMs);
                try {
                    Thread.sleep(retryIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        System.out.printf("[spike-10.9] materialVideoInfo 回读: mediaIdPrefix=%s, titlePresent=%s, descriptionPresent=%s, downUrlPresent=%s, attempts=%d/%d%n",
                prefix(uploaded.mediaId(), 8),
                StringUtils.hasText(info.title()),
                StringUtils.hasText(info.description()),
                StringUtils.hasText(info.downUrl()),
                actualAttempts, maxAttempts);
        assertThat(info).isNotNull();
    }

    @Test
    @Timeout(180)
    void shouldRecordOverCapacityRejectionFromRealApi() {
        assumeThat(uploadProbe)
                .as("wechat.mp.enabled=true 时视频上传 probe 应注册")
                .isNotNull();
        assumeThat(wxMpService)
                .as("需要 WxMpService 直接调 SDK 以绕过探针本地 10MB 校验")
                .isNotNull();
        assumeThat(hasRealCredentials())
                .as("需要真实的微信公众号 app-id / secret")
                .isTrue();
        assumeThat(Files.isRegularFile(OVER_10MB_VIDEO_PATH))
                .as("需要 ffmpeg 生成的 >10MB 边界样例: target/spike-10.9/wechat-video-over-10mb.mp4")
                .isTrue();

        long sizeBytes;
        try {
            sizeBytes = Files.size(OVER_10MB_VIDEO_PATH);
        } catch (Exception e) {
            throw new IllegalStateException("读取边界样例大小失败", e);
        }
        assertThat(sizeBytes).isGreaterThan(WeChatVideoMediaUploadProbe.MAX_VIDEO_SIZE_BYTES);

        String scenario = "容量边界(>10MB, sizeBytes=" + sizeBytes + ")";
        // 临时素材端点对照(直接调 SDK 绕过探针本地校验)。
        probeTempMaterialBoundary(scenario, OVER_10MB_VIDEO_PATH);
        // 永久素材端点(10.11 契约主体), WxMpMaterial 需 name/title/introduction。
        probePermanentMaterialBoundary(scenario, new WxMpMaterial(
                OVER_10MB_VIDEO_PATH.getFileName().toString(), OVER_10MB_VIDEO_PATH.toFile(),
                "story-10.9-spike-capacity", "story-10.9-spike"));
    }

    @Test
    @Timeout(180)
    void shouldRecordNonMp4FormatRejectionFromRealApi() {
        assumeThat(uploadProbe)
                .as("wechat.mp.enabled=true 时视频上传 probe 应注册")
                .isNotNull();
        assumeThat(wxMpService)
                .as("需要 WxMpService 直接调 SDK 以绕过探针 mp4 假设")
                .isNotNull();
        assumeThat(hasRealCredentials())
                .as("需要真实的微信公众号 app-id / secret")
                .isTrue();
        assumeThat(Files.isRegularFile(NON_MP4_VIDEO_PATH))
                .as("需要 ffmpeg 生成的非 mp4 边界样例: target/spike-10.9/wechat-video-boundary.avi")
                .isTrue();

        long sizeBytes;
        try {
            sizeBytes = Files.size(NON_MP4_VIDEO_PATH);
        } catch (Exception e) {
            throw new IllegalStateException("读取格式边界样例大小失败", e);
        }

        String scenario = "格式边界(avi, sizeBytes=" + sizeBytes + ")";
        probeTempMaterialBoundary(scenario, NON_MP4_VIDEO_PATH);
        probePermanentMaterialBoundary(scenario, new WxMpMaterial(
                NON_MP4_VIDEO_PATH.getFileName().toString(), NON_MP4_VIDEO_PATH.toFile(),
                "story-10.9-spike-format", "story-10.9-spike"));
    }

    @Test
    @Timeout(240)
    void shouldCreateDraftsReferencingPermanentVideoAndReadBack() {
        assumeThat(uploadProbe)
                .as("wechat.mp.enabled=true 时视频上传 probe 应注册")
                .isNotNull();
        assumeThat(wxMpService)
                .as("Spike 草稿实测直接使用 WxMpService, 不经 WeChatPublisher")
                .isNotNull();
        assumeThat(thumbMediaIdResolver)
                .as("草稿封面 thumb_media_id 解析器应注册")
                .isNotNull();
        assumeThat(hasRealCredentials())
                .as("需要真实的微信公众号 app-id / secret")
                .isTrue();

        Path videoPath = resolveVideoPath();
        assumeThat(Files.isRegularFile(videoPath))
                .as("需要可用的本地视频样例")
                .isTrue();

        // 1. 上传永久视频作为引用源(记录完整 media_id 供人工后台清理)。
        WeChatVideoMediaUploadProbe.UploadedPermanentVideo uploaded;
        try {
            uploaded = uploadProbe.uploadPermanentVideo(videoPath, "story-10.9-spike-draft-ref", "story-10.9-spike");
        } catch (AggregatorException e) {
            skipIfEnvironmentBlocked(e);
            throw e;
        }
        String videoMediaId = uploaded.mediaId();
        assertThat(videoMediaId).isNotBlank();
        recordCreatedArtifact("shouldCreateDraftsReferencingPermanentVideoAndReadBack/source-video", videoMediaId);
        System.out.printf("[spike-10.9] 草稿引用源永久视频已上传(已记入 created-artifacts.txt 待人工后台清理): mediaIdPrefix=%s%n",
                prefix(videoMediaId, 8));

        String thumbMediaId = thumbMediaIdResolver.resolve();
        assumeThat(StringUtils.hasText(thumbMediaId))
                .as("需要可解析的草稿封面 thumb_media_id")
                .isTrue();

        // 2. 候选 A: 永久视频 mediaId 直嵌 content 文本(设计注记的首选路径)。
        String candidateDirect = "<p>story-10.9-spike-video-media-id: " + videoMediaId + "</p>";
        probeDraftCandidate("direct-embed", candidateDirect, videoMediaId, thumbMediaId);

        // 3. 候选 B: iframe.video_iframe 形态(仅在候选 A 探测后顺带记录, 供 10.11 对账)。
        String candidateIframe = "<p>story-10.9-spike-iframe-candidate</p>"
                + "<iframe class=\"video_iframe\" frameborder=\"0\" src=\"" + videoMediaId + "\"></iframe>";
        probeDraftCandidate("iframe", candidateIframe, videoMediaId, thumbMediaId);
    }

    private void probeTempMaterialBoundary(String scenario, Path sample) {
        try {
            WxMediaUploadResult result = wxMpService.getMaterialService()
                    .mediaUpload(WxConsts.MediaFileType.VIDEO, sample.toFile());
            System.out.printf("[spike-10.9] %s: endpoint=mediaUpload(temp) 微信未拒绝, mediaIdPrefix=%s (与既有口径不符, 需人工复核)%n",
                    scenario, result == null ? "null" : prefix(result.getMediaId(), 8));
        } catch (WxErrorException e) {
            recordBoundaryRejection(scenario, "mediaUpload(temp)", e);
        } catch (RuntimeException e) {
            recordBoundaryRuntime(scenario, "mediaUpload(temp)", e);
        }
    }

    private void probePermanentMaterialBoundary(String scenario, WxMpMaterial material) {
        try {
            WxMpMaterialUploadResult result = wxMpService.getMaterialService()
                    .materialFileUpload(WxConsts.MediaFileType.VIDEO, material);
            System.out.printf("[spike-10.9] %s: endpoint=materialFileUpload(permanent) 微信未拒绝, mediaIdPrefix=%s (与既有口径不符, 需人工复核)%n",
                    scenario, result == null ? "null" : prefix(result.getMediaId(), 8));
            if (result != null && StringUtils.hasText(result.getMediaId())) {
                recordCreatedArtifact("boundary/permanent", result.getMediaId());
            }
        } catch (WxErrorException e) {
            recordBoundaryRejection(scenario, "materialFileUpload(permanent)", e);
        } catch (RuntimeException e) {
            recordBoundaryRuntime(scenario, "materialFileUpload(permanent)", e);
        }
    }

    private void recordBoundaryRejection(String scenario, String endpoint, WxErrorException e) {
        skipIfBlocked(errcode(e), e.getMessage());
        System.out.printf("[spike-10.9] %s: endpoint=%s 微信拒绝 errcode=%d, errmsg=%s (引用契约证据)%n",
                scenario, endpoint, errcode(e), errmsg(e));
    }

    private void recordBoundaryRuntime(String scenario, String endpoint, RuntimeException e) {
        skipIfBlocked(-1, rootMessage(e));
        System.out.printf("[spike-10.9] %s: endpoint=%s SDK 框架异常 rootMessage=%s%n",
                scenario, endpoint, rootMessage(e));
    }

    private void probeDraftCandidate(String candidate, String content, String videoMediaId, String thumbMediaId) {
        WxMpDraftArticles article = new WxMpDraftArticles();
        article.setTitle("story-10.9-spike-video-ref-" + candidate);
        article.setContent(content);
        article.setThumbMediaId(thumbMediaId);

        String draftMediaId;
        try {
            draftMediaId = wxMpService.getDraftService().addDraft(new WxMpAddDraft(List.of(article)));
        } catch (WxErrorException e) {
            if (skipIfWxEnvironmentBlocked(e)) {
                return;
            }
            System.out.printf("[spike-10.9] 草稿引用候选 %s: addDraft 拒绝 errcode=%d, errmsg=%s (引用契约证据)%n",
                    candidate, errcode(e), errmsg(e));
            return;
        } catch (RuntimeException e) {
            skipIfBlocked(-1, rootMessage(e));
            System.out.printf("[spike-10.9] 草稿引用候选 %s: SDK 框架异常 rootMessage=%s%n",
                    candidate, rootMessage(e));
            return;
        }
        // 能力异常应显性失败, 不能静默 skip。
        assertThat(draftMediaId).as("addDraft 应返回草稿 media_id").isNotBlank();
        recordCreatedArtifact("shouldCreateDraftsReferencingPermanentVideoAndReadBack/draft-" + candidate, draftMediaId);
        System.out.printf("[spike-10.9] 草稿引用候选 %s: addDraft 接受, 草稿 mediaIdPrefix=%s (已记入 created-artifacts.txt 待人工后台清理)%n",
                candidate, prefix(draftMediaId, 8));

        WxMpDraftInfo draftInfo;
        try {
            draftInfo = wxMpService.getDraftService().getDraft(draftMediaId);
        } catch (WxErrorException e) {
            if (skipIfWxEnvironmentBlocked(e)) {
                return;
            }
            System.out.printf("[spike-10.9] 草稿引用候选 %s: draft/get 回读失败 errcode=%d, errmsg=%s%n",
                    candidate, errcode(e), errmsg(e));
            return;
        } catch (RuntimeException e) {
            skipIfBlocked(-1, rootMessage(e));
            System.out.printf("[spike-10.9] 草稿引用候选 %s: draft/get SDK 框架异常 rootMessage=%s%n",
                    candidate, rootMessage(e));
            return;
        }

        String storedContent = draftInfo == null || draftInfo.getNewsItem() == null
                || draftInfo.getNewsItem().isEmpty()
                || draftInfo.getNewsItem().get(0) == null
                ? null
                : draftInfo.getNewsItem().get(0).getContent();
        boolean mediaIdSurvived = StringUtils.hasText(storedContent) && storedContent.contains(videoMediaId);
        System.out.printf("[spike-10.9] 草稿引用候选 %s: draft/get 回读成功, contentLength=%s, videoMediaIdSurvived=%s%n",
                candidate,
                storedContent == null ? "null" : storedContent.length(),
                mediaIdSurvived);
        if (StringUtils.hasText(storedContent)) {
            System.out.printf("[spike-10.9] 草稿引用候选 %s: 回读 content 截断摘要=%s%n",
                    candidate, prefix(storedContent.replace(videoMediaId, "<videoMediaId>"), 200));
        }
    }

    private boolean hasRealCredentials() {
        return StringUtils.hasText(wxMpProperties.getAppId())
                && StringUtils.hasText(wxMpProperties.getSecret())
                && !"dev-placeholder".equals(wxMpProperties.getAppId());
    }

    /**
     * AggregatorException 路径的阻塞判定(委托统一口径)。
     */
    private static void skipIfEnvironmentBlocked(AggregatorException e) {
        skipIfBlocked(-1, e.getMessage());
    }

    /**
     * 直调 SDK 路径的阻塞判定(委托统一口径, 返回 true 表示已 skip)。
     */
    private static boolean skipIfWxEnvironmentBlocked(WxErrorException e) {
        skipIfBlocked(errcode(e), e.getMessage());
        return false;
    }

    /**
     * 统一环境/凭据阻塞判定(单一口径): errcode 覆盖 40164/45009/40013/40001/40014,
     * 文本覆盖 DNS 三特征。命中即 assumeTrue-skip; 未命中直接返回, 调用方继续记录证据。
     */
    private static void skipIfBlocked(int errcode, String message) {
        String text = message == null ? "" : message;
        boolean whitelistBlocked = errcode == 40164 || text.contains("errcode=40164");
        boolean quotaBlocked = errcode == 45009 || text.contains("errcode=45009");
        boolean credentialBlocked = errcode == 40001 || errcode == 40013 || errcode == 40014
                || text.contains("errcode=40001")
                || text.contains("errcode=40013")
                || text.contains("errcode=40014");
        boolean dnsBlocked = text.contains("UnknownHostException")
                || text.contains("nodename nor servname provided")
                || text.contains("api.weixin.qq.com");
        assumeTrue(!(whitelistBlocked || quotaBlocked || credentialBlocked || dnsBlocked),
                "跳过: 外部微信环境阻塞 - errcode=" + errcode + ", " + text);
    }

    /**
     * 成功创建的永久素材/草稿完整 mediaId 追加写入 target/spike-10.9/created-artifacts.txt(不入库),
     * 一行一条(时间 + 来源 + mediaId), 供人工后台清理; 失败/阻塞分支不写。
     */
    private static void recordCreatedArtifact(String source, String mediaId) {
        String line = LocalDateTime.now() + " " + source + " " + mediaId + System.lineSeparator();
        try {
            Path artifacts = CREATED_ARTIFACTS_PATH.toAbsolutePath().normalize();
            Files.createDirectories(artifacts.getParent());
            Files.writeString(artifacts, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.out.printf("[spike-10.9] created-artifacts.txt 写入失败(请人工记录该 mediaId): source=%s, err=%s%n",
                    source, e.getMessage());
        }
    }

    private static int errcode(WxErrorException e) {
        return e.getError() == null ? -1 : e.getError().getErrorCode();
    }

    private static String errmsg(WxErrorException e) {
        String msg = e.getError() == null ? null : e.getError().getErrorMsg();
        return msg == null ? "unknown" : prefix(msg, 200);
    }

    private static String rootMessage(RuntimeException e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null ? root.getClass().getSimpleName() : prefix(message, 200);
    }

    private static String prefix(String value, int length) {
        if (value == null) {
            return "null";
        }
        return value.length() <= length ? value : value.substring(0, length);
    }

    private static Path resolveVideoPath() {
        String override = System.getenv("WECHAT_VIDEO_PATH");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return DEFAULT_VIDEO_PATH.toAbsolutePath().normalize();
    }
}
