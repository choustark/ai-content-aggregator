package com.choucj.aiaggregator.content.filter.config;

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
 * Story 2.3b {@link FilterProperties} 绑定 + 默认值 + 校验测试.
 *
 * <p>W12 (2026-06-28 review): 加负向校验用例, 验证 {@code @Min} / {@code @Max} /
 * {@code @DecimalMin} / {@code @DecimalMax} 注解生效.
 */
class FilterPropertiesTest {

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

    @EnableConfigurationProperties(FilterProperties.class)
    static class TestConfig {
    }

    @Test
    void shouldApplyDefaultsWhenOmitted() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .run(ctx -> {
                    FilterProperties props = ctx.getBean(FilterProperties.class);
                    assertThat(props.getCommentThreshold()).isEqualTo(10);
                    assertThat(props.getInnovationThreshold()).isEqualTo(6.0);
                });
    }

    @Test
    void shouldBindConfiguredValues() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", Map.of(
                                "filter.comment-threshold", 20,
                                "filter.innovation-threshold", 7.5))))
                .run(ctx -> {
                    FilterProperties props = ctx.getBean(FilterProperties.class);
                    assertThat(props.getCommentThreshold()).isEqualTo(20);
                    assertThat(props.getInnovationThreshold()).isEqualTo(7.5);
                });
    }

    /**
     * W12 (2026-06-28 review): comment-threshold 为负数应触发校验失败.
     */
    @Test
    void shouldFailValidationWhenCommentThresholdNegative() {
        FilterProperties props = new FilterProperties();
        props.setCommentThreshold(-1);

        Set<ConstraintViolation<FilterProperties>> violations = validator.validate(props);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).isEqualTo("commentThreshold"));
    }

    /**
     * W12 + N1 (2026-06-28 review): comment-threshold 超过 1_000_000 上限应触发校验失败.
     */
    @Test
    void shouldFailValidationWhenCommentThresholdTooLarge() {
        FilterProperties props = new FilterProperties();
        props.setCommentThreshold(2_000_000);

        Set<ConstraintViolation<FilterProperties>> violations = validator.validate(props);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).isEqualTo("commentThreshold"));
    }

    /**
     * W12 + W7 (2026-06-28 review): innovation-threshold 为负数应触发 @DecimalMin 校验失败.
     */
    @Test
    void shouldFailValidationWhenInnovationThresholdNegative() {
        FilterProperties props = new FilterProperties();
        props.setInnovationThreshold(-0.1);

        Set<ConstraintViolation<FilterProperties>> violations = validator.validate(props);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).isEqualTo("innovationThreshold"));
    }

    /**
     * W12 + W8 (2026-06-28 review): innovation-threshold 超过 10.0 上限应触发 @DecimalMax 校验失败.
     */
    @Test
    void shouldFailValidationWhenInnovationThresholdTooLarge() {
        FilterProperties props = new FilterProperties();
        props.setInnovationThreshold(11.0);

        Set<ConstraintViolation<FilterProperties>> violations = validator.validate(props);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).isEqualTo("innovationThreshold"));
    }
}
