package com.choucj.aiaggregator.ops;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实容器验证：只在显式授权的 external 测试中构建并启动生产镜像，确认 GC 参数真实生效。
 *
 * <p>断言读取 PID 1 的 {@code /proc/1/environ} 而非 {@code /proc/1/cmdline} —
 * GC 契约经 {@code ENV JAVA_TOOL_OPTIONS} 注入, 是环境变量而非 argv 参数
 * (ENTRYPOINT 为 {@code exec java $JAVA_OPTS -jar /app/app.jar}), cmdline 中不会出现。
 * 无论断言结果如何都会删除临时容器与测试镜像，不复用任何开发环境容器。
 *
 * <p>{@code @Timeout} 只是防挂死兜底: 冷缓存 docker build 含全量 mvn 依赖下载,
 * 可能远超常规定位超时; 子进程在中断路径上会被 destroyForcibly, 不会遗留孤儿构建进程。
 */
@Tag("external")
class ContainerGcOptionsExternalTest {

    private static final String IMAGE = "ai-content-aggregator:story-10-3-gc-test";
    private static final String CONTAINER = "story-10-3-gc-options-test";

    @Test
    @Timeout(1800)
    void should_start_container_with_configured_gc_options() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("STORY_10_3_GC_CONTAINER_TEST")),
                "需显式设置 STORY_10_3_GC_CONTAINER_TEST=true 才会构建并启动 Docker 容器");
        assumeTrue(run("docker", "info").exitCode() == 0, "Docker daemon 不可用");

        run("docker", "rm", "-f", CONTAINER);
        try {
            assertThat(run("docker", "build", "-t", IMAGE, ".").exitCode()).isZero();
            CommandResult started = run("docker", "run", "-d", "--name", CONTAINER,
                    "--memory", "1g", "--cpus", "1.0", IMAGE);
            assertThat(started.exitCode()).isZero();

            String environ = "";
            for (int attempt = 0; attempt < 10 && environ.isBlank(); attempt++) {
                Thread.sleep(1_000);
                CommandResult result = run("docker", "exec", CONTAINER, "sh", "-c",
                        "tr '\\000' '\\n' </proc/1/environ");
                if (result.exitCode() == 0) {
                    environ = result.output();
                }
            }
            assertThat(environ)
                    .contains("JAVA_TOOL_OPTIONS=-XX:+UseG1GC")
                    .contains("-XX:MaxRAMPercentage=75.0")
                    .contains("-Xlog:gc*,safepoint:file=/app/logs/gc/gc.log");
        } finally {
            run("docker", "rm", "-f", CONTAINER);
            run("docker", "rmi", "-f", IMAGE);
        }
    }

    private static CommandResult run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(List.of(command)).redirectErrorStream(true).start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new CommandResult(process.waitFor(), output);
        } catch (InterruptedException interrupted) {
            // @Timeout 中断路径: 杀掉子进程, 不遗留孤儿 docker build/run.
            process.destroyForcibly();
            throw interrupted;
        }
    }

    private record CommandResult(int exitCode, String output) {
    }
}
