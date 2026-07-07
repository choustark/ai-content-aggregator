package com.choucj.aiaggregator.publish.wechat.config;

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
 * Story 3.4 {@link PublishingProperties} 绑定 + 默认值 + 校验测试.
 */
class PublishingPropertiesTest {

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

    @EnableConfigurationProperties(PublishingProperties.class)
    static class TestConfig {
    }

    @Test
    void shouldApplyDefaultsWhenOmitted() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .run(ctx -> {
                    PublishingProperties props = ctx.getBean(PublishingProperties.class);
                    assertThat(props.getRealtimeThreshold()).isEqualTo(8);
                    assertThat(props.getBatchCron()).isEqualTo("0 0 20 * * ?");
                    assertThat(props.isBatchEnabled()).isTrue();
                    assertThat(props.getQueueTtlDays()).isEqualTo(7);
                });
    }

    @Test
    void shouldBindConfiguredValues() {
        new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("test", Map.of(
                                "wechat.mp.publishing.realtime-threshold", 10,
                                "wechat.mp.publishing.batch-cron", "0 30 21 * * ?",
                                "wechat.mp.publishing.batch-enabled", false,
                                "wechat.mp.publishing.queue-ttl-days", 30))))
                .run(ctx -> {
                    PublishingProperties props = ctx.getBean(PublishingProperties.class);
                    assertThat(props.getRealtimeThreshold()).isEqualTo(10);
                    assertThat(props.getBatchCron()).isEqualTo("0 30 21 * * ?");
                    assertThat(props.isBatchEnabled()).isFalse();
                    assertThat(props.getQueueTtlDays()).isEqualTo(30);
                });
    }

    @Test
    void shouldFailValidationWhenRealtimeThresholdTooSmall() {
        PublishingProperties props = new PublishingProperties();
        props.setRealtimeThreshold(0);

        Set<ConstraintViolation<PublishingProperties>> violations = validator.validate(props);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).isEqualTo("realtimeThreshold"));
    }

    @Test
    void shouldFailValidationWhenRealtimeThresholdTooLarge() {
        PublishingProperties props = new PublishingProperties();
        props.setRealtimeThreshold(11);

        Set<ConstraintViolation<PublishingProperties>> violations = validator.validate(props);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).isEqualTo("realtimeThreshold"));
    }

    @Test
    void shouldFailValidationWhenBatchCronBlank() {
        PublishingProperties props = new PublishingProperties();
        props.setBatchCron(" ");

        Set<ConstraintViolation<PublishingProperties>> violations = validator.validate(props);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).isEqualTo("batchCron"));
    }

    @Test
    void shouldFailValidationWhenQueueTtlDaysTooSmall() {
        PublishingProperties props = new PublishingProperties();
        props.setQueueTtlDays(0);

        Set<ConstraintViolation<PublishingProperties>> violations = validator.validate(props);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).isEqualTo("queueTtlDays"));
    }

    @Test
    void shouldFailValidationWhenQueueTtlDaysTooLarge() {
        PublishingProperties props = new PublishingProperties();
        props.setQueueTtlDays(366);

        Set<ConstraintViolation<PublishingProperties>> violations = validator.validate(props);

        assertThat(violations).anySatisfy(v ->
                assertThat(v.getPropertyPath().toString()).isEqualTo("queueTtlDays"));
    }
}
