package com.choucj.aiaggregator.common.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * GitHub API 配置属性.
 *
 * <p>用于 Epic 4 GitHub README/Code 抓取, Token 通过 {@code GITHUB_TOKEN} 环境变量或
 * {@code api-keys.yml} 注入. 未启用 Epic 4 时, dev 环境可用占位符通过 {@link NotBlank} 校验.
 * {@link #toString} 不输出 token 字段以避免日志泄露.
 */
@ConfigurationProperties(prefix = "github")
@Validated
@Data
@ToString(exclude = "token")
public class GithubProperties {

    @NotBlank(message = "github.token must be configured (set GITHUB_TOKEN env var or fill api-keys.yml)")
    private String token;
}
