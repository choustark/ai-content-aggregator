package com.choucj.aiaggregator.common.observability;

import com.choucj.aiaggregator.common.util.TextTruncateUtil;

import java.util.regex.Pattern;

/**
 * 规范化可能受外部控制的日志文本，以阻止控制字符注入、凭据泄露和无界日志增长。
 *
 * <p>敏感值统一替换为固定 {@code ***}，避免掩码长度反向泄露原值长度；截断复用
 * {@link TextTruncateUtil} 的 Unicode code point 安全实现。
 *
 * <p>引用源：Story 10.2（创建）/ commit 6e72106（LLM 错误体掩码收敛）。
 */
public final class LogSanitizer {

    private static final int ERROR_BODY_MAX_CODE_POINTS = 300;
    /**
     * 掩码前预截上限：上游错误体大小无界，先限长再跑多趟全文正则，
     * 避免内存/CPU 随输入长度线性放大。
     */
    private static final int PRE_MASK_MAX_CODE_POINTS = 65_536;
    /**
     * 键值分隔符的等价物：真实空白（含 Unicode 行分隔符）外加字面转义序列
     * {@code \t}/{@code \n}/{@code \r} — 上游错误体以 JSON 渲染时控制字符以两字符转义形态出现。
     */
    private static final String SEPARATOR = "(?:[\\s\\x{0085}\\x{2028}\\x{2029}]|\\\\[nrt])";
    /** 贪婪值类：吞到逗号/引号/分号等结构边界或行尾，含内部空白，防含空格口令只掩首词。 */
    private static final String LINE_VALUE = "[^\\r\\n\\x{0085}\\x{2028}\\x{2029},\\\"'}&;]+";
    /** 紧凑值类：遇空白即止 — Bearer/JWT 等单 token 凭据不含空格，保留凭据后的散文。 */
    private static final String TOKEN_VALUE = "[^\\s\\x{0085}\\x{2028}\\x{2029},\\\"'}&;]+";
    /** JSON 字符串值类：容忍 {@code \\"} 等转义序列，防在值内转义引号处提前终止泄露尾段。 */
    private static final String JSON_STRING_VALUE = "(?:(?:\\\\\\\\.|[^\\\"\\\\])*)";
    private static final Pattern JSON_AUTHORIZATION = Pattern.compile(
            "(?i)(\\\"authorization\\\"" + SEPARATOR + "*:" + SEPARATOR + "*\\\")" + JSON_STRING_VALUE);
    private static final Pattern AUTHORIZATION_SCHEME = Pattern.compile(
            "(?i)(\\bauthorization\\b[\\\"']?" + SEPARATOR + "*[:=]" + SEPARATOR + "*[\\\"']?)"
                    + "(?:basic|bearer)" + SEPARATOR + "+[\\\"']?" + LINE_VALUE);
    private static final Pattern AUTHORIZATION_DIGEST = Pattern.compile(
            "(?i)(\\bauthorization\\b[\\\"']?" + SEPARATOR + "*[:=]" + SEPARATOR + "*[\\\"']?)"
                    + "digest" + SEPARATOR + "+[^\\r\\n\\x{0085}\\x{2028}\\x{2029}]*");
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(\\bauthorization\\b[\\\"']?" + SEPARATOR + "*[:=]" + SEPARATOR + "*[\\\"']?)" + LINE_VALUE);
    private static final Pattern JSON_COOKIE = Pattern.compile(
            "(?i)(\\\"(?:set-)?cookies?\\\"" + SEPARATOR + "*:" + SEPARATOR + "*\\\")" + JSON_STRING_VALUE);
    private static final Pattern COOKIE = Pattern.compile(
            "(?i)(\\b(?:set-)?cookies?\\b[\\\"']?" + SEPARATOR + "*[:=]" + SEPARATOR + "*[\\\"']?)"
                    + "[^\\r\\n\\x{0085}\\x{2028}\\x{2029},]*");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)(\\b(?:api[-_]?key|access[-_]?token|refresh[-_]?token|token|password|passwd|pwd"
                    + "|session(?:[-_]?id)?|sid|oauth)"
                    + "\\b[\\\"']?" + SEPARATOR + "*[:=]" + SEPARATOR + "*[\\\"']?)"
                    + "(?:bearer" + SEPARATOR + "+)?" + LINE_VALUE);
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(\\bbearer" + SEPARATOR + "+)" + TOKEN_VALUE);
    private static final Pattern OPENAI_STYLE_KEY = Pattern.compile(
            "(?i)\\bsk-[A-Za-z0-9_-]{6,}\\b");

    private LogSanitizer() {
    }

    /**
     * 将日志文本单行化、脱敏并按 code point 截断，使结构化输出保持安全且可检索。
     *
     * @param value 待处理文本；{@code null} 返回空串
     * @param maxCodePoints 最大 code point 数，省略号计入预算
     * @return 安全日志文本
     */
    public static String sanitize(String value, int maxCodePoints) {
        String input = value == null ? "" : TextTruncateUtil.truncateForLog(value, PRE_MASK_MAX_CODE_POINTS);
        // 必须先对原始文本脱敏：控制字符转义后会变成字面反斜杠，破坏 \s 等分隔符匹配。
        String masked = JSON_AUTHORIZATION.matcher(input).replaceAll("$1***");
        masked = AUTHORIZATION_DIGEST.matcher(masked).replaceAll("$1***");
        masked = AUTHORIZATION_SCHEME.matcher(masked).replaceAll("$1***");
        masked = AUTHORIZATION.matcher(masked).replaceAll("$1***");
        masked = JSON_COOKIE.matcher(masked).replaceAll("$1***");
        masked = COOKIE.matcher(masked).replaceAll("$1***");
        // 先处理完整 Bearer 值，避免 token=Bearer 场景先吞掉协议名后留下真实凭据。
        masked = BEARER.matcher(masked).replaceAll("$1***");
        masked = SENSITIVE_ASSIGNMENT.matcher(masked).replaceAll("$1***");
        masked = OPENAI_STYLE_KEY.matcher(masked).replaceAll("sk-***");
        return TextTruncateUtil.truncateForLog(normalizeControls(masked), maxCodePoints);
    }

    /**
     * 清理 LLM 上游错误体并限制在 300 个 code point 内，以保留诊断摘要而不泄露凭据。
     *
     * @param body 上游错误体
     * @return 清理后的有限摘要；空白输入返回 {@code (空)}
     */
    public static String sanitizeBody(String body) {
        if (body == null || body.isBlank()) {
            return "(空)";
        }
        return sanitize(body, ERROR_BODY_MAX_CODE_POINTS);
    }

    private static String normalizeControls(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '\n' -> normalized.append("\\n");
                case '\r' -> normalized.append("\\r");
                case '\t' -> normalized.append("\\t");
                case '\b' -> normalized.append("\\b");
                case '\f' -> normalized.append("\\f");
                default -> {
                    if (current < 0x20
                            || current == 0x7f
                            || (current >= 0x80 && current <= 0x9f)
                            || current == 0x2028
                            || current == 0x2029) {
                        appendUnicodeEscape(normalized, current);
                    } else {
                        normalized.append(current);
                    }
                }
            }
        }
        return normalized.toString();
    }

    private static void appendUnicodeEscape(StringBuilder target, char value) {
        target.append("\\u");
        for (int shift = 12; shift >= 0; shift -= 4) {
            target.append(Character.forDigit((value >> shift) & 0x0f, 16));
        }
    }
}
