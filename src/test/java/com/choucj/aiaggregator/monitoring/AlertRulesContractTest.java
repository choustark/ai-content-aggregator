package com.choucj.aiaggregator.monitoring;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AlertRulesContractTest {

    @SuppressWarnings("unchecked")
    @Test
    void should_define_required_alerts_when_rules_are_parsed() throws IOException {
        Map<String, Object> document = new Yaml().load(Files.readString(
                Path.of("ops/observability/alerts.yml")));
        List<Map<String, Object>> groups = (List<Map<String, Object>>) document.get("groups");
        List<Map<String, Object>> rules = groups.stream()
                .flatMap(group -> ((List<Map<String, Object>>) group.get("rules")).stream())
                .filter(rule -> rule.containsKey("alert"))
                .toList();
        Set<String> names = rules.stream().map(rule -> (String) rule.get("alert")).collect(Collectors.toSet());

        assertThat(names).contains(
                "AiAggregatorApplicationDown",
                "AiAggregatorContentWindowMissed",
                "AiAggregatorBatchPublishWindowMissed",
                "AiAggregatorTaskQueueBacklog",
                "AiAggregatorTaskQueueSignalUnavailable",
                "AiAggregatorTaskProcessingStuck",
                "AiAggregatorTaskOldestAgeHigh",
                "AiAggregatorCadvisorDown",
                "AiAggregatorContainerMemoryPressure",
                "AiAggregatorContainerCpuThrottlingHigh",
                "AiAggregatorExternalDependencyConsecutiveFailures",
                "AiAggregatorCostMetricsInvalid",
                "AiAggregatorMonthlyCostWarning",
                "AiAggregatorMonthlyCostHalted");

        rules.forEach(rule -> {
            assertThat(rule.get("expr")).as("%s expr", rule.get("alert")).isNotNull();
            assertThat(rule.get("for")).as("%s for", rule.get("alert")).isNotNull();
            Map<String, Object> labels = (Map<String, Object>) rule.get("labels");
            Map<String, Object> annotations = (Map<String, Object>) rule.get("annotations");
            assertThat(labels).containsKey("severity");
            assertThat(annotations).containsKeys("summary", "runbook", "recovery");
        });

        String ruleTests = Files.readString(Path.of("ops/observability/tests/alerts.test.yml"));
        assertThat(ruleTests)
                .contains("alert_rule_test")
                .contains("AiAggregatorApplicationDown")
                .contains("AiAggregatorContentWindowMissed")
                .contains("AiAggregatorBatchPublishWindowMissed")
                .contains("AiAggregatorTaskQueueBacklog")
                .contains("AiAggregatorTaskOldestAgeHigh")
                .contains("AiAggregatorCadvisorDown")
                .contains("AiAggregatorContainerMemoryPressure")
                .contains("AiAggregatorContainerCpuThrottlingHigh")
                .contains("AiAggregatorExternalDependencyConsecutiveFailures")
                .contains("AiAggregatorCostMetricsInvalid")
                .contains("AiAggregatorMonthlyCostWarning")
                .contains("AiAggregatorMonthlyCostHalted");

        Map<String, String> expressions = rules.stream().collect(Collectors.toMap(
                rule -> (String) rule.get("alert"), rule -> String.valueOf(rule.get("expr"))));
        assertThat(expressions.get("AiAggregatorMonthlyCostWarning"))
                .contains("level=\"stop\"")
                .contains("level=\"warning\"")
                .contains("> 0");
        assertThat(expressions.get("AiAggregatorMonthlyCostHalted"))
                .contains("level=\"stop\"")
                .contains("level=\"warning\"")
                .contains("> 0");
        assertThat(expressions.get("AiAggregatorTaskQueueSignalUnavailable"))
                .contains("aiaggregator_task_queue_collection_last_success_seconds")
                .contains("> 300");
    }
}
