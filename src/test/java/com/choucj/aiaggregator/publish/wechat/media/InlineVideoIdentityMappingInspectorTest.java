package com.choucj.aiaggregator.publish.wechat.media;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InlineVideoIdentityMappingInspectorTest {

    private static final String MATERIAL_ID = "material-secret-123456";
    private static final String WXV_ID = "wxv_1234567890abcdef";

    @Test
    void shouldReturnConditionalGoWhenPublicMaterialFieldsHaveNoExplicitAssociation() {
        var result = inspect(new InlineVideoIdentityMappingInspector.MappingEvidence(
                false, false, false, Set.of(), null));

        assertThat(result.decision()).isEqualTo(
                InlineVideoIdentityMappingInspector.Decision.CONDITIONAL_GO);
        assertThat(result.fieldMatrix().get("materialMediaId"))
                .startsWith("present(len=").doesNotContain(MATERIAL_ID);
        assertThat(result.fieldMatrix().get("wxvId/mpvid")).doesNotContain(WXV_ID);
    }

    @Test
    void shouldReturnGoOnlyForDocumentedRepeatedExplicitPublicAssociation() {
        var result = inspect(new InlineVideoIdentityMappingInspector.MappingEvidence(
                true, true, false, Set.of(WXV_ID), null));

        assertThat(result.decision()).isEqualTo(InlineVideoIdentityMappingInspector.Decision.GO);
    }

    @Test
    void shouldKeepSingleExplicitObservationConditional() {
        var result = inspect(new InlineVideoIdentityMappingInspector.MappingEvidence(
                true, false, false, Set.of(WXV_ID), null));

        assertThat(result.decision()).isEqualTo(
                InlineVideoIdentityMappingInspector.Decision.CONDITIONAL_GO);
    }

    @Test
    void shouldReturnNoGoForPrivateLoginDependency() {
        var result = inspect(new InlineVideoIdentityMappingInspector.MappingEvidence(
                false, false, true, Set.of(), null));

        assertThat(result.decision()).isEqualTo(InlineVideoIdentityMappingInspector.Decision.NO_GO);
    }

    @Test
    void shouldReturnEnvironmentBlockedBeforeCapabilityConclusion() {
        var result = inspect(new InlineVideoIdentityMappingInspector.MappingEvidence(
                false, false, false, Set.of(), "missing credentials"));

        assertThat(result.decision()).isEqualTo(
                InlineVideoIdentityMappingInspector.Decision.ENVIRONMENT_BLOCKED);
    }

    @Test
    void shouldReturnEnvironmentBlockedWhenMaterialReadbackIsMissing() {
        var nodes = InlineVideoNodeInspector.extract(
                "<iframe class=\"video_iframe\" data-mpvid=\"" + WXV_ID + "\"></iframe>");
        var result = InlineVideoIdentityMappingInspector.inspect(null, nodes,
                new InlineVideoIdentityMappingInspector.MappingEvidence(
                        false, false, false, Set.of(), null));

        assertThat(result.decision()).isEqualTo(
                InlineVideoIdentityMappingInspector.Decision.ENVIRONMENT_BLOCKED);
    }

    @Test
    void shouldIgnoreIdentityOnlyPresentInChildContent() {
        var nodes = InlineVideoNodeInspector.extract(
                "<iframe class=\"video_iframe\" title=\"player\"><span>" + WXV_ID
                        + "</span></iframe>");

        assertThat(InlineVideoIdentityMappingInspector.nodeIdentities(nodes)).isEmpty();
    }

    private static InlineVideoIdentityMappingInspector.Result inspect(
            InlineVideoIdentityMappingInspector.MappingEvidence evidence) {
        var material = new InlineVideoIdentityMappingInspector.PublicMaterialFields(
                MATERIAL_ID, "title", "description", "https://example.invalid/video");
        var nodes = InlineVideoNodeInspector.extract(
                "<iframe class=\"video_iframe\" data-mpvid=\"" + WXV_ID + "\"></iframe>");
        return InlineVideoIdentityMappingInspector.inspect(material, nodes, evidence);
    }
}
