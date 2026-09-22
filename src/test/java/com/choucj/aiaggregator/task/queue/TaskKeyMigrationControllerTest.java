package com.choucj.aiaggregator.task.queue;

import com.choucj.aiaggregator.task.queue.TaskKeyMigrator.MigrationSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Story 10.4 review 修复 — {@link TaskKeyMigrationController} 受控边界单测:
 * 令牌鉴权矩阵(未配置全拒/错误 403/正确 200)与冲突语义(409 忠实反映未完成, 防误删旧键).
 */
@ExtendWith(MockitoExtension.class)
class TaskKeyMigrationControllerTest {

    private static final String TOKEN = "ops-secret-token";

    @Mock
    private TaskKeyMigrator taskKeyMigrator;

    private TaskKeyMigrationController controller;

    @BeforeEach
    void setUp() {
        controller = new TaskKeyMigrationController(taskKeyMigrator, TOKEN);
    }

    @Test
    void should_reject_all_requests_when_token_not_configured() {
        // 开关开启但未配令牌 = 裸奔形态, 必须拒绝一切请求(即使请求头碰巧为空)
        TaskKeyMigrationController unconfigured =
                new TaskKeyMigrationController(taskKeyMigrator, "");

        ResponseEntity<Map<String, Object>> response = unconfigured.migrate("anything");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("success", false);
        verifyNoInteractions(taskKeyMigrator);
    }

    @Test
    void should_return_403_when_token_missing() {
        ResponseEntity<Map<String, Object>> response = controller.migrate(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(taskKeyMigrator);
    }

    @Test
    void should_return_403_when_token_mismatched() {
        ResponseEntity<Map<String, Object>> response = controller.migrate("wrong-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("success", false);
        verifyNoInteractions(taskKeyMigrator);
    }

    @Test
    void should_return_200_with_summary_when_token_valid_and_no_conflicts() {
        when(taskKeyMigrator.migrate()).thenReturn(new MigrationSummary(2, 1, 3, 0, 0, 0, 15L));

        ResponseEntity<Map<String, Object>> response = controller.migrate(TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("success", true)
                .containsEntry("migratedCount", 3)
                .containsEntry("conflictCount", 0)
                .containsEntry("invalidCount", 0);
    }

    @Test
    void should_return_409_when_migration_has_state_conflicts() {
        // 冲突 = 迁移未完成; success=false 防止运维据"成功"信号误删旧键
        when(taskKeyMigrator.migrate()).thenReturn(new MigrationSummary(1, 1, 1, 0, 1, 0, 12L));

        ResponseEntity<Map<String, Object>> response = controller.migrate(TOKEN);

        assertThat(response.getStatusCode())
                .as("存在冲突时响应状态必须与完成度一致(409), 不得返回成功")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("success", false)
                .containsEntry("conflictCount", 1);
    }

    @Test
    void should_return_409_when_migration_has_invalid_entries() {
        when(taskKeyMigrator.migrate()).thenReturn(new MigrationSummary(1, 0, 0, 0, 0, 1, 8L));

        ResponseEntity<Map<String, Object>> response = controller.migrate(TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("success", false)
                .containsEntry("invalidCount", 1);
    }

    @Test
    void should_return_500_when_migration_throws() {
        when(taskKeyMigrator.migrate()).thenThrow(new IllegalStateException("redis down"));

        ResponseEntity<Map<String, Object>> response = controller.migrate(TOKEN);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("success", false);
        verify(taskKeyMigrator).migrate();
    }
}
