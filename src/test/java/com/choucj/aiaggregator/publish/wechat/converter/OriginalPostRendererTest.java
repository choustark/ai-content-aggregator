package com.choucj.aiaggregator.publish.wechat.converter;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.model.Article;
import com.choucj.aiaggregator.source.twitter.model.MediaUploadStatus;
import com.choucj.aiaggregator.source.twitter.model.PublishabilityStatus;
import com.choucj.aiaggregator.source.twitter.model.Tweet;
import com.choucj.aiaggregator.source.twitter.model.TweetAccessStatus;
import com.choucj.aiaggregator.source.twitter.model.TweetMedia;
import com.choucj.aiaggregator.source.twitter.model.TweetMediaType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 8.5: OriginalPostRenderer 确定性渲染器单元测试.
 *
 * <p>纯函数直构 (new OriginalPostRenderer())，无 Mockito — 渲染器零外部依赖 (AC9)。
 * 覆盖 AC11 列出的全部场景类 + T5.2 反向断言 (img src 仅来自 wechatUrl) + T5.3 确定性断言。
 *
 * <p>引用源: Story 8.5(创建)。
 */
class OriginalPostRendererTest {

    private static final String TWEET_ID = "2090838453126566066";
    private static final String TWEET_URL = "https://x.com/testuser/status/" + TWEET_ID;
    private static final String WECHAT_URL = "https://mmbiz.qpic.cn/mmbiz_png/abc/123?wx_fmt=png";
    private static final String SOURCE_URL = "https://pbs.twimg.com/media/xyz.jpg";
    private static final String PREVIEW_URL = "https://pbs.twimg.com/media/xyz?format=jpg&name=small";
    private static final String VIDEO_SOURCE_URL = "https://video.twimg.com/ext_tw_video/1/pu/vid/abc/720x1280/xyz.mp4";

    /** 渲染器输出的 img 标签固定形态 (无属性顺序歧义，确定性规格的一部分)。 */
    private static final Pattern IMG_SRC_PATTERN = Pattern.compile("<img src=\"([^\"]*)\"/>");

    private final OriginalPostRenderer renderer = new OriginalPostRenderer();

    // ===== 构造辅助 =====

    private Tweet.TweetBuilder baseTweet() {
        return Tweet.builder()
                .id(TWEET_ID)
                .author("testuser")
                .url(TWEET_URL)
                .formattedText("hello world");
    }

    private TweetMedia.TweetMediaBuilder photo(String id, String wechatUrl) {
        return TweetMedia.builder()
                .id(id)
                .type(TweetMediaType.PHOTO)
                .sourceUrl(SOURCE_URL)
                .previewImageUrl(PREVIEW_URL)
                .uploadStatus(wechatUrl == null ? MediaUploadStatus.FAILED : MediaUploadStatus.UPLOADED)
                .wechatUrl(wechatUrl);
    }

    private List<String> extractImgSrcs(String html) {
        Matcher matcher = IMG_SRC_PATTERN.matcher(html);
        List<String> srcs = new ArrayList<>();
        while (matcher.find()) {
            srcs.add(matcher.group(1));
        }
        return srcs;
    }

    /**
     * T5.2 反向断言 helper (CR Round 1 patch#8/#10 统一应用到所有含 img 场景):
     * img src ⊆ embeddedImageUrls — 注意 HTML 中是 escapeHtml 转义后形态，
     * 比较前先对 embeddedImageUrls 逐元素转义；外部 CDN 直链绝不作为 img src。
     */
    private void assertImgSrcsOnlyFromWechat(OriginalPostRenderResult result) {
        List<String> imgSrcs = extractImgSrcs(result.html());
        List<String> escapedEmbedded = result.embeddedImageUrls().stream()
                .map(ArticleToWxArticleConverter::escapeHtml).toList();
        assertThat(imgSrcs).allSatisfy(escapedEmbedded::contains);
        assertThat(imgSrcs).doesNotContain(SOURCE_URL, PREVIEW_URL, VIDEO_SOURCE_URL);
        assertThat(result.html()).doesNotContain("<img src=\"https://pbs.twimg.com");
        assertThat(result.html()).doesNotContain("<img src=\"https://video.twimg.com");
    }

    // ===== AC1: 确定性正文结构渲染 =====

    @Test
    void should_map_newlines_to_paragraphs_and_br_when_text_has_various_line_breaks() {
        Tweet tweet = baseTweet().formattedText("第一段\r\n\r\n第二行1\n第二行2\r第三行").build();

        OriginalPostRenderResult result = renderer.render(tweet, null);

        assertThat(result.html()).contains("<p>第一段</p>");
        assertThat(result.html()).contains("<p>第二行1<br/>第二行2<br/>第三行</p>");
    }

    @Test
    void should_escape_html_when_text_contains_script_injection() {
        Tweet tweet = baseTweet()
                .formattedText("a <script>alert('x')</script> & \"quoted\" 'single'")
                .build();

        OriginalPostRenderResult result = renderer.render(tweet, null);

        assertThat(result.html())
                .contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;")
                .contains("&amp;")
                .contains("&quot;quoted&quot;")
                .doesNotContain("<script>");
    }

    @Test
    void should_select_text_source_by_priority_when_higher_priority_blank() {
        // formattedText 缺失 → rawText
        Tweet rawOnly = baseTweet().formattedText(null).rawText("raw 内容").build();
        assertThat(renderer.render(rawOnly, null).html()).contains("raw 内容");

        // formattedText 空白 + rawText 缺失 → content
        Tweet contentOnly = baseTweet().formattedText("   ").rawText(null).content("plain 内容").build();
        assertThat(renderer.render(contentOnly, null).html()).contains("plain 内容");
    }

    @Test
    void should_append_missing_links_when_url_not_contained_in_text() {
        Tweet tweet = baseTweet()
                .formattedText("看这个 https://example.com/a 很不错")
                .links(Arrays.asList("https://example.com/a", "https://example.com/b"))
                .build();

        String html = renderer.render(tweet, null).html();

        assertThat(html).contains("原文链接补遗");
        assertThat(html).contains("<a href=\"https://example.com/b\">https://example.com/b</a>");
        // 已在文本中的链接不重复列出
        assertThat(html).doesNotContain("<a href=\"https://example.com/a\">https://example.com/a</a>");
    }

    @Test
    void should_omit_appendix_when_all_links_contained_in_text() {
        Tweet tweet = baseTweet()
                .formattedText("链接 https://example.com/a 与 https://example.com/b")
                .links(Arrays.asList("https://example.com/a", "https://example.com/b"))
                .build();

        assertThat(renderer.render(tweet, null).html()).doesNotContain("原文链接补遗");
    }

    @Test
    void should_not_render_mentions_separately() {
        Tweet tweet = baseTweet().formattedText("Hi there").mentions(List.of("@friend")).build();

        assertThat(renderer.render(tweet, null).html()).doesNotContain("@friend");
    }

    // ===== AC2/AC3: 图片只用微信 URL + 媒体顺序 =====

    @Test
    void should_render_img_with_wechat_url_when_single_photo_prepared() {
        Tweet tweet = baseTweet().build();
        TweetMedia photo = photo("m1", WECHAT_URL).build();

        OriginalPostRenderResult result = renderer.render(tweet, List.of(photo));

        assertThat(result.html()).contains("<img src=\"" + WECHAT_URL + "\"/>");
        assertThat(result.embeddedImageCount()).isEqualTo(1);
        assertThat(result.degradedMediaCount()).isZero();
        assertThat(result.embeddedImageUrls()).containsExactly(WECHAT_URL);
        assertImgSrcsOnlyFromWechat(result);
    }

    @Test
    void should_preserve_media_order_when_multiple_photos_rendered() {
        Tweet tweet = baseTweet().build();
        TweetMedia first = photo("m1", "https://mmbiz.qpic.cn/mmbiz_png/a/1.png").build();
        TweetMedia second = photo("m2", "https://mmbiz.qpic.cn/mmbiz_png/b/2.png").build();
        // order 字段与 list 顺序相反 — list 顺序为权威，不重排
        TweetMedia firstWithOrder = first.toBuilder().order(1).build();
        TweetMedia secondWithOrder = second.toBuilder().order(0).build();

        OriginalPostRenderResult result =
                renderer.render(tweet, List.of(firstWithOrder, secondWithOrder));
        String html = result.html();

        int indexFirst = html.indexOf("https://mmbiz.qpic.cn/mmbiz_png/a/1.png");
        int indexSecond = html.indexOf("https://mmbiz.qpic.cn/mmbiz_png/b/2.png");
        assertThat(indexFirst).isGreaterThan(-1);
        assertThat(indexSecond).isGreaterThan(indexFirst);
        assertImgSrcsOnlyFromWechat(result);
    }

    @Test
    void should_embed_image_when_upload_status_skipped_with_wechat_url() {
        // 8.4 幂等契约: SKIPPED 也可能携带 wechatUrl，可用性判据 = wechatUrl 非空
        Tweet tweet = baseTweet().build();
        TweetMedia skipped = photo("m1", WECHAT_URL)
                .uploadStatus(MediaUploadStatus.SKIPPED)
                .build();

        OriginalPostRenderResult result = renderer.render(tweet, List.of(skipped));

        assertThat(result.html()).contains("<img src=\"" + WECHAT_URL + "\"/>");
        assertThat(result.embeddedImageCount()).isEqualTo(1);
        assertImgSrcsOnlyFromWechat(result);
    }

    @Test
    void should_not_embed_when_publishability_blocked() {
        Tweet tweet = baseTweet().build();
        TweetMedia blocked = photo("m1", WECHAT_URL)
                .publishability(PublishabilityStatus.BLOCKED)
                .build();

        OriginalPostRenderResult result = renderer.render(tweet, List.of(blocked));

        assertThat(result.html()).doesNotContain("<img");
        assertThat(result.html()).contains("该媒体不适宜自动呈现");
        assertThat(result.embeddedImageCount()).isZero();
        assertThat(result.degradedMediaCount()).isEqualTo(1);
    }

    @Test
    void should_embed_when_publishability_null() {
        // 老 sidecar 反序列化 null 语义 (D3 警示 #1): publishability null → 按 UNKNOWN 可嵌入
        Tweet tweet = baseTweet().build();
        TweetMedia legacy = photo("m1", WECHAT_URL).publishability(null).build();

        OriginalPostRenderResult result = renderer.render(tweet, List.of(legacy));

        assertThat(result.html()).contains("<img src=\"" + WECHAT_URL + "\"/>");
        assertImgSrcsOnlyFromWechat(result);
    }

    // ===== AC4: 引用推上下文 =====

    @Test
    void should_render_quote_block_when_quoted_tweet_url_present() {
        Tweet tweet = baseTweet()
                .quotedTweetUrl("https://x.com/other/status/999")
                .quotedTweetText("引用推的 <内容>")
                .build();

        String html = renderer.render(tweet, null).html();

        assertThat(html).contains("<blockquote>");
        assertThat(html).contains("引用推的 &lt;内容&gt;");
        assertThat(html).contains("<a href=\"https://x.com/other/status/999\">查看引用推</a>");
    }

    @Test
    void should_use_default_copy_when_quoted_tweet_text_blank() {
        Tweet tweet = baseTweet()
                .quotedTweetUrl("https://x.com/other/status/999")
                .quotedTweetText(null)
                .build();

        assertThat(renderer.render(tweet, null).html()).contains("(引用内容不可得)");
    }

    @Test
    void should_truncate_quoted_text_when_longer_than_200_codepoints() {
        String longText = "😀".repeat(250);
        Tweet tweet = baseTweet()
                .quotedTweetUrl("https://x.com/other/status/999")
                .quotedTweetText(longText)
                .build();

        String html = renderer.render(tweet, null).html();
        int start = html.indexOf("<blockquote><p>") + "<blockquote><p>".length();
        String body = html.substring(start, html.indexOf("</p>", start));
        assertThat(body.codePointCount(0, body.length())).isEqualTo(200);
    }

    @Test
    void should_not_render_quote_block_when_quoted_tweet_url_null() {
        Tweet tweet = baseTweet().quotedTweetUrl(null).quotedTweetText("some quote").build();

        assertThat(renderer.render(tweet, null).html()).doesNotContain("<blockquote>");
    }

    // ===== AC5: 视频/GIF 降级 =====

    @Test
    void should_degrade_video_when_media_type_is_video() {
        Tweet tweet = baseTweet().build();
        TweetMedia video = TweetMedia.builder()
                .id("m1")
                .type(TweetMediaType.VIDEO)
                .sourceUrl(VIDEO_SOURCE_URL)
                .previewImageUrl(PREVIEW_URL)
                .build();

        OriginalPostRenderResult result = renderer.render(tweet, List.of(video));

        assertThat(result.html()).contains("视频内容暂不支持稳定内嵌");
        assertThat(result.html()).contains("<a href=\"" + TWEET_URL + "\">原文链接</a>");
        assertThat(result.html()).doesNotContain("<img");
        assertThat(result.embeddedImageCount()).isZero();
        assertThat(result.degradedMediaCount()).isEqualTo(1);
    }

    @Test
    void should_degrade_gif_when_media_type_is_gif() {
        Tweet tweet = baseTweet().build();
        TweetMedia gif = TweetMedia.builder()
                .id("m1")
                .type(TweetMediaType.GIF)
                .sourceUrl(VIDEO_SOURCE_URL)
                .previewImageUrl(PREVIEW_URL)
                .build();

        OriginalPostRenderResult result = renderer.render(tweet, List.of(gif));

        assertThat(result.html()).contains("动图暂不走正式图片接口");
        assertThat(result.html()).doesNotContain("<img");
        assertThat(result.embeddedImageUrls()).isEmpty();
    }

    @Test
    void should_render_unknown_degradation_when_media_type_null() {
        Tweet tweet = baseTweet().build();
        TweetMedia legacy = photo("m1", WECHAT_URL).type(null).build();

        OriginalPostRenderResult result = renderer.render(tweet, List.of(legacy));

        assertThat(result.html()).contains("未识别的媒体类型");
        assertThat(result.html()).doesNotContain("<img");
        assertThat(result.degradedMediaCount()).isEqualTo(1);
    }

    @Test
    void should_render_unknown_degradation_when_media_element_null() {
        // D3 警示 #4: 列表含 null 元素不得静默 skip，按 UNKNOWN 走降级提示
        Tweet tweet = baseTweet().build();

        OriginalPostRenderResult result = renderer.render(tweet, Arrays.asList(photo("m1", WECHAT_URL).build(), null));

        assertThat(result.html()).contains("未识别的媒体类型");
        assertThat(result.embeddedImageCount()).isEqualTo(1);
        assertThat(result.degradedMediaCount()).isEqualTo(1);
    }

    // ===== AC6: 媒体失败 reviewer-readable 提示 =====

    @Test
    void should_show_sanitized_failure_reason_when_photo_without_wechat_url() {
        Tweet tweet = baseTweet().build();
        TweetMedia failed = photo("m1", null)
                .failureReason("下载失败 <timeout> after\nretry")
                .build();

        String html = renderer.render(tweet, List.of(failed)).html();

        assertThat(html).contains("图片未能呈现");
        // 单行化 + HTML 转义后的 reason 摘要
        assertThat(html).contains("下载失败 &lt;timeout&gt; after retry");
        assertThat(html).contains("<a href=\"" + TWEET_URL + "\">原文链接</a>");
    }

    @Test
    void should_show_fixed_copy_when_photo_failure_reason_blank() {
        Tweet tweet = baseTweet().build();
        TweetMedia failed = photo("m1", null).failureReason(null).build();

        assertThat(renderer.render(tweet, List.of(failed)).html()).contains("图片未上传至微信");
    }

    @Test
    void should_use_plain_copy_when_tweet_url_blank() {
        Tweet tweet = baseTweet().url(null).build();
        TweetMedia video = TweetMedia.builder().id("m1").type(TweetMediaType.VIDEO).build();

        String html = renderer.render(tweet, List.of(video)).html();

        assertThat(html).contains("请到 X 原帖查看");
        // 不伪造 URL
        assertThat(html).doesNotContain("<a href=");
    }

    // ===== AC7: 推文级致命失败 =====

    @Test
    void should_throw_non_retryable_when_tweet_null() {
        assertThatThrownBy(() -> renderer.render(null, null))
                .isInstanceOf(NonRetryableException.class);
    }

    @Test
    void should_throw_non_retryable_when_tweet_id_blank() {
        Tweet tweet = baseTweet().id("   ").build();

        assertThatThrownBy(() -> renderer.render(tweet, null))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("tweetId=");
    }

    @Test
    void should_throw_non_retryable_when_access_status_blocked() {
        Tweet tweet = baseTweet().accessStatus(TweetAccessStatus.DELETED).build();

        assertThatThrownBy(() -> renderer.render(tweet, null))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("source access blocked");
    }

    @Test
    void should_throw_non_retryable_when_all_text_sources_blank() {
        Tweet tweet = baseTweet()
                .formattedText("  ")
                .rawText(null)
                .content("")
                .build();

        assertThatThrownBy(() -> renderer.render(tweet, null))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("all text sources blank");
    }

    // ===== AC8: footer 与 Article 组装 =====

    @Test
    void should_render_footer_with_author_and_link_when_url_present() {
        Tweet tweet = baseTweet().build();

        String html = renderer.render(tweet, null).html();

        assertThat(html).contains("<hr/>");
        assertThat(html).contains("<strong>来源:</strong> X 原帖 by @testuser");
        assertThat(html.endsWith("<p><a href=\"" + TWEET_URL + "\">原文链接</a></p>\n")).isTrue();
    }

    @Test
    void should_render_footer_without_author_when_author_blank() {
        Tweet tweet = baseTweet().author(null).build();

        String html = renderer.render(tweet, null).html();

        assertThat(html).contains("<strong>来源:</strong> X 原帖</p>");
        assertThat(html).doesNotContain("by @");
    }

    @Test
    void should_build_full_article_when_to_article_called() {
        Tweet tweet = baseTweet()
                .formattedText("标题行\n\n正文内容")
                .innovationScore(null)
                .build();
        OriginalPostRenderResult result = renderer.render(tweet, null);

        Article article = renderer.toArticle(tweet, result);

        assertThat(article.getId()).isEqualTo("tw-" + TWEET_ID);
        assertThat(article.getTitle()).isEqualTo("标题行");
        assertThat(article.getDigest()).isEqualTo("标题行 正文内容");
        assertThat(article.getContent()).isEqualTo(result.html());
        // 原帖复现非 AI 生成 (AR8 诚实标识)；无 LLM 评分 (D3: 不读 tweet.innovationScore 拆箱)
        assertThat(article.isAiGenerated()).isFalse();
        assertThat(article.getInnovationScore()).isZero();
        assertThat(article.getOriginalUrl()).isEqualTo(TWEET_URL);
        assertThat(article.getSource()).isEqualTo("来源:@testuser");
        assertThat(article.getCreatedAt()).isNotNull();
    }

    @Test
    void should_use_default_source_when_author_blank_in_to_article() {
        Tweet tweet = baseTweet().author(null).build();
        OriginalPostRenderResult result = renderer.render(tweet, null);

        assertThat(renderer.toArticle(tweet, result).getSource()).isEqualTo("来源:X 原帖");
    }

    @Test
    void should_throw_non_retryable_when_tweet_id_contains_illegal_chars() {
        Tweet tweet = baseTweet().id("bad id!").build();
        OriginalPostRenderResult result =
                renderer.render(baseTweet().build(), null);

        assertThatThrownBy(() -> renderer.toArticle(tweet, result))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("非法 tweetId");
    }

    // ===== AC10: title/digest 确定性派生 =====

    @Test
    void should_truncate_title_and_digest_when_text_exceeds_codepoint_limits() {
        // 70 个 emoji (每个 1 codepoint / 2 char) — title 截 64 cp 不切断代理对
        String emojiText = "😀".repeat(70);
        Tweet tweet = baseTweet().formattedText(emojiText).build();

        OriginalPostRenderResult result = renderer.render(tweet, null);

        assertThat(result.title().codePointCount(0, result.title().length())).isEqualTo(64);
        // 精确断言: 64 个完整 emoji，代理对未被切断
        assertThat(result.title()).isEqualTo("😀".repeat(64));
        assertThat(result.digest().codePointCount(0, result.digest().length())).isEqualTo(70);
    }

    @Test
    void should_fold_newlines_in_digest_when_text_has_multiple_lines() {
        Tweet tweet = baseTweet().formattedText("第一行\n\n第二行\n第三行").build();

        assertThat(renderer.render(tweet, null).digest()).isEqualTo("第一行 第二行 第三行");
    }

    // ===== AC9/T5.3: 确定性 =====

    @Test
    void should_be_deterministic_when_same_input_rendered_twice() {
        Tweet tweet = baseTweet()
                .formattedText("内容\n\n第二段 <b>")
                .quotedTweetUrl("https://x.com/other/status/999")
                .quotedTweetText("引用")
                .links(List.of("https://example.com/x"))
                .build();
        List<TweetMedia> media = List.of(
                photo("m1", WECHAT_URL).build(),
                TweetMedia.builder().id("m2").type(TweetMediaType.VIDEO).build());

        OriginalPostRenderResult first = renderer.render(tweet, media);
        OriginalPostRenderResult second = renderer.render(tweet, media);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void should_render_no_media_section_when_prepared_media_null() {
        OriginalPostRenderResult result = renderer.render(baseTweet().build(), null);

        assertThat(result.html()).doesNotContain("<img");
        assertThat(result.embeddedImageCount()).isZero();
        assertThat(result.degradedMediaCount()).isZero();
        assertThat(result.embeddedImageUrls()).isEmpty();
    }

    // ===== T5.2: 反向断言 + 计数 =====

    @Test
    void should_only_use_wechat_urls_as_img_src_when_mixed_media() {
        Tweet tweet = baseTweet().build();
        TweetMedia embedded = photo("m1", WECHAT_URL).build();
        TweetMedia video = TweetMedia.builder()
                .id("m2")
                .type(TweetMediaType.VIDEO)
                .sourceUrl(VIDEO_SOURCE_URL)
                .previewImageUrl(PREVIEW_URL)
                .build();
        TweetMedia failedPhoto = photo("m3", null).failureReason("upload failed").build();

        OriginalPostRenderResult result = renderer.render(tweet, List.of(embedded, video, failedPhoto));

        assertThat(extractImgSrcs(result.html())).isNotEmpty();
        assertImgSrcsOnlyFromWechat(result);
        // 计数
        assertThat(result.embeddedImageCount()).isEqualTo(1);
        assertThat(result.degradedMediaCount()).isEqualTo(2);
    }

    // ===== CR Round 1 patches 回归 =====

    @Test
    void should_throw_non_retryable_when_tweet_id_illegal_in_render() {
        // patch#1: 字符集校验提前到 render，含换行的伪造 id 不能进入日志 (CWE-117)
        Tweet tweet = baseTweet().id("123\nINFO fake log line").build();

        assertThatThrownBy(() -> renderer.render(tweet, null))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("illegal tweet id characters")
                .hasMessageNotContaining("fake log line");
    }

    @Test
    void should_escape_wechat_url_in_img_src_when_url_contains_ampersand() {
        // patch#8: HTML src 是转义形态、embeddedImageUrls 保留原始形态，二者不能直接比较
        String urlWithParams = "https://mmbiz.qpic.cn/mmbiz_png/abc/123?wx_fmt=png&wxfrom=5";
        Tweet tweet = baseTweet().build();
        TweetMedia photo = photo("m1", urlWithParams).build();

        OriginalPostRenderResult result = renderer.render(tweet, List.of(photo));

        assertThat(result.html()).contains("<img src=\"https://mmbiz.qpic.cn/mmbiz_png/abc/123"
                + "?wx_fmt=png&amp;wxfrom=5\"/>");
        assertThat(result.embeddedImageUrls()).containsExactly(urlWithParams);
        assertThat(extractImgSrcs(result.html()))
                .containsExactly(ArticleToWxArticleConverter.escapeHtml(urlWithParams));
        assertImgSrcsOnlyFromWechat(result);
    }

    @Test
    void should_map_quoted_text_line_breaks_to_br_when_quote_is_multiline() {
        // patch#2: 引用推多行文本换行不塌缩，与主文本管线一致
        Tweet tweet = baseTweet()
                .quotedTweetUrl("https://x.com/other/status/999")
                .quotedTweetText("论点一\n论点二\n论点三")
                .build();

        String html = renderer.render(tweet, null).html();

        assertThat(html).contains("论点一<br/>论点二<br/>论点三");
    }

    @Test
    void should_throw_non_retryable_when_render_result_null() {
        // patch#3: result null 与 tweet null 同契约，NonRetryable 而非 NPE
        Tweet tweet = baseTweet().build();

        assertThatThrownBy(() -> renderer.toArticle(tweet, null))
                .isInstanceOf(NonRetryableException.class)
                .hasMessageContaining("render result null");
    }

    @Test
    void should_degrade_to_plain_copy_when_urls_have_unsafe_scheme() {
        // patch#5: javascript: 等非 http(s) URL 绝不进 href — quote 锚点不渲染、
        // tweet.url 走纯文案、补遗以转义纯文本呈现
        Tweet tweet = baseTweet()
                .url("javascript:alert(1)//")
                .quotedTweetUrl("javascript:alert(1)//")
                .quotedTweetText("引用内容")
                .links(List.of("javascript:alert(1)//"))
                .build();
        TweetMedia video = TweetMedia.builder().id("m1").type(TweetMediaType.VIDEO).build();

        String html = renderer.render(tweet, List.of(video)).html();

        assertThat(html).doesNotContain("<a href=\"javascript:");
        // 引用块保留内容但锚点不渲染
        assertThat(html).contains("<blockquote>");
        assertThat(html).contains("引用内容");
        assertThat(html).doesNotContain("查看引用推");
        // tweet.url 非法 → 媒体降级与 footer 均走纯文案/无锚点
        assertThat(html).contains("请到 X 原帖查看");
    }

    @Test
    void should_deduplicate_links_when_links_contain_duplicates() {
        // patch#6: 重复 URL 只补遗一次 (单锚点 = href + 文本共出现 2 次)
        Tweet tweet = baseTweet()
                .formattedText("看这个")
                .links(Arrays.asList("https://example.com/b", "https://example.com/b"))
                .build();

        String html = renderer.render(tweet, null).html();

        int occurrences = html.split("https://example\\.com/b", -1).length - 1;
        assertThat(occurrences).isEqualTo(2);
    }

    @Test
    void should_normalize_unicode_line_separators_when_text_contains_u2028() {
        // patch#7: U+2028/U+2029/U+0085 与 \n 同样参与段落/br 映射
        Tweet tweet = baseTweet()
                .formattedText("第一段\u2028\u2028第二段\u2028行2")
                .build();

        String html = renderer.render(tweet, null).html();

        assertThat(html).contains("<p>第一段</p>");
        assertThat(html).contains("<p>第二段<br/>行2</p>");
    }

    @Test
    void should_reject_null_text_fields_when_result_manually_constructed() {
        // patch#9: public record API 对 title/digest/html fail-fast
        assertThatThrownBy(() -> new OriginalPostRenderResult(null, "d", "h", 0, 0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("title");
    }

    @Test
    void should_filter_null_elements_when_embedded_urls_contain_null() {
        // patch#9: 列表 null 元素过滤而非 List.copyOf 无信息 NPE
        OriginalPostRenderResult result = new OriginalPostRenderResult("t", "d", "h", 1, 0,
                Arrays.asList("https://mmbiz.qpic.cn/a", null));

        assertThat(result.embeddedImageUrls())
                .containsExactly("https://mmbiz.qpic.cn/a");
    }
}
