package com.choucj.aiaggregator.processor.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Twitter 处理流水线配置 (Story 2.6).
 *
 * <p>对应 {@link com.choucj.aiaggregator.processor.TwitterProcessor} 的运行参数:
 * <ul>
 *   <li>{@link #faultIsolationEnabled} — 故障隔离总开关 (true: per-article 异常隔离不阻塞整批,
 *       false: 调试用, 异常透传到顶层 ContentScheduler)</li>
 *   <li>{@link #taskIdPrefix} — taskId 前缀, 用于 {@code ContentScheduler.processTask} 路由
 *       (默认 {@code "twitter"}, 对应批量触发 {@code twitter:run} /
 *       单文章跟踪 {@code twitter:tweet:{tweetId}})</li>
 * </ul>
 *
 * <p>配置示例 ({@code application.yml}):
 * <pre>{@code
 * processor:
 *   fault-isolation-enabled: true
 *   task-id-prefix: "twitter"
 * }</pre>
 *
 * <p>校验 (W7/W8 + W12 模式, Story 2.4/2.5 review lessons 复用):
 * <ul>
 *   <li>W7/W8: {@link #taskIdPrefix} 加 {@link NotBlank}, 防误配空值导致路由失效</li>
 *   <li>W12: {@link ProcessorPropertiesTest} 加负向校验用例验证注解生效</li>
 * </ul>
 *
 * <p>模式参考: Story 2.5 {@link com.choucj.aiaggregator.publish.storage.config.ArchiverProperties} /
 * Story 2.3b FilterProperties / Story 2.4 RewriterProperties.
 */
@ConfigurationProperties(prefix = "processor")
@Validated
@Data
public class ProcessorProperties {

    /**
     * 故障隔离总开关 (默认 true).
     *
     * <p>{@code true} (生产默认): TwitterProcessor 内部用 per-article try/catch + continue
     * 单条失败不阻塞其他, 失败计入 failureCount 由 summary 日志输出.
     *
     * <p>{@code false} (调试用): per-article 异常直接透传到 process() 顶层,
     * 由 ContentScheduler 顶层 catch 兜底处理 (Retryable 留 processing 集合 / NonRetryable complete).
     * 仅用于排查问题时定位具体阶段, 不建议生产使用.
     */
    private boolean faultIsolationEnabled = true;

    /**
     * taskId 前缀 (默认 {@code "twitter"}).
     *
     * <p>用于 {@code ContentScheduler.processTask(taskId)} 按 taskId 前缀路由到具体 Processor:
     * <ul>
     *   <li>{@code twitter:run} / {@code twitter:tweet:{id}} → TwitterProcessor.process()</li>
     *   <li>{@code github:...} → GitHubProcessor (Epic 4 待扩展)</li>
     * </ul>
     *
     * <p>修改此值会同步影响 {@code ContentScheduler.processTask} 的 {@code startsWith(prefix + ":")}
     * 路由判断. 多 Processor 同前缀会冲突, 部署时需保证唯一性.
     */
    @NotBlank(message = "processor.task-id-prefix 不能为空")
    private String taskIdPrefix = "twitter";
}
