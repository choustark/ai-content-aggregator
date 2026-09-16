package com.choucj.aiaggregator.common.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证外部文本在进入日志前完成单行化、凭据脱敏和码点安全截断，以阻止注入与泄露。
 *
 * <p>引用源：Story 10.2（创建）。
 */
class LogSanitizerTest {

    @Test
    void should_mask_credentials_when_control_characters_separate_the_sensitive_tokens() {
        for (String input : java.util.List.of(
                "Bearer\tbearer-tab-canary",
                "api-key\t=\tapi-key-tab-canary",
                "token:\nBearer bearer-newline-canary")) {
            String sanitized = LogSanitizer.sanitize(input, 300);
            assertThat(sanitized)
                    .contains("***")
                    .doesNotContain("bearer-tab-canary", "api-key-tab-canary", "bearer-newline-canary");
        }
    }

    @Test
    void should_mask_all_authorization_schemes_when_header_contains_credentials() {
        for (String input : java.util.List.of(
                "Authorization: Basic basic-canary",
                "Authorization: Digest username=\"digest-user-canary\", response=\"digest-response-canary\"",
                "{\"authorization\":\"Basic json-basic-canary\",\"note\":\"safe\"}")) {
            assertThat(LogSanitizer.sanitize(input, 300))
                    .contains("***")
                    .doesNotContain("basic-canary", "digest-user-canary", "digest-response-canary", "json-basic-canary");
        }
    }

    @Test
    void should_mask_all_cookie_pairs_when_cookie_header_contains_multiple_values() {
        for (String input : java.util.List.of(
                "Cookie: sid=sid-canary; sessionid=session-canary; oauth=oauth-canary",
                "Cookie: sid=sid-canary; sessionid=session-canary, Cookie: oauth=oauth-canary")) {
            assertThat(LogSanitizer.sanitize(input, 300))
                    .contains("Cookie: ***")
                    .doesNotContain("sid-canary", "session-canary", "oauth-canary");
        }
    }

    @Test
    void should_escape_all_controls_and_separators_when_input_contains_unicode_line_breaks() {
        for (int code : java.util.stream.IntStream.concat(
                java.util.stream.IntStream.rangeClosed(0x7f, 0x9f),
                java.util.stream.IntStream.of(0x2028, 0x2029)).toArray()) {
            String raw = String.valueOf((char) code);
            String sanitized = LogSanitizer.sanitize("before" + raw + "after", 100);
            assertThat(sanitized).isEqualTo("before" + String.format("\\u%04x", code) + "after");
            assertThat(sanitized.split("\\R")).hasSize(1);
        }
    }

    @Test
    void should_mask_complete_bearer_value_when_nested_in_sensitive_assignment() {
        for (String input : java.util.List.of("token: Bearer bearer-canary=secret:tail",
                "access_token=Bearer bearer-canary=secret:tail", "Bearer bearer-canary=secret:tail")) {
            assertThat(LogSanitizer.sanitize(input, 300))
                    .contains("***")
                    .doesNotContain("bearer-canary", "secret", "tail");
        }
    }

    @Test
    void should_escape_control_characters_when_message_contains_emoji() {
        String sanitized = LogSanitizer.sanitize("first\nsecond\r\t\u0000😀", 100);

        assertThat(sanitized).isEqualTo("first\\nsecond\\r\\t\\u0000😀");
        assertThat(sanitized).doesNotContain("\n", "\r", "\t", "\u0000");
    }

    @Test
    void should_mask_sensitive_key_values_when_message_contains_bearer_tokens() {
        String input = "Authorization=Bearer auth-canary, api_key=api-canary, "
                + "access-token=access-canary, token=token-canary, password=pwd-canary";

        String sanitized = LogSanitizer.sanitize(input, 500);

        assertThat(sanitized)
                .contains("Authorization=***", "api_key=***", "access-token=***", "token=***", "password=***")
                .doesNotContain("auth-canary", "api-canary", "access-canary", "token-canary", "pwd-canary");
        assertThat(LogSanitizer.sanitize("Bearer standalone-canary", 100))
                .isEqualTo("Bearer ***");
    }

    @Test
    void should_mask_cookie_value_when_message_contains_cookie() {
        assertThat(LogSanitizer.sanitize("Cookie=session-cookie-canary", 100))
                .isEqualTo("Cookie=***");
    }

    @Test
    void should_truncate_by_code_point_when_message_exceeds_budget() {
        String sanitized = LogSanitizer.sanitize("😀".repeat(400), 300);

        assertThat(sanitized.codePointCount(0, sanitized.length())).isEqualTo(300);
        assertThat(sanitized).endsWith("...");
        assertThat(sanitized).doesNotContain("�");
    }

    @Test
    void should_mask_quoted_credentials_when_quote_wraps_the_sensitive_value() {
        for (String input : java.util.List.of(
                "Authorization:\"Basic quoted-basic-canary\"",
                "Authorization: 'Bearer quoted-bearer-canary'",
                "Cookie: \"sid=quoted-cookie-canary\"",
                "Authorization: Bearer \"jwt.quoted-canary\"")) {
            assertThat(LogSanitizer.sanitize(input, 300))
                    .contains("***")
                    .doesNotContain("quoted-basic-canary", "quoted-bearer-canary",
                            "quoted-cookie-canary", "jwt.quoted-canary");
        }
    }

    @Test
    void should_mask_credential_tail_when_value_contains_spaces_or_literal_escapes() {
        assertThat(LogSanitizer.sanitize("password=spaces pwd-tail-canary", 300))
                .doesNotContain("pwd-tail-canary");
        assertThat(LogSanitizer.sanitize("Set-Cookie: sid=csv-a-canary, oauth=csv-b-canary", 300))
                .doesNotContain("csv-a-canary", "csv-b-canary");
        assertThat(LogSanitizer.sanitize("Cookies: session=plural-cookie-canary", 300))
                .doesNotContain("plural-cookie-canary");
        assertThat(LogSanitizer.sanitize("Bearer\\tliteral-tab-canary", 300))
                .doesNotContain("literal-tab-canary");
        assertThat(LogSanitizer.sanitize("api-key\\t=\\tliteral-assignment-canary", 300))
                .doesNotContain("literal-assignment-canary");
        assertThat(LogSanitizer.sanitize("{\"cookie\":\"a\\\"escaped-json-cookie-canary\"}", 300))
                .doesNotContain("escaped-json-cookie-canary");
    }

    @Test
    void should_return_empty_marker_when_body_is_blank() {
        assertThat(LogSanitizer.sanitizeBody(null)).isEqualTo("(空)");
        assertThat(LogSanitizer.sanitizeBody("  ")).isEqualTo("(空)");
    }
}
