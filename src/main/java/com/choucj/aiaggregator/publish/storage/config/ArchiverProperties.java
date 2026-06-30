package com.choucj.aiaggregator.publish.storage.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Markdown 归档器配置 (Story 2.5).
 *
 * <p>对应 {@link com.choucj.aiaggregator.publish.storage.MarkdownArchiver} 的运行参数:
 * <ul>
 *   <li>{@link #baseDirectory} — 归档根目录 (相对路径运行目录 / 绝对路径 Docker 卷挂载)</li>
 *   <li>{@link #enabled} — 总开关, 关闭时 MarkdownArchiver Bean 不注册 (@ConditionalOnProperty)</li>
 *   <li>{@link #datePattern} — 文件名日期格式, 默认 {@code yyyy-MM-dd} (ISO 8601)</li>
 *   <li>{@link #fileSuffix} — 文件扩展名, 默认 {@code .md}</li>
 *   <li>{@link #separator} — 同日多文章块之间的分隔符 (Markdown 水平分割线)</li>
 * </ul>
 *
 * <p>配置示例 ({@code application.yml}):
 * <pre>{@code
 * archive:
 *   base-directory: "archive"
 *   enabled: true
 *   date-pattern: "yyyy-MM-dd"
 *   file-suffix: ".md"
 *   separator: "\n\n---\n\n"
 * }</pre>
 *
 * <p>校验 (W7/W8/N1 + W12 模式, Story 2.4 review lessons 复用):
 * <ul>
 *   <li>W7/W8: 字符串字段加 {@link NotBlank}, 防误配空值导致路径解析失败</li>
 *   <li>W12: {@link com.choucj.aiaggregator.publish.storage.config.ArchiverPropertiesTest}
 *       加负向校验用例验证注解生效</li>
 * </ul>
 *
 * <p>Docker 卷挂载对应 (architecture.md L1351): {@code ./archive:/app/archive}.
 */
@ConfigurationProperties(prefix = "archive")
@Validated
@Data
public class ArchiverProperties {

    /**
     * 归档根目录. 相对路径基于 JVM 工作目录 (本地开发 {@code ./archive}, Docker {@code /app/archive}).
     * <p>归档文件路径 = {@code {base-directory}/{yyyy-MM-dd}.md}.
     * <p>目录不存在时由 {@link com.choucj.aiaggregator.publish.storage.MarkdownArchiver#ensureDirectoryExists}
     * 自动递归创建 ({@code Files.createDirectories}).
     */
    @NotBlank(message = "archive.base-directory 不能为空")
    private String baseDirectory = "archive";

    /**
     * 总开关. 关闭时 MarkdownArchiver Bean 不注册
     * (@ConditionalOnProperty(name="archive.enabled", havingValue="true", matchIfMissing=true)).
     * <p>仅在故障排查或临时禁用归档时设为 false.
     */
    private boolean enabled = true;

    /**
     * 文件名日期格式. 默认 {@code yyyy-MM-dd} (ISO 8601 严格).
     * <p>由 {@link java.time.format.DateTimeFormatter#ofPattern(String)} 解析, 非法格式在
     * MarkdownArchiver 初始化 ({@code @PostConstruct}) 抛 IllegalArgumentException.
     * <p>校验: 仅允许 {@code [-_a-zA-Z0-9]} 字符 (拒绝 {@code /} 防子目录逃逸, 拒绝 {@code ..} 防路径穿越).
     */
    @NotBlank(message = "archive.date-pattern 不能为空")
    @Pattern(regexp = "[-_a-zA-Z0-9]+",
            message = "archive.date-pattern 仅允许字母/数字/-/_ 字符 (拒绝 / 与 .. 防路径穿越)")
    private String datePattern = "yyyy-MM-dd";

    /**
     * 文件扩展名. 默认 {@code .md} (Markdown).
     * <p>校验: 不允许包含路径分隔符 {@code /} 或 {@code \} (防跨平台路径穿越).
     */
    @NotBlank(message = "archive.file-suffix 不能为空")
    @Pattern(regexp = "^[^/\\\\]*$", message = "archive.file-suffix 不能包含路径分隔符 / 或 \\")
    private String fileSuffix = ".md";

    /**
     * 同日多文章块之间的分隔符. 默认 {@code \n\n---\n\n} (Markdown 水平分割线).
     * <p>视觉清晰区分同日多篇文章, 同时不破坏 Markdown 渲染.
     */
    @NotBlank(message = "archive.separator 不能为空")
    private String separator = "\n\n---\n\n";
}
