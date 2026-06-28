package com.choucj.aiaggregator.source.twitter.config;

import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * twscrape CLI 客户端配置.
 *
 * <p>twscrape 是 Python 生态的 Twitter 抓取工具({@code https://github.com/vladkens/twscrape}),
 * 通过已登录账号的 cookie 抓取推文完整字段(text / 互动数 / 图片), Story 2.2b 引入作为
 * {@link com.choucj.aiaggregator.source.twitter.client.TwscrapeClient} 的主路径.
 * FxTwitter 公共实例失败时由 {@link com.choucj.aiaggregator.source.twitter.TwitterSource}
 * 编排层降级到本客户端.
 *
 * <p><b>架构 delta (Story 2.2b):</b>
 * <ul>
 *   <li>twscrape 调用通过 {@code ProcessBuilder} 子进程方式, 不引入 Python sidecar 容器(架构 L285)</li>
 *   <li>twscrape 账号凭据(cookie / username / password)<b>不</b>通过 {@code @ConfigurationProperties}
 *       绑定 — 避免 {@code /actuator/env} 即使 {@code show-values: when-authorized} 也降低泄漏风险;
 *       实际登录态由运维通过 {@code docker exec ... twscrape add_accounts} 维护, 应用代码不接触凭据</li>
 *   <li>{@link TwscrapeProperties} 与 {@link FxTwitterProperties} 各自独立 enabled 开关, 编排层
 *       (TwitterSource) 用 {@link #isEnabled()} 决定是否走 twscrape 主路径</li>
 * </ul>
 *
 * <p>调用方 {@link com.choucj.aiaggregator.source.twitter.client.TwscrapeClient} 通过
 * {@link #isEnabled()} 实现降级开关 — 关闭时直接抛 {@code RetryableException}, 由 TwitterSource
 * 编排层捕获后落到 FxTwitter 降级路径.
 */
@ConfigurationProperties(prefix = "twscrape")
@Validated
@Data
public class TwscrapeProperties {

    /** twscrape 主路径总开关. 关闭时直接降级到 FxTwitter(由编排层处理). */
    private boolean enabled = true;

    /**
     * CLI 调用超时(秒). 默认 120s, twscrape 单次抓取通常 5-30s, 慢网络可达 60s+.
     * <p>{@code @Min(60)} — 短于 60s 易在网络抖动时误杀有效响应.
     */
    @Min(value = 60, message = "twscrape.timeout-seconds must be >= 60")
    private int timeoutSeconds = 120;

    /**
     * twscrape 可执行文件名或绝对路径. 默认 {@code twscrape}(依赖 PATH 查找).
     * <p>Docker 镜像安装后位于 {@code /usr/bin/twscrape}; 本地开发需 {@code pip3 install twscrape}.
     */
    private String executable = "twscrape";

    /**
     * 忽略 twscrape 账号凭据校验(测试用). 生产保持 false — 无登录账号时 twscrape 会以退出码非 0 失败,
     * RetryableException 自动降级到 FxTwitter.
     */
    private boolean skipAccountCheck = false;
}
