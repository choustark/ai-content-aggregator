package com.choucj.aiaggregator.common.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 9.1 AC1: {@link ContentGenerationMode} 三种生成模式语义可区分测试.
 *
 * <p>三种模式必须各自独立存在: REWRITE = 纯 AI 改写 Markdown; PRESERVE_ORIGINAL =
 * 确定性原帖复现 HTML; REWRITE_WITH_MEDIA = AI 改写 Markdown + 原帖 PHOTO 媒体嵌入。
 */
class ContentGenerationModeTest {

    @Test
    void should_contain_exactly_three_generation_modes() {
        assertThat(ContentGenerationMode.values())
                .containsExactly(ContentGenerationMode.REWRITE,
                        ContentGenerationMode.PRESERVE_ORIGINAL,
                        ContentGenerationMode.REWRITE_WITH_MEDIA);
    }

    @Test
    void should_be_distinct_across_all_three_modes() {
        assertThat(ContentGenerationMode.REWRITE)
                .isNotEqualTo(ContentGenerationMode.PRESERVE_ORIGINAL)
                .isNotEqualTo(ContentGenerationMode.REWRITE_WITH_MEDIA);
        assertThat(ContentGenerationMode.PRESERVE_ORIGINAL)
                .isNotEqualTo(ContentGenerationMode.REWRITE_WITH_MEDIA);
    }

    @Test
    void should_parse_rewrite_with_media_from_config_string() {
        // 配置绑定语义: default-mode=REWRITE_WITH_MEDIA 必须可被 Spring 宽松绑定解析
        assertThat(ContentGenerationMode.valueOf("REWRITE_WITH_MEDIA"))
                .isEqualTo(ContentGenerationMode.REWRITE_WITH_MEDIA);
    }
}
