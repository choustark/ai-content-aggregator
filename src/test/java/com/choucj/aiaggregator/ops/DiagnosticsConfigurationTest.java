package com.choucj.aiaggregator.ops;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证生产诊断部署契约，因为 GC 与 Arthas 的安全边界必须在发布前阻止配置漂移。
 */
class DiagnosticsConfigurationTest {

    private static final Path DOCKERFILE = Path.of("Dockerfile");
    private static final Path PRODUCTION_COMPOSE = Path.of("docker-compose.prod.yml");
    private static final Path PRODUCTION_PROFILE = Path.of("src/main/resources/application-prod.yml");
    private static final Path OPERATIONS_GUIDE = Path.of("ops/observability/README.md");

    @Test
    void should_pin_java_images_when_dockerfile_is_read() throws Exception {
        String dockerfile = Files.readString(DOCKERFILE);

        assertThat(dockerfile)
                .contains("FROM maven:3.9.11-eclipse-temurin-21@sha256:")
                .contains("FROM eclipse-temurin:21.0.12_8-jdk-alpine-3.24@sha256:")
                .doesNotContain("FROM maven:3.9-eclipse-temurin-21 ")
                .doesNotContain("FROM eclipse-temurin:21-jre-alpine\n");
    }

    @Test
    void should_isolate_management_surface_when_production_compose_is_read() throws Exception {
        String compose = Files.readString(PRODUCTION_COMPOSE);

        assertThat(compose)
                .contains("SPRING_PROFILES_ACTIVE: prod")
                .contains("127.0.0.1:${APP_PORT:-8080}:8080")
                .contains("${GC_LOG_DIR:?GC_LOG_DIR must be set}:/app/logs/gc")
                .doesNotContain("3658:")
                .doesNotContain("8563:")
                .doesNotContain("arthas:")
                .doesNotContain("privileged:")
                .doesNotContain("pid: host")
                .doesNotContain(":latest");
    }

    @Test
    void should_configure_rotating_gc_log_when_dockerfile_is_read() throws Exception {
        String dockerfile = Files.readString(DOCKERFILE);

        assertThat(dockerfile)
                .contains("RUN mkdir -p /app/logs/gc")
                .contains("-XX:+UseG1GC")
                .contains("-XX:MaxRAMPercentage=75.0")
                .contains("-Xlog:gc*,safepoint:file=/app/logs/gc/gc.log:time,uptime,level,tags:filecount=5,filesize=20M")
                .contains("ENV JAVA_TOOL_OPTIONS=")
                .contains("ENV JAVA_OPTS=\"\"");
    }

    @Test
    void should_keep_base_and_operator_jvm_options_separate_when_container_starts() throws Exception {
        String dockerfile = Files.readString(DOCKERFILE);

        assertThat(dockerfile)
                .contains("exec java $JAVA_OPTS -jar /app/app.jar")
                .doesNotContain("ENV JAVA_OPTS=\"-XX:+UseG1GC");
    }

    @Test
    void should_limit_actuator_exposure_when_production_profile_is_read() throws Exception {
        String profile = Files.readString(PRODUCTION_PROFILE);

        assertThat(profile)
                .contains("include: health,info,prometheus")
                .contains("env:\n      access: none")
                .doesNotContain("include: \"*\"");
    }

    @Test
    void should_document_bounded_and_non_public_arthas_when_operations_guide_is_read() throws Exception {
        String runbook = Files.readString(OPERATIONS_GUIDE);

        // 契约断言锚定命令/参数形态, 不锚定中文自然语言措辞 — 措辞润色不应打破构建。
        assertThat(runbook)
                .contains("SHA-256 `f45cd49cb69488490736bcbf3f66842e1eb4b55c300a658d51cf45400311d56d`")
                .contains("4.3.2")
                .contains("dashboard")
                .contains("jvm")
                .contains("thread -n 3")
                .contains("-n 5")
                .contains("profiler stop --file")
                .contains("3658/8563")
                .contains("heapdump")
                .contains("vmtool")
                .contains("retransform");
    }
}
