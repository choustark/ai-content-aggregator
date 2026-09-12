package com.choucj.aiaggregator.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 已启动单机观测栈的可选外部冒烟测试。
 *
 * <p>默认回归通过 Maven 的 {@code excludedGroups=external} 排除此测试。显式设置
 * {@code OBSERVABILITY_SMOKE=true} 并启用 {@code external-tests} profile 后，测试只读验证
 * Prometheus targets/query 和 Grafana health，不负责启动或停止容器。
 */
@Tag("external")
class ObservabilityStackSmokeTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Test
    @Timeout(30)
    void should_expose_healthy_targets_when_observability_stack_is_running() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("OBSERVABILITY_SMOKE")),
                "需先启动应用与观测栈，并设置 OBSERVABILITY_SMOKE=true");

        String prometheus = envOrDefault("PROMETHEUS_URL", "http://127.0.0.1:9090");
        String grafana = envOrDefault("GRAFANA_URL", "http://127.0.0.1:3000");

        JsonNode targets = getJson(prometheus + "/api/v1/targets");
        assertThat(targets.path("status").asText()).isEqualTo("success");
        Set<String> healthyJobs = new HashSet<>();
        for (JsonNode target : targets.path("data").path("activeTargets")) {
            if ("up".equals(target.path("health").asText())) {
                healthyJobs.add(target.path("labels").path("job").asText());
            }
        }
        assertThat(healthyJobs).as("应用与 cAdvisor target 应均为 UP")
                .contains("ai-content-aggregator", "cadvisor");

        JsonNode grafanaHealth = getJson(grafana + "/api/health");
        assertThat(grafanaHealth.path("database").asText()).isEqualTo("ok");

        JsonNode query = getJson(prometheus + "/api/v1/query?query="
                + URLEncoder.encode("up{job=\"ai-content-aggregator\"}", StandardCharsets.UTF_8));
        assertThat(query.path("data").path("result")).as("应用 up 指标应有 series").isNotEmpty();
    }

    private static JsonNode getJson(String url) throws Exception {
        HttpResponse<String> response = HTTP_CLIENT.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(url).isBetween(200, 299);
        return OBJECT_MAPPER.readTree(response.body());
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
