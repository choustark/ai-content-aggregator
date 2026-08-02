package com.choucj.aiaggregator.common.util;

/**
 * 按 Unicode code point 截断文本，避免日志、prompt 和摘要截断时切坏 emoji 等代理对字符。
 *
 * <p>引用源: Story 6.3(创建) / lessons-learned N2 + R3-1。
 */
public final class TextTruncateUtil {

    private TextTruncateUtil() {
    }

    /**
     * 按 code point 截断文本，保护外部内容进入 prompt、日志或摘要字段时不会产生半个代理对。
     *
     * @param content       待截断文本；为 null 时返回空串
     * @param maxCodePoints 最大 code point 数；小于等于 0 时返回空串
     * @return code point 数不超过 {@code maxCodePoints} 的文本
     */
    public static String truncateByCodePoints(String content, int maxCodePoints) {
        if (content == null || content.isEmpty() || maxCodePoints <= 0) {
            return "";
        }
        int total = content.codePointCount(0, content.length());
        if (total <= maxCodePoints) {
            return content;
        }
        int endIndex = content.offsetByCodePoints(0, maxCodePoints);
        return content.substring(0, endIndex);
    }

    /**
     * 截断日志文本且让省略号计入最大长度预算，避免日志字段突破 N4 长度契约。
     *
     * @param content 待截断文本；为 null 时返回空串
     * @param max     最大 code point 数；小于等于 0 时返回空串
     * @return code point 数不超过 {@code max} 的日志文本
     */
    public static String truncateForLog(String content, int max) {
        if (content == null || content.isEmpty() || max <= 0) {
            return "";
        }
        int total = content.codePointCount(0, content.length());
        if (total <= max) {
            return content;
        }
        if (max <= 3) {
            return truncateByCodePoints(content, max);
        }
        return truncateByCodePoints(content, max - 3) + "...";
    }
}
