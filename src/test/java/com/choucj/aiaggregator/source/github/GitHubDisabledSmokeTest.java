package com.choucj.aiaggregator.source.github;

import com.choucj.aiaggregator.processor.GitHubProcessor;
import com.choucj.aiaggregator.source.github.client.GitHubClient;
import com.choucj.aiaggregator.source.github.service.GitHubValueAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 4.1 AC-7 — GitHub 数据源 Bean 注册 / 关闭冒烟测试.
 *
 * <p>本类验证 {@code features.github.enabled=false} 时, GitHub 下游 Bean 不注册:
 * <ul>
 *   <li>{@link GitHubSource} (项目层 @ConditionalOnProperty)</li>
 *   <li>{@link GitHubClient} (GitHubClientImpl @Component, 但 @ConditionalOnProperty 不在本类...
 *       实际由 features.github.enabled 总开关控制)</li>
 * </ul>
 *
 * <p>{@link GitHubProcessor} 是 Story 4.4 AC-3 常驻编排器: disabled 场景仍注册, 但
 * {@code process()} 直接输出 0-summary 后返回.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "features.github.enabled=false",
        "wechat.mp.enabled=false",
        "schedule.run-on-startup=false"
})
class GitHubDisabledSmokeTest {

    @Autowired(required = false)
    private GitHubSource gitHubSource;

    @Autowired(required = false)
    private GitHubClient gitHubClient;

    @Autowired(required = false)
    private GitHubValueAnalyzer gitHubValueAnalyzer;

    @Autowired(required = false)
    private GitHubProcessor gitHubProcessor;

    @Test
    void gitHubSourceBeanNotRegisteredWhenDisabled() {
        assertThat(gitHubSource)
                .as("features.github.enabled=false 时 GitHubSource Bean 不应注册")
                .isNull();
    }

    @Test
    void gitHubClientBeanNotRegisteredWhenDisabled() {
        assertThat(gitHubClient)
                .as("features.github.enabled=false 时 GitHubClient Bean 不应注册")
                .isNull();
    }

    @Test
    void gitHubValueAnalyzerBeanNotRegisteredWhenDisabled() {
        assertThat(gitHubValueAnalyzer)
                .as("features.github.enabled=false 时 GitHubValueAnalyzer Bean 不应注册")
                .isNull();
    }

    @Test
    void gitHubProcessorStillReturnsZeroSummaryWhenDisabled(CapturedOutput output) {
        assertThat(gitHubProcessor)
                .as("features.github.enabled=false 时 GitHubProcessor 应常驻注册以满足 AC-3 0-summary 契约")
                .isNotNull();

        gitHubProcessor.process();

        assertThat(output.getAll())
                .contains("GitHub Pipeline disabled")
                .contains("GitHub Pipeline 完成")
                .contains("发现=0")
                .contains("README 补全失败=0")
                .contains("价值分析通过=0")
                .contains("改写成功=0")
                .contains("归档成功=0")
                .contains("失败=0");
    }
}
