package com.choucj.aiaggregator.source.github;

import com.choucj.aiaggregator.source.github.client.GitHubClient;
import com.choucj.aiaggregator.source.github.config.GitHubProperties;
import com.choucj.aiaggregator.source.github.model.GitHubRepo;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Story 4.1 AC-7 — GitHub 数据源连通性冒烟测试.
 *
 * <p>三层测试场景:
 * <ol>
 *   <li>{@link #gitHubClientBeanRegistered()} — 配置层: features.github.enabled=true 时 GitHubClient Bean 注册.</li>
 *   <li>{@link #gitHubSourceBeanRegistered()} — 配置层: GitHubSource Bean 注册, 可注入.</li>
 *   <li>{@link #fetchTrendingWithRealToken()} — 集成层: 调用真实 GitHub Search API 抓取, 仅在 GITHUB_TOKEN 环境变量存在时执行.</li>
 * </ol>
 *
 * <p>设计模式: 复用 Story 1.5a {@code RedisClusterConnectivityTest} / Story 3.1 {@code WxJavaConnectivitySmokeTest}
 * 的 {@code @EnabledIfEnvironmentVariable} 跳过模式 — 无凭据场景静默 skip, 有凭据时真实调用.
 *
 * <p><b>spike 调试方法</b>: 配置 GITHUB_TOKEN 后运行:
 * <pre>
 * GITHUB_TOKEN=github_pat_xxx ./mvnw test -Dtest=GitHubConnectivitySmokeTest#fetchTrendingWithRealToken
 * </pre>
 *
 * <p>常见失败:
 * <ul>
 *   <li>403 + X-RateLimit-Remaining:0 → 未认证 (60/h) 或认证 (5000/h) 配额耗尽, 等待 reset.</li>
 *   <li>401 → token 失效 / 过期.</li>
 *   <li>ResourceAccessException → 网络不通 (GitHub 在国内需走代理).</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "features.github.enabled=true",
        "feature-flags.github.enabled=true",
        "github.enabled=true",
        "archive.enabled=false",
        "schedule.run-on-startup=false"
})
@Slf4j
class GitHubConnectivitySmokeTest {

    @Autowired
    private GitHubClient gitHubClient;

    @Autowired
    private GitHubSource gitHubSource;

    @Autowired
    private GitHubProperties gitHubProperties;

    @Test
    void gitHubClientBeanRegistered() {
        assertThat(gitHubClient)
                .as("features.github.enabled=true 时 GitHubClientImpl 应注册")
                .isNotNull();
    }

    @Test
    void gitHubSourceBeanRegistered() {
        assertThat(gitHubSource)
                .as("features.github.enabled=true 时 GitHubSource 应注册")
                .isNotNull();
    }

    @Test
    void gitHubPropertiesBound() {
        assertThat(gitHubProperties.getTrending().getLanguage()).isEqualTo("java");
        assertThat(gitHubProperties.getTrending().getLookbackDays()).isBetween(1, 90);
        assertThat(gitHubProperties.getTrending().getTopN()).isBetween(1, 100);
    }

    @Test
    @Timeout(30)
    @EnabledIfEnvironmentVariable(named = "GITHUB_TOKEN", matches = "^(?!your-github-token$).+")
    void fetchTrendingWithRealToken() {
        List<GitHubRepo> repos = gitHubClient.fetchTrending();

        assertThat(repos)
                .as("认证模式 (30/min) 下应能拉到至少 1 条 (除非该语言近期无新仓库)")
                .isNotNull();
        if (!repos.isEmpty()) {
            GitHubRepo first = repos.get(0);
            assertThat(first.getFullName()).as("full_name 必填").isNotNull();
            assertThat(first.getUrl()).as("html_url 必填").startsWith("https://github.com/");
            log.info("GitHub Trending OK: 条数={}, 首条={} (stars={})",
                    repos.size(), first.getFullName(), first.getStars());
        }
    }

    /**
     * Story 4.2 AC-7 — GitHub README 连通性冒烟测试.
     *
     * <p>抓取真实 Trending 首仓库后调用 README API:
     * <ul>
     *   <li>Trending 列表和首仓库 fullName 必须有效</li>
     *   <li>解码后应为以 Markdown / HTML 标题开始的非空 UTF-8 文本</li>
     *   <li>长度受限于 {@code github.readme.max-size-kb} (默认 100KB)</li>
     * </ul>
     */
    @Test
    @Timeout(30)
    @EnabledIfEnvironmentVariable(named = "GITHUB_TOKEN", matches = "^(?!your-github-token$).+")
    void fetchReadmeWithRealToken() {
        List<GitHubRepo> repos = gitHubClient.fetchTrending();
        assumeTrue(!repos.isEmpty(), "近期无 Trending 仓库, 跳过 README 连通性断言");
        String fullName = repos.get(0).getFullName();
        assumeTrue(fullName != null && fullName.contains("/"), "Trending 首仓库 fullName 无效");
        String[] coordinates = fullName.split("/", 2);
        String readme = gitHubClient.fetchReadme(coordinates[0], coordinates[1]);

        assertThat(readme)
                .as("Trending 首仓库 README 解码后应非空")
                .isNotNull()
                .isNotEmpty();
        assertThat(readme.startsWith("# ") || readme.startsWith("<h1"))
                .as("README 应以 Markdown 或 HTML 一级标题开始")
                .isTrue();
        assertThat(readme.codePointCount(0, readme.length()))
                .as("README 应被截断至 <= 100KB")
                .isLessThanOrEqualTo(gitHubProperties.getReadme().getMaxSizeKb() * 1024);
        log.info("GitHub README OK: owner={}, repo={}, 字符数={}",
                coordinates[0], coordinates[1], readme.codePointCount(0, readme.length()));
    }
}
