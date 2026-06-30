package com.choucj.aiaggregator.processor.config;

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
 * Story 2.6 {@link ProcessorProperties} 绑定 + 默认值 + 校验测试.
 *
 * <p>覆盖 W7/W8/N1 (校验注解) + W12 (负向校验用例) 模式,
 * 复用 Story 2.5 ArchiverPropertiesTest / Story 2.3b FilterPropertiesTest 测试范式.
 */
class ProcessorPropertiesTest {

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

    @EnableConfigurationProperties(ProcessorProperties.class)
    static class TestConfig {
    }

    @Test
    void shouldApplyDefaultsWhenOmitted() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .run(ctx -> {
                    ProcessorProperties props = ctx.getBean(ProcessorProperties.class);
                    assertThat(props.isFaultIsolationEnabled()).isTrue();
                    assertThat(props.getTaskIdPrefix()).isEqualTo("twitter");
                });
    }

    @Test
    void shouldBindConfiguredValues() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", Map.of(
                                "processor.fault-isolation-enabled", false,
                                "processor.task-id-prefix", "twitter-stage"))))
                .run(ctx -> {
                    ProcessorProperties props = ctx.getBean(ProcessorProperties.class);
                    assertThat(props.isFaultIsolationEnabled()).isFalse();
                    assertThat(props.getTaskIdPrefix()).isEqualTo("twitter-stage");
                });
    }

    /**
     * W12 + W7/W8: task-id-prefix 空白应触发 @NotBlank 校验失败.
     */
    @Test
    void shouldFailValidationWhenTaskIdPrefixBlank() {
        ProcessorProperties props = new ProcessorProperties();
        props.setTaskIdPrefix("");

        Set<ConstraintViolation<ProcessorProperties>> violations = validator.validate(props);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).isEqualTo("taskIdPrefix"));
    }

    @Test
    void shouldFailValidationWhenTaskIdPrefixIsWhitespace() {
        ProcessorProperties props = new ProcessorProperties();
        props.setTaskIdPrefix("   ");

        Set<ConstraintViolation<ProcessorProperties>> violations = validator.validate(props);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).isEqualTo("taskIdPrefix"));
    }
}
