package com.choucj.aiaggregator.publish.wechat.media;

import com.binarywang.spring.starter.wxjava.mp.properties.WxMpProperties;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.draft.WxMpDraftArticles;
import me.chanjar.weixin.mp.bean.draft.WxMpDraftInfo;
import me.chanjar.weixin.mp.bean.draft.WxMpDraftItem;
import me.chanjar.weixin.mp.bean.material.WxMpMaterialVideoInfoResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 只读公开 API 的 Story 10.13 真实标识对账 smoke；不创建或删除草稿。 */
@SpringBootTest
@TestPropertySource(properties = {"wechat.mp.enabled=true", "archive.enabled=false",
        "schedule.run-on-startup=false"})
@Tag("external")
class WxInlineVideoIdentityMappingSmokeTest {

    private static final Path ROOT = Path.of("target/spike-10.13");
    private static final Path RAW = ROOT.resolve("raw");
    private static final List<String> EVIDENCE_FILES = List.of(
            "material-response.txt", "source-draft-content.html", "mapping-result.txt");

    @Autowired(required = false)
    private WxMpService wxMpService;
    @Autowired
    private WxMpProperties properties;

    @Test
    @Timeout(120)
    void shouldReconcilePublicMaterialFieldsWithExistingDraftNodeWithoutCreatingDraft() throws Exception {
        clearOwnEvidence();
        String materialId = System.getenv("WECHAT_VIDEO_MATERIAL_MEDIA_ID");
        String draftId = System.getenv("WECHAT_INLINE_VIDEO_SOURCE_DRAFT_MEDIA_ID");
        String draftTitle = System.getenv("WECHAT_INLINE_VIDEO_SOURCE_DRAFT_TITLE");
        assumeTrue(wxMpService != null, "Environment Blocked: 微信组件未装配");
        assumeTrue(hasCredentials(), "Environment Blocked: 缺少真实微信公众号凭据");
        assumeTrue(StringUtils.hasText(materialId),
                "Environment Blocked: 需设置 WECHAT_VIDEO_MATERIAL_MEDIA_ID");
        assumeTrue(StringUtils.hasText(draftId) || StringUtils.hasText(draftTitle),
                "Environment Blocked: 需设置源草稿 ID 或已确认唯一标题");

        try {
            String sourceId = StringUtils.hasText(draftId) ? draftId.trim()
                    : locateUniqueDraft(draftTitle.trim());
            WxMpMaterialVideoInfoResult material = wxMpService.getMaterialService()
                    .materialVideoInfo(materialId.trim());
            assertThat(material).as("公开素材回读响应不能为空").isNotNull();
            WxMpDraftArticles article = WxInlineVideoDraftSmokeTest.selectArticle(
                    wxMpService.getDraftService().getDraft(sourceId),
                    StringUtils.hasText(draftTitle) ? draftTitle.trim() : null);
            List<InlineVideoNodeInspector.Node> nodes = InlineVideoNodeInspector.extract(article.getContent());
            assertThat(nodes).as("源草稿必须含标准视频节点").isNotEmpty();

            var publicFields = new InlineVideoIdentityMappingInspector.PublicMaterialFields(
                    materialId.trim(), material.getTitle(), material.getDescription(), material.getDownUrl());
            var result = inspectObservedPublicFields(publicFields, nodes);

            writeRaw("material-response.txt", "materialMediaId=" + materialId.trim()
                    + "\ntitle=" + value(material.getTitle())
                    + "\ndescription=" + value(material.getDescription())
                    + "\ndownUrl=" + value(material.getDownUrl()) + "\n");
            writeRaw("source-draft-content.html", article.getContent());
            writeRaw("mapping-result.txt", "executedAt=" + Instant.now()
                    + "\ndecision=" + result.decision()
                    + "\nreason=" + result.reason()
                    + "\nfieldMatrix=" + result.fieldMatrix() + "\n");

            System.out.printf("[inline-video-identity-mapping] decision=%s, material=%s, draft=%s, fields=%s%n",
                    result.decision(), InlineVideoIdentityMappingInspector.summary(materialId),
                    InlineVideoIdentityMappingInspector.summary(sourceId), result.fieldMatrix());
            assertThat(result.decision()).isEqualTo(
                    InlineVideoIdentityMappingInspector.Decision.CONDITIONAL_GO);
            assertThat(result.fieldMatrix()).containsKeys(
                    "materialMediaId", "title", "description", "downUrl", "wxvId/mpvid",
                    "publicAssociationObserved");
        } catch (WxErrorException e) {
            assumeTrue(!WxInlineVideoDraftSmokeTest.environmentBlocked(e),
                    "Environment Blocked: errcode="
                            + (e.getError() == null ? -1 : e.getError().getErrorCode()));
            throw e;
        }
    }

    private String locateUniqueDraft(String title) throws WxErrorException {
        List<String> matches = new ArrayList<>();
        int offset = 0;
        while (true) {
            var page = wxMpService.getDraftService().listDraft(offset, 20, 0);
            if (page == null || page.getItems() == null || page.getItems().isEmpty()) {
                break;
            }
            matches.addAll(matchingDraftIds(page.getItems(), title));
            offset += page.getItems().size();
            if (!shouldContinuePage(page.getItems().size(), page.getTotalCount(), offset)) {
                break;
            }
        }
        assertThat(matches).as("标题只读定位必须恰好命中一个源草稿").hasSize(1);
        return matches.get(0);
    }

    private boolean hasCredentials() {
        return StringUtils.hasText(properties.getAppId()) && StringUtils.hasText(properties.getSecret())
                && !"dev-placeholder".equals(properties.getAppId());
    }

    private static void writeRaw(String name, String content) throws IOException {
        Files.createDirectories(RAW);
        Files.writeString(RAW.resolve(name), content == null ? "" : content);
    }

    private static void clearOwnEvidence() throws IOException {
        for (String name : EVIDENCE_FILES) {
            Files.deleteIfExists(RAW.resolve(name));
        }
    }

    static InlineVideoIdentityMappingInspector.Result inspectObservedPublicFields(
            InlineVideoIdentityMappingInspector.PublicMaterialFields material,
            List<InlineVideoNodeInspector.Node> nodes) {
        Set<String> observed = InlineVideoIdentityMappingInspector.publicFieldMatches(
                material, InlineVideoIdentityMappingInspector.nodeIdentities(nodes));
        var evidence = new InlineVideoIdentityMappingInspector.MappingEvidence(
                true, false, false, observed, null);
        return InlineVideoIdentityMappingInspector.inspect(material, nodes, evidence);
    }

    static List<String> matchingDraftIds(List<WxMpDraftItem> items, String title) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (WxMpDraftItem item : items) {
            if (item == null || item.getContent() == null || item.getContent().getNewsItem() == null) {
                continue;
            }
            boolean matched = item.getContent().getNewsItem().stream()
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(article -> title.equals(article.getTitle()));
            if (matched && StringUtils.hasText(item.getMediaId())) {
                matches.add(item.getMediaId());
            }
        }
        return List.copyOf(matches);
    }

    static boolean shouldContinuePage(int pageSize, Integer totalCount, int nextOffset) {
        return pageSize == 20 && (totalCount == null || nextOffset < totalCount);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
