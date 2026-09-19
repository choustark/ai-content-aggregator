package com.choucj.aiaggregator.ops;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Story 10.3 的既有 HTTP/SDK 边界没有遗漏统一 recorder 接线。 */
class ExternalBoundaryObservabilityTest {

    private static final List<String> BOUNDARIES = List.of(
            "source/github/client/GitHubClientImpl.java",
            "source/twitter/client/RSSHubClient.java",
            "source/twitter/client/FxTwitterClient.java",
            "source/twitter/discovery/ApifyDiscoveryClient.java",
            "source/twitter/discovery/XAuthorScraperDiscoveryClient.java",
            "source/twitter/media/MediaDownloadClient.java",
            "common/client/LangChain4jLlmClient.java",
            "content/embedding/EmbeddingServiceImpl.java",
            "publish/wechat/client/WxJavaWeChatClient.java",
            "publish/wechat/WeChatPublisher.java",
            "publish/wechat/WeChatMaterialTool.java");

    @Test
    void should_wire_slow_operation_recorder_when_external_boundary_is_inspected() throws Exception {
        Path root = Path.of("src/main/java/com/choucj/aiaggregator");
        for (String relative : BOUNDARIES) {
            String source = Files.readString(root.resolve(relative));
            assertThat(source)
                    .as("边界 %s 应注入统一 recorder", relative)
                    .contains("SlowOperationRecorder")
                    .contains("slowOperationRecorder.observe(");
        }
    }
}
