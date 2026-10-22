package com.choucj.aiaggregator.publish.wechat.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class WeChatPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldDisableVideoByDefault() {
        runner.run(context -> assertThat(context.getBean(WeChatProperties.class)
                .getVideo().isEnabled()).isFalse());
    }

    @Test
    void shouldAllowExplicitVideoEnablementForControlledExperiments() {
        runner.withPropertyValues("wechat.mp.video.enabled=true")
                .run(context -> assertThat(context.getBean(WeChatProperties.class)
                        .getVideo().isEnabled()).isTrue());
    }

    @EnableConfigurationProperties(WeChatProperties.class)
    static class TestConfig {
    }
}
