package com.choucj.aiaggregator.common.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Boot 实际解析后的测试配置，防止模板导入覆盖隔离值。 */
class TestConfigurationIsolationTest {

    @Test
    void should_preserve_isolation_when_boot_loads_test_configuration() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=classpath:/application.yml")
                .run(context -> {
                    var environment = context.getEnvironment();
                    assertThat(environment.getProperty("spring.data.redis.cluster.nodes[0]"))
                            .isEqualTo("redis-cluster-1:7001");
                    assertThat(environment.getProperty("redis.cluster.nodes[0].host"))
                            .isEqualTo("redis-cluster-1");
                    assertThat(environment.getProperty("langchain4j.community.redis.enabled"))
                            .isEqualTo("false");
                    assertThat(environment.getProperty("features.rag.enabled")).isEqualTo("false");
                    assertThat(environment.getProperty("feature-flags.rag.enabled")).isEqualTo("false");
                    assertThat(environment.getProperty("feature-flags.rag.batch-async-enabled"))
                            .isEqualTo("false");
                    assertThat(environment.getProperty("logging.level.com.choucj.aiaggregator"))
                            .isEqualTo("INFO");
                    assertThat(environment.getProperty("spring.config.import")).isNull();
                });
    }
}
