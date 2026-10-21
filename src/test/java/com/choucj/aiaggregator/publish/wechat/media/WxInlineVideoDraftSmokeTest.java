package com.choucj.aiaggregator.publish.wechat.media;

import com.binarywang.spring.starter.wxjava.mp.properties.WxMpProperties;
import com.choucj.aiaggregator.publish.wechat.WeChatThumbMediaIdResolver;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.draft.WxMpAddDraft;
import me.chanjar.weixin.mp.bean.draft.WxMpDraftArticles;
import me.chanjar.weixin.mp.bean.draft.WxMpDraftInfo;
import me.chanjar.weixin.mp.bean.draft.WxMpDraftItem;
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
import java.nio.file.StandardOpenOption;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 使用后台真实节点验证公开 draft/get -> draft/add -> draft/get 的隔离 smoke test。 */
@SpringBootTest
@TestPropertySource(properties = {"wechat.mp.enabled=true", "archive.enabled=false", "schedule.run-on-startup=false"})
@Tag("external")
class WxInlineVideoDraftSmokeTest {

    private static final Path ROOT = Path.of("target/spike-inline-video");
    private static final Path RAW = ROOT.resolve("raw");
    private static final Path CREATED = ROOT.resolve("created-artifacts.txt");

    @Autowired(required = false)
    private WxMpService wxMpService;
    @Autowired(required = false)
    private WeChatThumbMediaIdResolver thumbResolver;
    @Autowired
    private WxMpProperties properties;

    @Test
    @Timeout(180)
    void shouldRoundTripRealBackendGeneratedInlineVideoNode() throws Exception {
        String configuredId = System.getenv("WECHAT_INLINE_VIDEO_SOURCE_DRAFT_MEDIA_ID");
        String configuredTitle = System.getenv("WECHAT_INLINE_VIDEO_SOURCE_DRAFT_TITLE");
        assumeTrue(wxMpService != null && thumbResolver != null, "Environment Blocked: 微信组件未装配");
        assumeTrue(hasCredentials(), "Environment Blocked: 缺少真实微信公众号凭据");
        assumeTrue(StringUtils.hasText(configuredId) || StringUtils.hasText(configuredTitle),
                "Environment Blocked: 需设置源草稿 ID 或已确认唯一的标题");

        try {
            String sourceId = StringUtils.hasText(configuredId) ? configuredId.trim() : locateUniqueDraft(configuredTitle.trim());
            WxMpDraftInfo sourceDraft = wxMpService.getDraftService().getDraft(sourceId);
            WxMpDraftArticles source = selectArticle(sourceDraft,
                    StringUtils.hasText(configuredTitle) ? configuredTitle.trim() : null);
            String sourceContent = source.getContent();
            List<InlineVideoNodeInspector.Node> sourceNodes = InlineVideoNodeInspector.extract(sourceContent);
            if (sourceNodes.isEmpty()) {
                writeRaw("diagnostic-summary.txt",
                        "源草稿没有识别到后台生成的视频播放器节点；未创建第二草稿。"
                                + System.lineSeparator() + "sourceIdPrefix=" + prefix(sourceId));
            }
            assertThat(sourceNodes).as("源草稿必须含后台生成的有效视频播放器节点").isNotEmpty();

            String marker = "inline-video-roundtrip-" + UUID.randomUUID();
            WxMpDraftArticles copy = new WxMpDraftArticles();
            copy.setTitle(marker);
            copy.setThumbMediaId(thumbResolver.resolve());
            copy.setContent("<p>" + marker + "</p>" + sourceNodes.stream()
                    .map(InlineVideoNodeInspector.Node::raw).reduce("", String::concat));

            String createdId = wxMpService.getDraftService().addDraft(new WxMpAddDraft(List.of(copy)));
            assertThat(createdId).as("draft/add 必须返回新草稿 ID").isNotBlank();
            append(CREATED, Instant.now() + " draft " + createdId + " marker=" + marker + System.lineSeparator());

            String storedContent = selectArticle(wxMpService.getDraftService().getDraft(createdId), marker).getContent();
            var comparison = InlineVideoNodeInspector.compare(sourceNodes,
                    InlineVideoNodeInspector.extract(storedContent));
            writeRaw("source-content.html", sourceContent);
            writeRaw("stored-content.html", storedContent);
            writeRaw("comparison.txt", comparison.differences().isEmpty()
                    ? "MATCH" : String.join(System.lineSeparator(), comparison.differences()));
            writeRaw("redacted-node-snapshot.txt", sourceNodes.stream()
                    .map(InlineVideoNodeInspector.Node::redactedSnapshot)
                    .reduce("", (left, right) -> left + right + System.lineSeparator()));

            System.out.printf("[inline-video-spike] round-trip=%s, sourceIdPrefix=%s, createdIdPrefix=%s, marker=%s%n",
                    comparison.matches(), prefix(sourceId), prefix(createdId), marker);
            assertThat(comparison.differences()).as("播放器关键结构不得被删除或改写").isEmpty();
        } catch (WxErrorException e) {
            assumeTrue(!environmentBlocked(e), "Environment Blocked: " + safeError(e));
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
            for (WxMpDraftItem item : page.getItems()) {
                WxMpDraftInfo content = item.getContent();
                if (content != null && content.getNewsItem() != null && content.getNewsItem().stream()
                        .anyMatch(article -> title.equals(article.getTitle()))) {
                    matches.add(item.getMediaId());
                }
            }
            offset += page.getItems().size();
            if (page.getTotalCount() == null || offset >= page.getTotalCount()) {
                break;
            }
        }
        assertThat(matches).as("标题只读定位必须恰好命中一个源草稿").hasSize(1);
        return matches.get(0);
    }

    static WxMpDraftArticles selectArticle(WxMpDraftInfo info, String expectedTitle) {
        assertThat(info).as("draft/get 响应不能为空").isNotNull();
        assertThat(info.getNewsItem()).as("源草稿至少包含一篇文章").isNotEmpty();
        if (StringUtils.hasText(expectedTitle)) {
            List<WxMpDraftArticles> matches = info.getNewsItem().stream()
                    .filter(article -> expectedTitle.equals(article.getTitle()))
                    .toList();
            assertThat(matches).as("draft/get 中标题必须恰好匹配一篇文章").hasSize(1);
            return matches.get(0);
        }
        return info.getNewsItem().get(0);
    }

    private boolean hasCredentials() {
        return StringUtils.hasText(properties.getAppId()) && StringUtils.hasText(properties.getSecret())
                && !"dev-placeholder".equals(properties.getAppId());
    }

    static boolean environmentBlocked(WxErrorException e) {
        int code = e.getError() == null ? -1 : e.getError().getErrorCode();
        if (code == 40164 || code == 45009 || code == 40001 || code == 40013 || code == 40014) {
            return true;
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnknownHostException || cause instanceof ConnectException
                    || cause instanceof SocketTimeoutException) {
                return true;
            }
            String message = cause.getMessage() == null ? "" : cause.getMessage();
            if (message.contains("UnknownHostException") || message.contains("nodename nor servname provided")
                    || message.contains("Connection refused") || message.contains("connect timed out")) {
                return true;
            }
        }
        return false;
    }

    private static String safeError(WxErrorException e) {
        int code = e.getError() == null ? -1 : e.getError().getErrorCode();
        return "errcode=" + code + ", type=" + e.getClass().getSimpleName();
    }

    private static void writeRaw(String name, String content) throws IOException {
        Files.createDirectories(RAW);
        Files.writeString(RAW.resolve(name), content == null ? "" : content);
    }

    private static void append(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String prefix(String value) {
        return value.substring(0, Math.min(8, value.length()));
    }
}
