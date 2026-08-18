package com.choucj.aiaggregator.task.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 手动任务触发控制器 - 用于手动执行定时任务.
 *
 * <p>提供 REST 端点 {@code POST /api/tasks/trigger} 来手动触发内容处理任务，
 * 方便开发和测试阶段随时执行任务，无需等待 cron 定时或重启应用.
 *
 * <p><b>使用场景:</b>
 * <ul>
 *   <li>开发测试：需要立即验证代码修改效果</li>
 *   <li>数据补抓：需要手动补充抓取某些账号的内容</li>
 *   <li>问题排查：需要手动触发来调试任务执行问题</li>
 * </ul>
 *
 * <p><b>安全考虑:</b> 此端点仅用于开发环境，生产环境应通过 Spring Security 或其他机制保护.
 *
 * @see ContentScheduler
 */
@Slf4j
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskTriggerController {

    private final ContentScheduler contentScheduler;

    /**
     * 手动触发内容处理任务.
     *
     * <p>调用 {@link ContentScheduler#processContent()} 执行完整的内容处理流程，
     * 包括 Twitter 账号抓取、内容筛选、改写、归档和发布等步骤.
     *
     * @return 包含执行状态的响应
     */
    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> triggerTask() {
        log.info("手动触发内容处理任务");
        long startTime = System.currentTimeMillis();

        try {
            // 调用 ContentScheduler 的处理方法
            contentScheduler.processContent();

            long duration = System.currentTimeMillis() - startTime;
            log.info("手动触发任务完成: 耗时={}ms", duration);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "任务执行完成",
                "durationMs", duration,
                "timestamp", System.currentTimeMillis()
            ));

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("手动触发任务失败: 耗时={}ms", duration, e);

            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", "任务执行失败: " + e.getMessage(),
                "durationMs", duration,
                "timestamp", System.currentTimeMillis()
            ));
        }
    }

    /**
     * 健康检查端点 - 用于验证任务触发器是否可用.
     *
     * @return 健康状态
     */
    @PostMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "schedulerAvailable", contentScheduler != null,
            "timestamp", System.currentTimeMillis()
        ));
    }
}
