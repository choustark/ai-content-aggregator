package com.choucj.aiaggregator.common.util;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

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

    /**
     * 提取异常链最深处的非空 message 作为根因，避免日志只输出表层包装异常。
     *
     * <p>无任何 cause message 时回退到异常类名。引用源: Story 7.1 提升
     * (原 MarkdownArchiver private 实现) / lessons-learned 跨包可见性模式。
     *
     * <p><b>环检测 (Story 7.1 review patch-1):</b> 用 {@link IdentityHashMap} 记录已访问异常,
     * 防御任意 cause 环 (自引用 {@code A→A} 与 2-cycle {@code A→B→A}), 避免 cause 链成环时
     * 线程无限循环挂死。异常链理论上应有向无环, 但第三方 SDK (含 Throwable.initCause 滥用)
     * 可能构造出环。
     *
     * @param e 异常；为 null 时返回 null
     * @return 根因 message 或异常类名
     */
    public static String getRootMessage(Throwable e) {
        if (e == null) {
            return null;
        }
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable cursor = e;
        String last = e.getMessage();
        seen.add(cursor);
        while (cursor.getCause() != null && !seen.contains(cursor.getCause())) {
            cursor = cursor.getCause();
            seen.add(cursor);
            if (cursor.getMessage() != null) {
                last = cursor.getMessage();
            }
        }
        return last == null ? e.getClass().getSimpleName() : last;
    }
}
