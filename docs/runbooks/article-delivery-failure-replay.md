# 文章交付失败核对与补跑 Runbook

适用于 `ArticleStatus=DELIVERY_FAILED` 的必需 PHOTO/VIDEO 下载失败。所有操作均保留原失败快照和 dead-letter 审计；禁止使用 `redis-cli` 直接修改 `task:*`、`article:*` 或媒体状态键。

## 1. 定位权威记录

1. 从结构化日志取得 `articleId`、`tweetId` 和 `failureTaskId`，不要复制正文或凭据。
2. 读取 `{archive.base-directory}/articles/{articleId}.json`，确认：
   - `status=DELIVERY_FAILED`
   - `failureStage=MEDIA_DOWNLOAD`
   - `failureTaskId=delivery:{articleId}`
   - `article` 仍完整保存。
3. 从 `{archive.base-directory}/media/twitter/.canonical/{tweetId}.date` 读取首次归档日期，再检查该日期目录下唯一的 `{tweetId}/media.json`。不得按补跑当天新建 sidecar。
4. 通过应用状态查询接口或只读运维工具核对 Redis `article:{articleId}:status=DELIVERY_FAILED`，并确认 `failureTaskId` 位于 TaskQueue dead-letter。任一层不一致时先重放高层失败协调命令，不得手工修键。

## 2. 修复媒体源

修复不可访问 URL、权限或源文件后，再提交补跑。GIF 为 `DEFERRED`，不通过此流程自动下载；UNKNOWN 必须先由人工确认类型，不能静默降级发布。

## 3. 受控补跑

补跑功能默认关闭。启用 `task.replay.enabled=true`，并通过密钥存储提供 `task.replay.token`。调用：

```bash
curl -X POST \
  -H 'X-Replay-Token: <token>' \
  -H 'X-Replay-Request-Id: <stable-request-id>' \
  'http://127.0.0.1:<port>/api/articles/<articleId>/delivery/replay'
```

同一 `X-Replay-Request-Id` 只允许产生同一个确定性关联任务。默认复用文章快照中的 Article，不再次调用 LLM；Story 10.7 不提供自动重生成入口。若确需重新生成，必须走独立人工变更并留下审计记录。

## 4. 验收

- 原 dead-letter 保持不变；文章快照状态可在受控补跑中从 `DELIVERY_FAILED` 推进，
  但原 `failureStage`、`failureCode`、`failureSummary`、`failedAt`、`failureTaskId` 必须完整保留且不可覆盖。
- 新任务包含 `replayedFrom`，并继续使用 canonical sidecar。
- 任一必需媒体三阶段未全部 `SUCCEEDED` 前，文章不得进入 `PENDING_PUBLISH`，微信 `addDraft` 调用次数应为 0。
- 补跑失败时根据返回状态处理：非死信或状态不一致先核对四层记录；Redis 不可用时停止操作，恢复后使用相同 request id 重试。
