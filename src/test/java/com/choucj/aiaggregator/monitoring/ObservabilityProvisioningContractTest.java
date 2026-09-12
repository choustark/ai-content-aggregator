package com.choucj.aiaggregator.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityProvisioningContractTest {

    private static final Path ROOT = Path.of("ops/observability");
    private static final String DATASOURCE_UID = "aiaggregator-prometheus";

    @SuppressWarnings("unchecked")
    @Test
    void should_configure_real_targets_when_prometheus_yaml_is_parsed() throws IOException {
        Map<String, Object> config = new Yaml().load(Files.readString(ROOT.resolve("prometheus.yml")));
        assertThat((List<String>) config.get("rule_files"))
                .containsExactly("/etc/prometheus/alerts.yml");

        List<Map<String, Object>> scrapeConfigs = (List<Map<String, Object>>) config.get("scrape_configs");
        Map<String, Object> application = scrapeConfigs.stream()
                .filter(item -> "ai-content-aggregator".equals(item.get("job_name")))
                .findFirst().orElseThrow();
        Map<String, Object> cadvisor = scrapeConfigs.stream()
                .filter(item -> "cadvisor".equals(item.get("job_name")))
                .findFirst().orElseThrow();

        assertThat(application.get("metrics_path")).isEqualTo("/actuator/prometheus");
        assertThat(targetsOf(application)).containsExactly("aiaggregator-host:8080");
        assertThat(targetsOf(cadvisor)).containsExactly("cadvisor:8080");
    }

    @SuppressWarnings("unchecked")
    @Test
    void should_provision_stable_datasource_when_yaml_is_parsed() throws IOException {
        Map<String, Object> datasource = new Yaml().load(Files.readString(
                ROOT.resolve("grafana/provisioning/datasources/prometheus.yml")));
        List<Map<String, Object>> datasources = (List<Map<String, Object>>) datasource.get("datasources");
        assertThat(datasources).singleElement().satisfies(item -> {
            assertThat(item.get("uid")).isEqualTo(DATASOURCE_UID);
            assertThat(item.get("url")).isEqualTo("http://prometheus:9090");
            assertThat(item.get("isDefault")).isEqualTo(true);
        });

        Map<String, Object> provider = new Yaml().load(Files.readString(
                ROOT.resolve("grafana/provisioning/dashboards/dashboard.yml")));
        List<Map<String, Object>> providers = (List<Map<String, Object>>) provider.get("providers");
        Map<String, Object> options = (Map<String, Object>) providers.getFirst().get("options");
        assertThat(options.get("path")).isEqualTo("/var/lib/grafana/dashboards");
    }

    @Test
    void should_provide_required_queries_when_dashboard_is_loaded() throws IOException {
        JsonNode dashboard = new ObjectMapper().readTree(
                Files.readString(ROOT.resolve("grafana/dashboards/aiaggregator-overview.json")));
        assertThat(dashboard.path("uid").asText()).isEqualTo("aiaggregator-overview");
        assertThat(dashboard.toString()).contains("\"uid\":\"" + DATASOURCE_UID + "\"");

        String json = dashboard.toString();
        assertThat(json)
                .contains("up{job=\\\"ai-content-aggregator\\\"}")
                .contains("jvm_memory_used_bytes")
                .contains("jvm_gc_pause_seconds")
                .contains("jvm_threads_live_threads")
                .contains("process_cpu_usage")
                .contains("container_cpu_usage_seconds_total")
                .contains("container_memory_working_set_bytes")
                .contains("http_server_requests_seconds_count")
                .contains("aiaggregator_task_queue_size")
                .contains("aiaggregator_task_processed_total")
                .contains("\"noValue\":\"N/A\"")
                .contains("队列执行结果");
    }

    @SuppressWarnings("unchecked")
    private static List<String> targetsOf(Map<String, Object> scrapeConfig) {
        List<Map<String, Object>> staticConfigs = (List<Map<String, Object>>) scrapeConfig.get("static_configs");
        return (List<String>) staticConfigs.getFirst().get("targets");
    }
}
