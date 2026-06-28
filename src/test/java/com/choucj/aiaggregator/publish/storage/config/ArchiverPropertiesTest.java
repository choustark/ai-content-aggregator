package com.choucj.aiaggregator.publish.storage.config;

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
 * Story 2.5 {@link ArchiverProperties} 绑定 + 默认值 + 校验测试.
 *
 * <p>覆盖 W7/W8/N1 (校验注解) + W12 (负向校验用例) 模式 (Story 2.4 review lessons 复用).
 */
class ArchiverPropertiesTest {

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

    @EnableConfigurationProperties(ArchiverProperties.class)
    static class TestConfig {
    }

    @Test
    void shouldApplyDefaultsWhenOmitted() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .run(ctx -> {
                    ArchiverProperties props = ctx.getBean(ArchiverProperties.class);
                    assertThat(props.getBaseDirectory()).isEqualTo("archive");
                    assertThat(props.isEnabled()).isTrue();
                    assertThat(props.getDatePattern()).isEqualTo("yyyy-MM-dd");
                    assertThat(props.getFileSuffix()).isEqualTo(".md");
                    assertThat(props.getSeparator()).isEqualTo("\n\n---\n\n");
                });
    }

    @Test
    void shouldBindConfiguredValues() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", Map.of(
                                "archive.base-directory", "/var/data/archive",
                                "archive.enabled", false,
                                "archive.date-pattern", "yyyy/MM/dd",
                                "archive.file-suffix", ".markdown",
                                "archive.separator", "\n===\n"))))
                .run(ctx -> {
                    ArchiverProperties props = ctx.getBean(ArchiverProperties.class);
                    assertThat(props.getBaseDirectory()).isEqualTo("/var/data/archive");
                    assertThat(props.isEnabled()).isFalse();
                    assertThat(props.getDatePattern()).isEqualTo("yyyy/MM/dd");
                    assertThat(props.getFileSuffix()).isEqualTo(".markdown");
                    assertThat(props.getSeparator()).isEqualTo("\n===\n");
                });
    }

    /**
     * W12 + W7/W8 (2026-06-28 review lessons 复用): base-directory 空白应触发 @NotBlank 校验失败.
     */
    @Test
    void shouldFailValidationWhenBaseDirectoryBlank() {
        ArchiverProperties props = new ArchiverProperties();
        props.setBaseDirectory("");

        Set<ConstraintViolation<ArchiverProperties>> violations = validator.validate(props);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).isEqualTo("baseDirectory"));
    }

    /**
     * W12: date-pattern 空白应触发 @NotBlank 校验失败.
     */
    @Test
    void shouldFailValidationWhenDatePatternBlank() {
        ArchiverProperties props = new ArchiverProperties();
        props.setDatePattern(" ");

        Set<ConstraintViolation<ArchiverProperties>> violations = validator.validate(props);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).isEqualTo("datePattern"));
    }

    /**
     * W12: file-suffix 空白应触发 @NotBlank 校验失败.
     */
    @Test
    void shouldFailValidationWhenFileSuffixBlank() {
        ArchiverProperties props = new ArchiverProperties();
        props.setFileSuffix("");

        Set<ConstraintViolation<ArchiverProperties>> violations = validator.validate(props);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).isEqualTo("fileSuffix"));
    }

    /**
     * W12: separator 空白应触发 @NotBlank 校验失败.
     */
    @Test
    void shouldFailValidationWhenSeparatorBlank() {
        ArchiverProperties props = new ArchiverProperties();
        props.setSeparator("");

        Set<ConstraintViolation<ArchiverProperties>> violations = validator.validate(props);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).isEqualTo("separator"));
    }
}
