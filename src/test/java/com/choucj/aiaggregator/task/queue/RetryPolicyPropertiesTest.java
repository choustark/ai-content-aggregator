package com.choucj.aiaggregator.task.queue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 10.5 {@link RetryPolicyProperties} 单测 — 指数退避封顶公式与跨字段校验.
 */
class RetryPolicyPropertiesTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        validatorFactory.close();
    }

    @Test
    void should_return_initial_backoff_for_first_attempt() {
        RetryPolicyProperties properties = new RetryPolicyProperties();

        assertThat(properties.delayForAttempt(1)).isEqualTo(60_000L);
    }

    @Test
    void should_double_backoff_exponentially_before_cap() {
        RetryPolicyProperties properties = new RetryPolicyProperties();
        properties.setBackoffInitialMs(1_000L);
        properties.setBackoffMaxMs(10_000L);

        assertThat(properties.delayForAttempt(1)).isEqualTo(1_000L);
        assertThat(properties.delayForAttempt(2)).isEqualTo(2_000L);
        assertThat(properties.delayForAttempt(3)).isEqualTo(4_000L);
        assertThat(properties.delayForAttempt(4)).isEqualTo(8_000L);
    }

    @Test
    void should_cap_backoff_at_configured_maximum() {
        RetryPolicyProperties properties = new RetryPolicyProperties();
        properties.setBackoffInitialMs(1_000L);
        properties.setBackoffMaxMs(5_000L);

        // AC1 "退避时间受配置的上限约束": 第 4 次起封顶 5s, 不再翻倍
        assertThat(properties.delayForAttempt(4)).isEqualTo(5_000L);
        assertThat(properties.delayForAttempt(5)).isEqualTo(5_000L);
        assertThat(properties.delayForAttempt(100)).isEqualTo(5_000L);
    }

    @Test
    void should_not_overflow_on_huge_attempt_index() {
        RetryPolicyProperties properties = new RetryPolicyProperties();
        properties.setBackoffInitialMs(1_000L);
        properties.setBackoffMaxMs(5_000L);

        // attempt 极大时左移溢出防护: 结果仍为正的上限值, 不翻负
        assertThat(properties.delayForAttempt(Integer.MAX_VALUE)).isEqualTo(5_000L);
    }

    @Test
    void should_reject_attempt_below_one() {
        RetryPolicyProperties properties = new RetryPolicyProperties();

        assertThatThrownBy(() -> properties.delayForAttempt(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempt");
    }

    @Test
    void should_pass_validation_with_defaults() {
        assertThat(validator.validate(new RetryPolicyProperties())).isEmpty();
    }

    @Test
    void should_fail_validation_when_backoff_max_below_initial() {
        RetryPolicyProperties properties = new RetryPolicyProperties();
        properties.setBackoffInitialMs(10_000L);
        properties.setBackoffMaxMs(5_000L);

        assertThat(validator.validate(properties))
                .anySatisfy(violation ->
                        assertThat(violation.getMessage()).contains("backoff-max-ms"));
    }

    @Test
    void should_fail_validation_when_max_attempts_below_one() {
        RetryPolicyProperties properties = new RetryPolicyProperties();
        properties.setMaxAttempts(0);

        assertThat(validator.validate(properties))
                .anySatisfy(violation ->
                        assertThat(violation.getMessage()).contains("max-attempts"));
    }

    @Test
    void should_annotate_validated_so_spring_enforces_constraints() {
        // F1: JSR-303 约束注解本身不会激活 — 只有类上标注 @Validated,
        // Spring 绑定配置时才会校验并拒绝非法值; 缺失时注解形同虚设
        assertThat(RetryPolicyProperties.class.isAnnotationPresent(
                org.springframework.validation.annotation.Validated.class))
                .as("RetryPolicyProperties 必须标注 @Validated 让配置绑定层执行 JSR-303 校验")
                .isTrue();
    }

    @Test
    void should_declare_configuration_properties_prefix() {
        assertThat(RetryPolicyProperties.class.isAnnotationPresent(
                org.springframework.boot.context.properties.ConfigurationProperties.class))
                .isTrue();
        assertThat(RetryPolicyProperties.class
                .getAnnotation(org.springframework.boot.context.properties.ConfigurationProperties.class)
                .prefix()).isEqualTo("task.retry");
    }
}
