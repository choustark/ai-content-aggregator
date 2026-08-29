package com.choucj.aiaggregator.publish.wechat.config;

import com.choucj.aiaggregator.common.model.ContentGenerationMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 8.3 {@link OriginalPostProperties} 绑定测试.
 */
class OriginalPostPropertiesTest {

    @Test
    void should_apply_defaults_when_omitted() {
        new ApplicationContextRunner()
                .withUserConfiguration(OriginalPostConfig.class)
                .run(ctx -> {
                    OriginalPostProperties props = ctx.getBean(OriginalPostProperties.class);
                    assertThat(props.isEnabled()).isFalse();
                    assertThat(props.getDefaultMode()).isEqualTo(ContentGenerationMode.REWRITE);
                });
    }

    @Test
    void should_bind_configured_values_when_explicitly_set() {
        new ApplicationContextRunner()
                .withUserConfiguration(OriginalPostConfig.class)
                .withPropertyValues(
                        "wechat.mp.original-post.enabled=true",
                        "wechat.mp.original-post.default-mode=PRESERVE_ORIGINAL")
                .run(ctx -> {
                    OriginalPostProperties props = ctx.getBean(OriginalPostProperties.class);
                    assertThat(props.isEnabled()).isTrue();
                    assertThat(props.getDefaultMode()).isEqualTo(ContentGenerationMode.PRESERVE_ORIGINAL);
                });
    }

    @Test
    void should_fail_binding_when_default_mode_is_invalid() {
        new ApplicationContextRunner()
                .withUserConfiguration(OriginalPostConfig.class)
                .withPropertyValues("wechat.mp.original-post.default-mode=NOT_A_MODE")
                .run(ctx -> {
                    assertThat(ctx.getStartupFailure()).isNotNull();
                    // 递归遍历 cause 链断言, 避免依赖 Spring Boot 异常包装层级实现细节 (CR Round 1)
                    assertThat(findMessageContaining(ctx.getStartupFailure(), "NOT_A_MODE")).isTrue();
                });
    }

    private static boolean findMessageContaining(Throwable failure, String expected) {
        Throwable cursor = failure;
        int hops = 0;
        while (cursor != null && hops < 16) {
            if (cursor.getMessage() != null && cursor.getMessage().contains(expected)) {
                return true;
            }
            cursor = cursor.getCause();
            hops++;
        }
        return false;
    }
}
