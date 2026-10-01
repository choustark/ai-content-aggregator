package com.choucj.aiaggregator.task.queue;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.observability.CorrelationContext;
import com.choucj.aiaggregator.common.model.ErrorCode;
import com.choucj.aiaggregator.monitoring.TaskMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 10.5 {@link TaskReplayController} 受控边界单测 —
 * 令牌鉴权矩阵(未配置全拒 / 缺失 403 / 不匹配 403 / 正确 200)、
 * 补跑结果码到 HTTP 语义映射(REPLAYED 200 / 非死信 404 / 数据不一致 409 /
 * 目标冲突 409 / Redis 不可用 503 / 意外异常 500)、
 * 指标隔离(故障不破坏补跑语义 — 10.5 review F12/F14)。
 */
@ExtendWith(MockitoExtension.class)
class TaskReplayControllerTest {

    private static final String TOKEN = "ops-secret-token";
    private static final String DEAD_TASK = "twitter:run:replay-source";

    @Mock
    private TaskQueue taskQueue;

    @Mock
    private TaskMetrics taskMetrics;

    private TaskReplayController controller;

    @BeforeEach
    void setUp() {
        controller = new TaskReplayController(taskQueue, Optional.of(taskMetrics), TOKEN);
    }

    @Test
    void should_reject_all_requests_when_token_not_configured() {
        // 开关开启但未配令牌 = 裸奔形态, 必须拒绝一切请求(即使请求头碰巧为空)
        TaskReplayController unconfigured =
                new TaskReplayController(taskQueue, Optional.empty(), "");

        ResponseEntity<Map<String, Object>> response = unconfigured.replay(DEAD_TASK, "", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("success", false);
        verifyNoInteractions(taskQueue);
    }

    @Test
    void should_return_403_when_token_missing() {
        ResponseEntity<Map<String, Object>> response = controller.replay(DEAD_TASK, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("success", false);
        verifyNoInteractions(taskQueue);
    }

    @Test
    void should_return_403_when_token_mismatched() {
        ResponseEntity<Map<String, Object>> response = controller.replay(DEAD_TASK, "wrong-token", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(taskQueue);
    }

    @Test
    void should_return_200_with_new_task_id_when_token_valid() {
        String newTaskId = DEAD_TASK + ":replay:1727000000000-ab12cd34";
        when(taskQueue.replayDeadLetter(DEAD_TASK, null))
                .thenReturn(new TaskQueue.ReplayResult(TaskQueue.ReplayOutcome.REPLAYED, newTaskId));

        ResponseEntity<Map<String, Object>> response = controller.replay(DEAD_TASK, TOKEN, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("success", true)
                .containsEntry("originalTaskId", DEAD_TASK)
                .containsEntry("newTaskId", newTaskId);
        verify(taskMetrics).recordRetry(TaskMetrics.RetryMetricOutcome.REPLAYED);
    }

    @Test
    void should_scope_correlation_context_to_replay_request() {
        MDC.put(CorrelationContext.CORRELATION_ID_KEY, "stale-correlation");
        when(taskQueue.replayDeadLetter(DEAD_TASK, null)).thenAnswer(invocation -> {
            assertThat(CorrelationContext.require()).isNotEqualTo("stale-correlation");
            assertThat(MDC.get(CorrelationContext.TASK_ID_KEY)).isEqualTo(DEAD_TASK);
            return new TaskQueue.ReplayResult(
                    TaskQueue.ReplayOutcome.REPLAYED, DEAD_TASK + ":replay:req");
        });

        ResponseEntity<Map<String, Object>> response = controller.replay(DEAD_TASK, TOKEN, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void should_return_200_even_when_metrics_absent() {
        // Optional<TaskMetrics> 缺失不影响补跑语义(Task 7.2 只观测契约)
        TaskReplayController withoutMetrics =
                new TaskReplayController(taskQueue, Optional.empty(), TOKEN);
        when(taskQueue.replayDeadLetter(DEAD_TASK, null))
                .thenReturn(new TaskQueue.ReplayResult(
                        TaskQueue.ReplayOutcome.REPLAYED, DEAD_TASK + ":replay:1-abcdefgh"));

        ResponseEntity<Map<String, Object>> response = withoutMetrics.replay(DEAD_TASK, TOKEN, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void should_return_200_even_when_metric_recording_throws() {
        // F14: 指标异常只降级告警 — 已成功的补跑不得被观测组件故障改写为 500
        when(taskQueue.replayDeadLetter(DEAD_TASK, null))
                .thenReturn(new TaskQueue.ReplayResult(
                        TaskQueue.ReplayOutcome.REPLAYED, DEAD_TASK + ":replay:2-12345678"));
        doThrow(new IllegalStateException("metrics registry broken"))
                .when(taskMetrics).recordRetry(TaskMetrics.RetryMetricOutcome.REPLAYED);

        ResponseEntity<Map<String, Object>> response = controller.replay(DEAD_TASK, TOKEN, null);

        assertThat(response.getStatusCode())
                .as("指标故障不得破坏已成功的补跑结果")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("success", true);
    }

    @Test
    void should_return_404_when_task_is_not_dead_letter() {
        // 非死信任务(不在 dead-letter 集合) → 返回码 NOT_DEAD_LETTER → 404
        when(taskQueue.replayDeadLetter("task-alive", null))
                .thenReturn(new TaskQueue.ReplayResult(
                        TaskQueue.ReplayOutcome.NOT_DEAD_LETTER, null));

        ResponseEntity<Map<String, Object>> response = controller.replay("task-alive", TOKEN, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("success", false);
        verify(taskMetrics, never()).recordRetry(any(TaskMetrics.RetryMetricOutcome.class));
    }

    @Test
    void should_return_409_when_original_state_inconsistent() {
        // F12: 死信集合成员但原 state 缺失/状态漂移(数据不一致) → 409 而非误报 404
        when(taskQueue.replayDeadLetter(DEAD_TASK, null))
                .thenReturn(new TaskQueue.ReplayResult(
                        TaskQueue.ReplayOutcome.ORIGINAL_STATE_INCONSISTENT, null));

        ResponseEntity<Map<String, Object>> response = controller.replay(DEAD_TASK, TOKEN, null);

        assertThat(response.getStatusCode())
                .as("数据不一致属服务端状态冲突(需运维核查), 404 会误导为'任务不存在'")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("success", false);
        verify(taskMetrics, never()).recordRetry(any(TaskMetrics.RetryMetricOutcome.class));
    }

    @Test
    void should_return_409_when_replay_target_conflicts() {
        // F12: 新 taskId state 已存在(目标冲突) → 409 可重试语义
        when(taskQueue.replayDeadLetter(DEAD_TASK, null))
                .thenReturn(new TaskQueue.ReplayResult(
                        TaskQueue.ReplayOutcome.TARGET_CONFLICT, null));

        ResponseEntity<Map<String, Object>> response = controller.replay(DEAD_TASK, TOKEN, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("success", false);
        verify(taskMetrics, never()).recordRetry(any(TaskMetrics.RetryMetricOutcome.class));
    }

    @Test
    void should_return_503_when_redis_unavailable() {
        // F12: RetryableException(Redis 连接异常) → 503 暂时不可用, 与 500 数据损坏区分
        when(taskQueue.replayDeadLetter(DEAD_TASK, null))
                .thenThrow(new RetryableException(ErrorCode.REDIS_CONNECTION_ERROR, "conn refused"));

        ResponseEntity<Map<String, Object>> response = controller.replay(DEAD_TASK, TOKEN, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("success", false);
        verify(taskMetrics, never()).recordRetry(any(TaskMetrics.RetryMetricOutcome.class));
    }

    @Test
    void should_return_500_when_replay_throws_unexpectedly() {
        when(taskQueue.replayDeadLetter(DEAD_TASK, null)).thenThrow(new IllegalStateException("boom"));

        ResponseEntity<Map<String, Object>> response = controller.replay(DEAD_TASK, TOKEN, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("success", false);
        verify(taskMetrics, never()).recordRetry(any(TaskMetrics.RetryMetricOutcome.class));
    }

    @Test
    void should_return_500_when_type_check_rejected() {
        // NonRetryableException(TYPE 拒绝, 数据损坏) → 500 需运维介入, 非 404
        when(taskQueue.replayDeadLetter(DEAD_TASK, null))
                .thenThrow(new NonRetryableException(ErrorCode.REDIS_DATA_ERROR,
                        "TYPE 前置校验拒绝"));

        ResponseEntity<Map<String, Object>> response = controller.replay(DEAD_TASK, TOKEN, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("success", false);
        verify(taskMetrics, never()).recordRetry(any(TaskMetrics.RetryMetricOutcome.class));
    }

    // ============ Story 10.5 review F-R1: 幂等请求标识 ============

    @Test
    void should_pass_request_id_through_when_valid() {
        // 合法 requestId 原样透传 — 新任务 ID 由 TaskQueue 确定性派生 <orig>:replay:<requestId>
        String newTaskId = DEAD_TASK + ":replay:incident-42";
        when(taskQueue.replayDeadLetter(DEAD_TASK, "incident-42"))
                .thenReturn(new TaskQueue.ReplayResult(TaskQueue.ReplayOutcome.REPLAYED, newTaskId));

        ResponseEntity<Map<String, Object>> response = controller.replay(DEAD_TASK, TOKEN, "incident-42");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("newTaskId", newTaskId);
        verify(taskQueue).replayDeadLetter(DEAD_TASK, "incident-42");
    }

    @Test
    void should_return_400_when_request_id_format_invalid() {
        // requestId 含空格/特殊字符 → 客户端格式校验 400, 不触达 TaskQueue(脚本不会执行)
        ResponseEntity<Map<String, Object>> response = controller.replay(DEAD_TASK, TOKEN, "bad id!");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("success", false);
        verifyNoInteractions(taskQueue);
    }

    @Test
    void should_return_400_when_request_id_exceeds_max_length() {
        // requestId 超 64 字符 → 400
        String tooLong = "a".repeat(65);

        ResponseEntity<Map<String, Object>> response = controller.replay(DEAD_TASK, TOKEN, tooLong);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(taskQueue);
    }

    @Test
    void should_treat_blank_request_id_as_default_when_valid_token() {
        // 空 requestId 走缺省派生(当日日期) — 透传原值, 由 TaskQueue 统一处理
        when(taskQueue.replayDeadLetter(DEAD_TASK, "  "))
                .thenReturn(new TaskQueue.ReplayResult(
                        TaskQueue.ReplayOutcome.REPLAYED, DEAD_TASK + ":replay:2026-10-01"));

        ResponseEntity<Map<String, Object>> response = controller.replay(DEAD_TASK, TOKEN, "  ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(taskQueue).replayDeadLetter(DEAD_TASK, "  ");
    }
}
