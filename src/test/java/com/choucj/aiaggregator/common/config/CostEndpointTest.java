package com.choucj.aiaggregator.common.config;

import com.choucj.aiaggregator.monitoring.CostMonitor;
import com.choucj.aiaggregator.monitoring.CostSnapshot;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story 5.5 {@link CostEndpoint} 只读成本端点测试.
 */
class CostEndpointTest {

    @Test
    void shouldExposeCurrentMonthCostSnapshotWithoutSensitiveData() {
        CostMonitor costMonitor = mock(CostMonitor.class);
        CostSnapshot snapshot = new CostSnapshot(
                YearMonth.now().toString(),
                30,
                5,
                2,
                100,
                2,
                "OK",
                false,
                Map.of("deepseek", new CostSnapshot.ModelCost(10, 20, 2)));
        when(costMonitor.readMonthSnapshot(any(YearMonth.class))).thenReturn(snapshot);

        Map<String, Object> response = new CostEndpoint(costMonitor).costs();

        assertThat(response)
                .containsEntry("degraded", false)
                .containsEntry("month", YearMonth.now().toString())
                .containsEntry("snapshot", snapshot);
        assertThat(response.toString())
                .doesNotContainIgnoringCase("apiKey")
                .doesNotContainIgnoringCase("secret")
                .doesNotContainIgnoringCase("prompt");
    }

    @Test
    void shouldReturnDegradedResponseWhenCostSnapshotReadFails() {
        CostMonitor costMonitor = mock(CostMonitor.class);
        when(costMonitor.readMonthSnapshot(any(YearMonth.class))).thenThrow(new RuntimeException("redis down"));

        Map<String, Object> response = new CostEndpoint(costMonitor).costs();

        assertThat(response)
                .containsEntry("degraded", true)
                .containsEntry("errorType", "RuntimeException");
        assertThat(response).containsKey("month");
    }
}
