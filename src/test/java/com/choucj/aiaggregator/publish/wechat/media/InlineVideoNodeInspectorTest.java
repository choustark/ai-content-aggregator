package com.choucj.aiaggregator.publish.wechat.media;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InlineVideoNodeInspectorTest {

    private static final String NODE = "<iframe class=\"video_iframe rich_pages\" data-mpvid=\"wxv_1234567890\" "
            + "data-cover=\"https://mmbiz.qpic.cn/cover.jpg\" width=\"640\" height=\"360\" "
            + "src=\"https://mp.weixin.qq.com/mp/readtemplate?t=pages/video_player_tmpl&vid=wxv_1234567890\"></iframe>";

    @Test
    void extractsRealPlayerShapeAndActualCriticalAttributes() {
        List<InlineVideoNodeInspector.Node> nodes = InlineVideoNodeInspector.extract("<p>before</p>" + NODE);

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).tag()).isEqualTo("iframe");
        assertThat(nodes.get(0).criticalAttributes()).containsEntry("data-mpvid", "wxv_1234567890")
                .containsEntry("width", "640").containsKey("src");
        assertThat(nodes.get(0).raw()).isEqualTo(NODE);
    }

    @Test
    void extractsCompleteElementIncludingNestedContentAndPreservesSelfClosingNode() {
        String video = "<video data-mpvid=\"wxv_nested123\"><source src=\"movie.mp4\"></source></video>";
        String selfClosing = "<iframe class=\"video_iframe\" data-mpvid=\"wxv_self123\"/>";

        assertThat(InlineVideoNodeInspector.extract(video).get(0).raw()).isEqualTo(video);
        assertThat(InlineVideoNodeInspector.extract(selfClosing).get(0).raw()).isEqualTo(selfClosing);
    }

    @Test
    void rejectsContentWithoutPlayerInsteadOfAcceptingMaterialIdText() {
        assertThat(InlineVideoNodeInspector.extract("<p>视频素材: media-id-only</p>")).isEmpty();
        assertThatThrownBy(() -> InlineVideoNodeInspector.compare(List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("没有有效视频节点");
    }

    @Test
    void reportsDeletedAndRewrittenCriticalAttributes() {
        var source = InlineVideoNodeInspector.extract(NODE);
        var stored = InlineVideoNodeInspector.extract(NODE.replace(" width=\"640\"", "")
                .replace("wxv_1234567890", "wxv_9999999999"));

        var comparison = InlineVideoNodeInspector.compare(source, stored);

        assertThat(comparison.matches()).isFalse();
        assertThat(comparison.differences()).anyMatch(it -> it.contains("width"))
                .anyMatch(it -> it.contains("data-mpvid"));
    }

    @Test
    void reportsStoredNodeMissingEntirely() {
        var comparison = InlineVideoNodeInspector.compare(InlineVideoNodeInspector.extract(NODE), List.of());

        assertThat(comparison.matches()).isFalse();
        assertThat(comparison.differences()).containsExactly("回读视频节点数量不足: source=1, stored=0");
    }

    @Test
    void ignoresAttributeOrderAndUnrelatedAdditions() {
        var stored = InlineVideoNodeInspector.extract("<iframe title=\"new\" height=\"360\" "
                + "src=\"https://mp.weixin.qq.com/mp/readtemplate?t=pages/video_player_tmpl&vid=wxv_1234567890\" "
                + "data-cover=\"https://mmbiz.qpic.cn/cover.jpg\" data-mpvid=\"wxv_1234567890\" "
                + "class=\"video_iframe rich_pages\" width=\"640\"></iframe>");

        assertThat(InlineVideoNodeInspector.compare(InlineVideoNodeInspector.extract(NODE), stored).matches()).isTrue();
    }

    @Test
    void redactsSecretsAndLongMediaIdentifiers() {
        String snapshot = InlineVideoNodeInspector.redact(
                "access_token=top-secret&media_id=abcdefghijklmnopqrstuvwxyz cookie=session-secret "
                        + "secret = 'quoted-secret' cookie = \"quoted-cookie\" "
                        + "data-mpvid=\"wxv_1234567890\" src=\"/player?vid=plainVid987654\"");

        assertThat(snapshot).doesNotContain("top-secret", "session-secret", "abcdefghijklmnopqrstuvwxyz",
                        "quoted-secret", "quoted-cookie", "wxv_1234567890", "plainVid987654")
                .contains("access_token=<redacted>", "media_id=abcdefgh...<redacted>", "cookie=<redacted>",
                        "secret = '<redacted>'", "cookie = \"<redacted>\"",
                        "data-mpvid=\"wxv_1234...<redacted>", "?vid=plainVid...<redacted>");
    }

    @Test
    void rejectsNullOrBlankInput() {
        assertThatThrownBy(() -> InlineVideoNodeInspector.extract(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InlineVideoNodeInspector.extract("  ")).isInstanceOf(IllegalArgumentException.class);
    }
}
