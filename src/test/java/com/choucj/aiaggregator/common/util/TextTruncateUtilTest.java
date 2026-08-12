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

    // getRootMessage (Story 7.1 提升)

    @Test
    void should_extract_deepest_cause_message() {
        Throwable deep = new RuntimeException("数据库连接失败");
        Throwable mid = new RuntimeException("序列化失败", deep);
        Throwable top = new RuntimeException("sidecar 写入失败", mid);

        assertThat(TextTruncateUtil.getRootMessage(top)).isEqualTo("数据库连接失败");
    }

    @Test
    void should_return_top_message_when_no_cause() {
        Throwable e = new IllegalArgumentException("非法参数");
        assertThat(TextTruncateUtil.getRootMessage(e)).isEqualTo("非法参数");
    }

    @Test
    void should_skip_null_cause_messages() {
        Throwable deepNullMsg = new RuntimeException();
        Throwable mid = new RuntimeException("中间原因", deepNullMsg);
        assertThat(TextTruncateUtil.getRootMessage(mid)).isEqualTo("中间原因");
    }

    @Test
    void should_fallback_to_class_name_when_no_message_anywhere() {
        Throwable e = new RuntimeException();
        assertThat(TextTruncateUtil.getRootMessage(e)).isEqualTo("RuntimeException");
    }

    @Test
    void should_return_null_when_exception_is_null() {
        assertThat(TextTruncateUtil.getRootMessage(null)).isNull();
    }

    // Story 7.1 review patch-1: cause 链成环 (2-cycle A→B→A) 不应无限循环挂死.
    // 注: JDK Throwable.initCause 禁止 A→A 自引用 (抛 IllegalArgumentException), 故自引用无需测试;
    // 但 A→B→A 双向环 JDK 允许构造, 是真实的挂死风险。
    @Test
    void should_break_cause_cycle_without_infinite_loop() {
        RuntimeException a = new RuntimeException("消息A");
        RuntimeException b = new RuntimeException("消息B", a);
        a.initCause(b); // 构造 A→B→A 的 2-cycle

        // 不应挂死; 返回环上任意非空 message 即可 (a 与 b 都见过)
        String root = TextTruncateUtil.getRootMessage(a);
        assertThat(root).isIn("消息A", "消息B");
    }
}
