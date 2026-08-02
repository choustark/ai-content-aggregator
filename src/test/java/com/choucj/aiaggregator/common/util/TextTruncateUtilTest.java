package com.choucj.aiaggregator.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextTruncateUtilTest {

    @Test
    void should_truncate_by_code_points_without_splitting_emoji() {
        String content = "AI🚀工具";

        String truncated = TextTruncateUtil.truncateByCodePoints(content, 3);

        assertThat(truncated).isEqualTo("AI🚀");
        assertThat(truncated.codePointCount(0, truncated.length())).isEqualTo(3);
    }

    @Test
    void should_return_empty_when_content_is_null_or_empty() {
        assertThat(TextTruncateUtil.truncateByCodePoints(null, 10)).isEmpty();
        assertThat(TextTruncateUtil.truncateByCodePoints("", 10)).isEmpty();
        assertThat(TextTruncateUtil.truncateForLog(null, 10)).isEmpty();
        assertThat(TextTruncateUtil.truncateForLog("", 10)).isEmpty();
    }

    @Test
    void should_keep_log_truncation_within_max_code_points() {
        String content = "标".repeat(60);

        String truncated = TextTruncateUtil.truncateForLog(content, 50);

        assertThat(truncated).endsWith("...");
        assertThat(truncated.codePointCount(0, truncated.length())).isEqualTo(50);
    }

    @Test
    void should_truncate_without_ellipsis_when_max_is_three_or_less() {
        String content = "ABCDE";

        String truncated = TextTruncateUtil.truncateForLog(content, 3);

        assertThat(truncated).isEqualTo("ABC");
        assertThat(truncated).doesNotEndWith("...");
    }
}
