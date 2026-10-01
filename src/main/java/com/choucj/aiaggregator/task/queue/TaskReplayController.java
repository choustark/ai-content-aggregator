package com.choucj.aiaggregator.task.queue;

import com.choucj.aiaggregator.common.exception.NonRetryableException;
import com.choucj.aiaggregator.common.exception.RetryableException;
import com.choucj.aiaggregator.common.observability.CorrelationContext;
import com.choucj.aiaggregator.monitoring.TaskMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;

/**
 * 受控的人工补跑入口(Story 10.5 AC4)。
 *
 * <p><b>受控边界(双层, 逐条复刻 {@link TaskKeyMigrationController} 先例):</b>
 * <ol>
 *   <li><b>启用开关:</b> 默认关闭, 仅当 {@code task.replay.enabled=true} 时本 Bean 才注册
 *       ({@code @ConditionalOnProperty(havingValue="true")} 无 {@code matchIfMissing})。</li>
 *   <li><b>共享密钥鉴权:</b> 请求须携带 {@code X-Replay-Token} 请求头,
 *       与 {@code task.replay.token} 配置比对(常量时间比较防时序侧信道)。
 *       令牌<b>未配置或为空时拒绝一切请求</b> — 防止"开关开启但未配令牌"的裸奔形态;
 *       不匹配返回 403。令牌来自环境变量注入(不得写入提交版配置文件)。</li>
 * </ol>
 *
 * <p><b>补跑语义:</b> 经 {@link TaskQueue#replayDeadLetter(String, String)} 高层命令执行 —
 * 保留原死信审计记录, 创建带 {@code replayedFrom} 关联的新 pending 任务;
 * 不通过直接修改 Redis 数据结构绕过任务状态机(AC4)。非死信任务返回 404。
 *
 * <p><b>幂等请求标识(10.5 review F-R1):</b> 可选请求头 {@code X-Replay-Request-Id}
 * 指定补跑幂等标识 — 新任务 ID 确定性派生为 {@code <orig>:replay:<requestId>},
 * 同 taskId + 同 requestId 的重复补跑(含网络重试场景)经 REPLAY 脚本 EXISTS 守卫
 * 原子拒绝, 返回 409(TARGET_CONFLICT, 幂等重复信号, 原死信记录不受影响)。
 * 缺省时由 TaskQueue 以当日日期派生(同日重复补跑天然幂等)。
 * 客户端传入的 requestId 先经格式校验, 非法返回 400。
 *
 * <p>引用源: Story 10.5 创建(2026-09-22)。
 */
@RestController
@RequestMapping("/api/tasks")
@Slf4j
@ConditionalOnProperty(prefix = "task.replay", name = "enabled", havingValue = "true")
public class TaskReplayController {

    /** 补跑访问令牌请求头名称. */
    static final String TOKEN_HEADER = "X-Replay-Token";

    /** 补跑幂等请求标识请求头名称(可选). */
    static final String REQUEST_ID_HEADER = "X-Replay-Request-Id";

    /** 幂等请求标识合法格式(与 {@code TaskQueue.sanitizeReplayRequestId} 口径一致). */
    static final String REQUEST_ID_PATTERN = "[A-Za-z0-9._-]{1,64}";

    private final TaskQueue taskQueue;
    private final Optional<TaskMetrics> taskMetricsOptional;
    private final String configuredToken;

    /**
     * 手写构造器(项目规则: 含 {@code @Value} 默认值的注入不能用 {@code @RequiredArgsConstructor}).
     *
     * @param taskQueue            任务队列(补跑高层命令唯一入口)
     * @param taskMetricsOptional  指标组件(Optional 容错注入, 补跑成功累计
     *                             {@code aiaggregator.task.retry{outcome=replayed}}; 缺失不影响补跑语义)
     * @param configuredToken      共享密钥({@code task.replay.token}; 为空表示未配置, 拒绝一切请求)
     */
    public TaskReplayController(TaskQueue taskQueue,
                                Optional<TaskMetrics> taskMetricsOptional,
                                @Value("${task.replay.token:}") String configuredToken) {
        this.taskQueue = taskQueue;
        this.taskMetricsOptional = taskMetricsOptional;
        this.configuredToken = configuredToken;
    }

    /**
     * 人工补跑指定死信任务.
     *
     * @param taskId    死信任务 ID(路径变量)
     * @param token     请求头 {@code X-Replay-Token} 携带的访问令牌
     * @param requestId 可选请求头 {@code X-Replay-Request-Id} 携带的幂等请求标识
     *                  (缺省由 TaskQueue 以当日日期派生)
     * @return 200 补跑成功(body 含新 taskId) / 400 幂等请求标识格式非法 /
     *         403 令牌缺失或不匹配 / 404 非死信任务 /
     *         409 数据不一致, 或幂等重复补跑/目标冲突(原死信记录未受影响) /
     *         503 Redis 暂时不可用 / 500 其他异常(可修复后重试)
     */
    @PostMapping("/replay/{taskId}")
    public ResponseEntity<Map<String, Object>> replay(
            @PathVariable("taskId") String taskId,
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestHeader(value = REQUEST_ID_HEADER, required = false) String requestId) {
        CorrelationContext.begin(taskId);
        try {
            return replayInContext(taskId, token, requestId);
        } finally {
            CorrelationContext.end();
        }
    }

    private ResponseEntity<Map<String, Object>> replayInContext(
            String taskId, String token, String requestId) {
        if (configuredToken == null || configuredToken.isBlank()) {
            log.error("补跑入口已启用但未配置访问令牌(task.replay.token), 拒绝执行");
            return ResponseEntity.status(403).body(Map.of(
                    "success", false,
                    "message", "补跑入口未配置访问令牌, 拒绝执行"));
        }
        if (token == null || !constantTimeEquals(token, configuredToken)) {
            log.error("补跑请求令牌校验失败, 拒绝执行");
            return ResponseEntity.status(403).body(Map.of(
                    "success", false,
                    "message", "补跑访问令牌无效"));
        }
        if (requestId != null && !requestId.isBlank() && !requestId.matches(REQUEST_ID_PATTERN)) {
            log.warn("补跑请求幂等标识格式非法, 拒绝执行: taskId={}, requestId={}", taskId, requestId);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "X-Replay-Request-Id 须匹配 [A-Za-z0-9._-]{1,64}"));
        }
        log.info("受控入口触发死信任务人工补跑: taskId={}, requestId={}",
                taskId, requestId == null || requestId.isBlank() ? "<default>" : requestId);
        try {
            TaskQueue.ReplayResult result = taskQueue.replayDeadLetter(taskId, requestId);
            return switch (result.outcome()) {
                case REPLAYED -> {
                    recordReplayedMetricSafely(taskId);
                    yield ResponseEntity.ok(Map.of(
                            "success", true,
                            "originalTaskId", taskId,
                            "newTaskId", result.newTaskId()));
                }
                case NOT_DEAD_LETTER -> {
                    log.warn("死信任务补跑被拒: 任务不是死信成员, taskId={}", taskId);
                    yield ResponseEntity.status(404).body(Map.of(
                            "success", false,
                            "message", "任务不是死信成员, 仅死信任务可补跑"));
                }
                case ORIGINAL_STATE_INCONSISTENT -> {
                    log.error("死信任务补跑被拒: 死信集合成员但原 state 缺失或状态非 DEAD_LETTER"
                            + "(数据不一致, 需运维核查), taskId={}", taskId);
                    yield ResponseEntity.status(409).body(Map.of(
                            "success", false,
                            "message", "死信任务状态数据不一致, 拒绝补跑, 需运维核查"));
                }
                case TARGET_CONFLICT -> {
                    log.warn("死信任务补跑被拒: 幂等重复补跑或目标冲突(新任务 state 已存在, "
                            + "原死信记录不受影响), taskId={}", taskId);
                    yield ResponseEntity.status(409).body(Map.of(
                            "success", false,
                            "message", "补跑目标已存在(幂等重复补跑或冲突), 原死信记录未受影响"));
                }
            };
        } catch (RetryableException e) {
            log.error("死信任务补跑失败(Redis 暂时不可用, 原死信审计记录未受影响): taskId={}", taskId, e);
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "message", "存储暂不可用, 请稍后重试: " + e.getClass().getSimpleName()));
        } catch (NonRetryableException e) {
            log.error("死信任务补跑失败(数据损坏类错误, 原死信审计记录未受影响): taskId={}, reason={}",
                    taskId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "补跑失败, 可修复后重试: " + e.getClass().getSimpleName()));
        } catch (Exception e) {
            log.error("死信任务补跑失败(原死信审计记录未受影响, 修复后可重试): taskId={}", taskId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "补跑失败, 可修复后重试: " + e.getClass().getSimpleName()));
        }
    }

    /**
     * 补跑成功指标隔离记录(10.5 review) — 指标异常只降级为告警,
     * 不让观测组件故障破坏已成功的补跑语义(观测是旁路, 不是主流程).
     */
    private void recordReplayedMetricSafely(String taskId) {
        try {
            taskMetricsOptional.ifPresent(metrics ->
                    metrics.recordRetry(TaskMetrics.RetryMetricOutcome.REPLAYED));
        } catch (RuntimeException e) {
            log.warn("补跑成功指标记录失败(已降级, 不影响补跑结果): taskId={}, reason={}",
                    taskId, e.getMessage());
        }
    }

    /** 常量时间字符串比较(防时序侧信道); 长度差异也会消耗同量比较. */
    private boolean constantTimeEquals(String provided, String expected) {
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
