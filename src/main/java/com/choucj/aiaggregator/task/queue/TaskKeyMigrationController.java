package com.choucj.aiaggregator.task.queue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 受控的一次性任务键迁移入口(Story 10.4 AC1/任务 4.3)。
 *
 * <p><b>受控边界(双层):</b>
 * <ol>
 *   <li><b>启用开关:</b> 默认关闭, 仅当 {@code task.migration.enabled=true} 时本 Bean 才注册
 *       ({@code @ConditionalOnProperty(havingValue="true")} 无 {@code matchIfMissing})。</li>
 *   <li><b>共享密钥鉴权(10.4 review 修复 — 开关只解决"默认不暴露", 不解决"暴露后谁能调用",
 *       破坏性运维操作必须验证调用方身份):</b> 请求须携带 {@code X-Migration-Token} 请求头,
 *       与 {@code task.migration.token} 配置比对(常量时间比较防时序侧信道)。
 *       令牌<b>未配置或为空时拒绝一切请求</b> — 防止"开关开启但未配令牌"的裸奔形态;
 *       不匹配返回 403。令牌来自环境变量注入(gotcha: 不得写入提交版配置文件)。</li>
 * </ol>
 *
 * <p><b>冲突语义(10.4 review 修复):</b> 迁移摘要中存在 state 冲突({@code conflictCount>0})
 * 时返回 HTTP 409 — 冲突意味着迁移未完成, 响应必须忠实反映完成度, 否则运维会据
 * "success=true" 误删旧键。body 仍携带完整摘要供核对。
 *
 * <p><b>为什么不用启动自动迁移:</b> 与启动恢复(recovery)存在时序竞争, 且违反 A7
 * (启动流程单编排器原则)。
 *
 * <p><b>迁移 runbook(运维操作说明, 不写在业务代码内):</b>
 * <ol>
 *   <li>暂停旧版写入和新版入队/消费, 备份并核对旧/新键数量及 ID 冲突;
 *       经环境变量注入 {@code TASK_MIGRATION_TOKEN} 并设 {@code task.migration.enabled=true}</li>
 *   <li>携带 {@code X-Migration-Token} 请求头调用 {@code POST /api/tasks/migration};
 *       端口只绑内网/loopback 或经实际鉴权保护 — <b>不得</b>复用无鉴权的
 *       {@code TaskTriggerController} 暴露生产迁移</li>
 *   <li>核对审计日志与 {@code task:{queue}:legacy-migrated} 账本、抽样状态;
 *       响应为 409(conflictCount/invalidCount&gt;0)时先核对冲突条目, 不得进入删除旧键步骤</li>
 *   <li>启动新版, 确认 {@code schedule.run-on-startup} 配置与恢复计划匹配; 若为 false,
 *       须通过受控的 {@code TaskQueue} 高层恢复命令处理迁入的旧 processing</li>
 *   <li>观察一个调度周期并确认无遗漏后由运维手工删除旧键; 删除前保留迁移账本,
 *       整个过程禁止旧新双写; 完成后关闭开关并清除令牌</li>
 * </ol>
 *
 * <p>引用源: Story 10.4 创建(2026-09-20);review 修复: 令牌鉴权 + 冲突 409(2026-09-20)。
 */
@RestController
@RequestMapping("/api/tasks")
@Slf4j
@ConditionalOnProperty(prefix = "task.migration", name = "enabled", havingValue = "true")
public class TaskKeyMigrationController {

    /** 迁移访问令牌请求头名称. */
    static final String TOKEN_HEADER = "X-Migration-Token";

    private final TaskKeyMigrator taskKeyMigrator;
    private final String configuredToken;

    /**
     * 手写构造器(项目规则: 含 {@code @Value} 默认值的注入不能用 {@code @RequiredArgsConstructor}).
     *
     * @param taskKeyMigrator 迁移编排器
     * @param configuredToken 共享密钥({@code task.migration.token}; 为空表示未配置, 拒绝一切请求)
     */
    public TaskKeyMigrationController(TaskKeyMigrator taskKeyMigrator,
                                      @Value("${task.migration.token:}") String configuredToken) {
        this.taskKeyMigrator = taskKeyMigrator;
        this.configuredToken = configuredToken;
    }

    /**
     * 执行一次性任务键迁移.
     *
     * @param token 请求头 {@code X-Migration-Token} 携带的访问令牌
     * @return 200 迁移完成摘要 / 403 令牌缺失或不匹配 / 409 存在冲突或无效条目(迁移未完成,
     *         需运维核对后重跑) / 500 迁移异常(账本保证幂等可重跑)
     */
    @PostMapping("/migration")
    public ResponseEntity<Map<String, Object>> migrate(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        if (configuredToken == null || configuredToken.isBlank()) {
            log.error("迁移入口已启用但未配置访问令牌(task.migration.token), 拒绝执行");
            return ResponseEntity.status(403).body(Map.of(
                    "success", false,
                    "message", "迁移入口未配置访问令牌, 拒绝执行"));
        }
        if (token == null || !constantTimeEquals(token, configuredToken)) {
            log.error("迁移请求令牌校验失败, 拒绝执行");
            return ResponseEntity.status(403).body(Map.of(
                    "success", false,
                    "message", "迁移访问令牌无效"));
        }
        log.info("受控入口触发任务键迁移");
        try {
            TaskKeyMigrator.MigrationSummary summary = taskKeyMigrator.migrate();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("legacyPendingCount", summary.legacyPendingCount());
            body.put("legacyProcessingCount", summary.legacyProcessingCount());
            body.put("migratedCount", summary.migratedCount());
            body.put("skippedCount", summary.skippedCount());
            body.put("conflictCount", summary.conflictCount());
            body.put("invalidCount", summary.invalidCount());
            body.put("elapsedMs", summary.elapsedMs());
            if (summary.conflictCount() > 0 || summary.invalidCount() > 0) {
                // 冲突/无效条目 = 迁移未完成; success=false 防止运维据"成功"信号误删旧键
                body.put("success", false);
                body.put("message", "迁移存在冲突或无效条目, 未全部完成, 请核对后处理并重跑(账本幂等)");
                log.warn("任务键迁移存在未完成条目: conflicts={}, invalid={}",
                        summary.conflictCount(), summary.invalidCount());
                return ResponseEntity.status(409).body(body);
            }
            body.put("success", true);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.error("任务键迁移失败(可修复后重跑, 账本保证幂等)", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "任务键迁移失败, 修复后可重跑(账本幂等): " + e.getClass().getSimpleName()));
        }
    }

    /** 常量时间字符串比较(防时序侧信道); 长度差异也会消耗同量比较. */
    private boolean constantTimeEquals(String provided, String expected) {
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
