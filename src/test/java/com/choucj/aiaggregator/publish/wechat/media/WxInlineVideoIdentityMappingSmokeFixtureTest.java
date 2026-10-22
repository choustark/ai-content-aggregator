package com.choucj.aiaggregator.publish.wechat.media;

import me.chanjar.weixin.mp.bean.draft.WxMpDraftArticles;
import me.chanjar.weixin.mp.bean.draft.WxMpDraftInfo;
import me.chanjar.weixin.mp.bean.draft.WxMpDraftItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WxInlineVideoIdentityMappingSmokeFixtureTest {

    private static final String WXV_ID = "wxv_1234567890abcdef";

    @Test
    void shouldObserveExactNodeIdentityInPublicMaterialField() {
        var material = material("https://example.invalid/video?vid=" + WXV_ID);
        var result = WxInlineVideoIdentityMappingSmokeTest.inspectObservedPublicFields(material, nodes());

        assertThat(result.decision()).isEqualTo(
                InlineVideoIdentityMappingInspector.Decision.CONDITIONAL_GO);
        assertThat(result.fieldMatrix()).containsEntry("publicAssociationObserved", "yes");
        assertThat(result.reason()).contains("公开显式关联");
    }

    @Test
    void shouldReportNoObservedAssociationWhenPublicFieldsDoNotMatch() {
        var result = WxInlineVideoIdentityMappingSmokeTest.inspectObservedPublicFields(
                material("https://example.invalid/video"), nodes());

        assertThat(result.decision()).isEqualTo(
                InlineVideoIdentityMappingInspector.Decision.CONDITIONAL_GO);
        assertThat(result.fieldMatrix()).containsEntry("publicAssociationObserved", "no");
        assertThat(result.reason()).contains("未提供");
    }

    @Test
    void shouldBlockWhenPlayerNodeHasNoIdentity() {
        var nodes = InlineVideoNodeInspector.extract(
                "<iframe class=\"video_iframe\" title=\"player\"></iframe>");
        var result = WxInlineVideoIdentityMappingSmokeTest.inspectObservedPublicFields(
                material("https://example.invalid/video"), nodes);

        assertThat(result.decision()).isEqualTo(
                InlineVideoIdentityMappingInspector.Decision.ENVIRONMENT_BLOCKED);
        assertThat(result.fieldMatrix()).containsEntry("wxvId/mpvid", "absent");
    }

    @Test
    void shouldContinueFullPageWhenTotalCountIsMissingAndIgnoreNullDraftEntries() {
        List<WxMpDraftItem> fullPage = new ArrayList<>();
        fullPage.add(null);
        fullPage.add(item("id-empty", null));
        List<WxMpDraftArticles> nullArticle = new ArrayList<>();
        nullArticle.add(null);
        fullPage.add(item("id-null-article", nullArticle));
        fullPage.add(item("id-match", List.of(new WxMpDraftArticles().setTitle("目标"))));
        while (fullPage.size() < 20) {
            fullPage.add(item("id-" + fullPage.size(),
                    List.of(new WxMpDraftArticles().setTitle("其他"))));
        }

        assertThat(WxInlineVideoIdentityMappingSmokeTest.matchingDraftIds(fullPage, "目标"))
                .containsExactly("id-match");
        assertThat(WxInlineVideoIdentityMappingSmokeTest.shouldContinuePage(20, null, 20)).isTrue();
        assertThat(WxInlineVideoIdentityMappingSmokeTest.shouldContinuePage(19, null, 19)).isFalse();
        assertThat(WxInlineVideoIdentityMappingSmokeTest.shouldContinuePage(20, 20, 20)).isFalse();
    }

    private static InlineVideoIdentityMappingInspector.PublicMaterialFields material(String downUrl) {
        return new InlineVideoIdentityMappingInspector.PublicMaterialFields(
                "material-123456", "title", "description", downUrl);
    }

    private static List<InlineVideoNodeInspector.Node> nodes() {
        return InlineVideoNodeInspector.extract(
                "<iframe class=\"video_iframe\" data-mpvid=\"" + WXV_ID + "\"></iframe>");
    }

    private static WxMpDraftItem item(String mediaId, List<WxMpDraftArticles> articles) {
        WxMpDraftItem item = new WxMpDraftItem();
        item.setMediaId(mediaId);
        if (articles != null) {
            item.setContent(new WxMpDraftInfo().setNewsItem(articles));
        }
        return item;
    }
}
