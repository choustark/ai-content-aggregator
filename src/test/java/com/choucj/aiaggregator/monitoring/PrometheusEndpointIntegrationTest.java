package com.choucj.aiaggregator.monitoring;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 Prometheus 端点的正反路径，以确保生产观测能力可采集且敏感端点不被顺带开放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.health.redis.enabled=false",
        "schedule.run-on-startup=false",
        "wechat.mp.enabled=false",
        "features.github.enabled=false",
        "feature-flags.github.enabled=false",
        "features.rag.enabled=false",
        "feature-flags.rag.enabled=false",
        "langchain4j.community.redis.enabled=false"
})
@ActiveProfiles({"prod", "test"})
@AutoConfigureObservability
class PrometheusEndpointIntegrationTest {

    @MockBean
    private com.choucj.aiaggregator.task.queue.TaskQueue taskQueue;

    @MockBean
    private CostMonitor costMonitor;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void should_export_core_metrics_when_prometheus_endpoint_is_requested() {
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("jvm_memory_")
                .contains("process_cpu_")
                .contains("http_server_requests_seconds_")
                .contains("aiaggregator_task_queue_size");
    }

    @Test
    void should_not_expose_env_or_write_endpoints_when_management_surface_is_restricted() {
        assertThat(restTemplate.getForEntity("/actuator/env", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity("/actuator/loggers", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.getForEntity("/actuator/heapdump", byte[].class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(restTemplate.postForEntity("/actuator/shutdown", null, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
