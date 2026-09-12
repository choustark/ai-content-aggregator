package com.choucj.aiaggregator.monitoring;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityComposeContractTest {

    private static final Path COMPOSE = Path.of("ops/observability/compose.observability.yml");

    @SuppressWarnings("unchecked")
    @Test
    void should_keep_plane_independent_when_compose_is_parsed() throws IOException {
        Map<String, Object> document = new Yaml().load(Files.readString(COMPOSE));
        Map<String, Map<String, Object>> services = (Map<String, Map<String, Object>>) document.get("services");

        assertThat(services).containsOnlyKeys("prometheus", "grafana", "cadvisor");
        services.forEach((name, service) -> assertThat((String) service.get("image"))
                .as("%s image must use tag and immutable digest", name)
                .matches("^[^:]+:[^@]+@sha256:[a-f0-9]{64}$")
                .doesNotContain("latest"));

        assertThat((Iterable<String>) services.get("prometheus").get("ports"))
                .allMatch(port -> port.startsWith("127.0.0.1:"));
        assertThat((Iterable<String>) services.get("grafana").get("ports"))
                .allMatch(port -> port.startsWith("127.0.0.1:"));
        assertThat(services.get("cadvisor")).doesNotContainKey("ports");
        assertThat((Iterable<String>) services.get("cadvisor").get("expose")).containsExactly("8080");
        assertThat((Iterable<String>) services.get("cadvisor").get("volumes"))
                .allMatch(volume -> volume.endsWith(":ro"));

        services.forEach((name, service) -> assertThat((Iterable<String>) service.get("networks"))
                .as("%s must only join the observability network", name)
                .containsExactly("observability"));
        Map<String, Object> networks = (Map<String, Object>) document.get("networks");
        assertThat(networks).containsOnlyKeys("observability");

        Map<String, Object> volumes = (Map<String, Object>) document.get("volumes");
        assertThat(volumes).containsKeys("prometheus-data", "grafana-data");
        Map<String, String> grafanaEnvironment = (Map<String, String>) services.get("grafana").get("environment");
        assertThat(grafanaEnvironment.get("GF_SECURITY_ADMIN_PASSWORD"))
                .isEqualTo("${GRAFANA_ADMIN_PASSWORD:?set GRAFANA_ADMIN_PASSWORD}");
        assertThat(Files.readString(COMPOSE))
                .contains("--storage.tsdb.retention.time=")
                .contains("--storage.tsdb.retention.size=")
                .contains("host-gateway")
                .doesNotContain("--web.enable-lifecycle");
    }
}
