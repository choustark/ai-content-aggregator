package com.choucj.aiaggregator.common.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.choucj.aiaggregator.processor.GitHubProcessor;
import com.choucj.aiaggregator.processor.TwitterProcessor;
import com.choucj.aiaggregator.processor.config.ProcessorProperties;
import com.choucj.aiaggregator.task.queue.TaskQueue;
import com.choucj.aiaggregator.task.queue.TaskRecoveryRunner;
import com.choucj.aiaggregator.task.scheduler.ContentScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证最终控制台日志采用可解析的 ECS JSON，因为配置存在不等于运行时编码器真正生效。
 *
 * <p>引用源：Story 10.2（创建）。
 */
@SpringBootTest(properties = {
        "wechat.mp.enabled=false",
        "schedule.run-on-startup=false"
})
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(StructuredLoggingIntegrationTest.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void should_emit_ecs_json_when_test_configuration_enables_structured_console(CapturedOutput output) throws Exception {
        String marker = "structured-log-probe";

        LOG.info(marker);

        JsonNode event = Arrays.stream(output.getOut().split("\\R"))
                .filter(line -> line.contains(marker))
                .map(StructuredLoggingIntegrationTest::readJson)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到结构化日志探针"));
        assertThat(event.path("@timestamp").asText()).isNotBlank();
        assertThat(event.path("log").path("level").asText()).isEqualTo("INFO");
        assertThat(event.path("log").path("logger").asText())
                .isEqualTo(StructuredLoggingIntegrationTest.class.getName());
        assertThat(event.path("message").asText()).isEqualTo(marker);
        assertThat(event.path("service").path("name").asText()).isEqualTo("ai-content-aggregator");
        assertThat(event.path("ecs").path("version").asText()).isEqualTo("8.11");
    }

    @Test
    void should_emit_sanitized_message_when_input_contains_secret(CapturedOutput output) {
        String marker = "sanitizer-probe";
        List<String> canaries = List.of("auth-canary", "cookie-canary", "api-canary", "token-canary", "pwd-canary");
        String unsafe = "line1\nline2 Authorization=Bearer " + canaries.get(0)
                + ", Cookie=" + canaries.get(1)
                + ", api-key=" + canaries.get(2)
                + ", token=" + canaries.get(3)
                + ", password=" + canaries.get(4);

        LOG.info("{} {}", marker, LogSanitizer.sanitize(unsafe, 300));

        String line = Arrays.stream(output.getOut().split("\\R"))
                .filter(candidate -> candidate.contains(marker))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到安全日志探针"));
        JsonNode event = readJson(line);
        assertThat(event.path("message").asText())
                .contains("line1\\nline2", "Authorization=***", "Cookie=***", "api-key=***", "token=***", "password=***")
                .doesNotContain("\n", "\r");
        canaries.forEach(canary -> assertThat(line).doesNotContain(canary));
    }

    @Test
    void should_correlate_scheduler_and_async_logs_when_task_crosses_thread_boundary(CapturedOutput output) throws Exception {
        TaskQueue taskQueue = mock(TaskQueue.class);
        TaskRecoveryRunner recoveryRunner = mock(TaskRecoveryRunner.class);
        TwitterProcessor twitterProcessor = mock(TwitterProcessor.class);
        ProcessorProperties properties = mock(ProcessorProperties.class);
        when(properties.getTaskIdPrefix()).thenReturn("twitter");
        when(taskQueue.getProcessingTasks()).thenReturn(Set.of());
        when(taskQueue.isQueued("twitter:run")).thenReturn(true);
        when(taskQueue.poll(eq(0L), eq(TimeUnit.SECONDS)))
                .thenReturn("twitter:42")
                .thenReturn(null);
        ExecutorService asyncExecutor = Executors.newSingleThreadExecutor();
        try {
            doAnswer(invocation -> {
                LOG.info("scheduler-chain-sync");
                asyncExecutor.submit(MdcPropagation.wrap(() -> LOG.info("scheduler-chain-async"))).get();
                return null;
            }).when(twitterProcessor).process();
            ContentScheduler scheduler = new ContentScheduler(taskQueue, recoveryRunner, twitterProcessor,
                    Optional.<GitHubProcessor>empty(), properties, false);

            scheduler.processContent();

            List<JsonNode> events = Arrays.stream(output.getOut().split("\\R"))
                    .filter(line -> line.contains("scheduler-chain-"))
                    .map(StructuredLoggingIntegrationTest::readJson)
                    .toList();
            assertThat(events).hasSize(2);
            String correlationId = events.getFirst().path("correlationId").asText();
            assertThat(correlationId).isNotBlank();
            assertThat(events).allSatisfy(event -> {
                assertThat(event.path("correlationId").asText()).isEqualTo(correlationId);
                assertThat(event.path("taskId").asText()).isEqualTo("twitter:42");
            });
        } finally {
            asyncExecutor.shutdownNow();
        }
    }

    private static JsonNode readJson(String line) {
        try {
            return OBJECT_MAPPER.readTree(line);
        } catch (Exception e) {
            throw new AssertionError("日志不是合法的单行 JSON: " + line, e);
        }
    }
}
