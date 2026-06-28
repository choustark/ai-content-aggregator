package com.choucj.aiaggregator.content.rewriter.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 2.4 {@link RewriterProperties} 绑定 + 默认值 + 校验测试.
 *
 * <p>覆盖 W7/W8/N1 (校验注解) + W12 (负向校验用例) 模式 (Story 2.3b review lessons).
 */
class RewriterPropertiesTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    @EnableConfigurationProperties(RewriterProperties.class)
    static class TestConfig {
    }

    @Test
    void shouldApplyDefaultsWhenOmitted() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .run(ctx -> {
                    RewriterProperties props = ctx.getBean(RewriterProperties.class);
                    assertThat(props.getMaxRetries()).isEqualTo(3);
                    assertThat(props.getRetryBackoffMs()).isEqualTo(1000L);
                    assertThat(props.getContentMaxCodePoints()).isEqualTo(2000);
                });
    }

    @Test
    void shouldBindConfiguredValues() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", Map.of(
                                "rewriter.max-retries", 5,
                                "rewriter.retry-backoff-ms", 2000L,
                                "rewriter.content-max-code-points", 3000))))
                .run(ctx -> {
                    RewriterProperties props = ctx.getBean(RewriterProperties.class);
                    assertThat(props.getMaxRetries()).isEqualTo(5);
                    assertThat(props.getRetryBackoffMs()).isEqualTo(2000L);
                    assertThat(props.getContentMaxCodePoints()).isEqualTo(3000);
                });
    }

    /**
     * W12 + W7 (2026-06-28 review): max-retries 为负数应触发 @Min 校验失败.
     */
    @Test
    void shouldFailValidationWhenMaxRetriesNegative() {
        RewriterProperties props = new RewriterProperties();
        props.setMaxRetries(-1);

        Set<ConstraintViolation<RewriterProperties>> violations = validator.validate(props);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).isEqualTo("maxRetries"));
    }

    /**
     * W12 + W8 (2026-06-28 review): max-retries 超过 10 上限应触发 @Max 校验失败.
     */
    @Test
    void shouldFailValidationWhenMaxRetriesTooLarge() {
        RewriterProperties props = new RewriterProperties();
        props.setMaxRetries(11);

        Set<ConstraintViolation<RewriterProperties>> violations = validator.validate(props);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).isEqualTo("maxRetries"));
    }

    /**
     * W12 + N1 (2026-06-28 review): retry-backoff-ms 低于 100 下限应触发 @Min 校验失败.
     */
    @Test
    void shouldFailValidationWhenRetryBackoffMsTooSmall() {
        RewriterProperties props = new RewriterProperties();
        props.setRetryBackoffMs(50L);

        Set<ConstraintViolation<RewriterProperties>> violations = validator.validate(props);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).isEqualTo("retryBackoffMs"));
    }

    /**
     * W12 + W8 (2026-06-28 review): content-max-code-points 超过 10000 上限应触发 @Max 校验失败.
     */
    @Test
    void shouldFailValidationWhenContentMaxCodePointsTooLarge() {
        RewriterProperties props = new RewriterProperties();
        props.setContentMaxCodePoints(20_000);

        Set<ConstraintViolation<RewriterProperties>> violations = validator.validate(props);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).isEqualTo("contentMaxCodePoints"));
    }
}
