# 单机可观测性拓扑

应用继续由宿主机上的 Spring Boot 进程运行。独立的观测 Compose 栈只启动 Prometheus、Grafana 和 cAdvisor：Prometheus 通过 `aiaggregator-host:8080/actuator/prometheus` 抓取宿主应用，并经内部网络抓取 cAdvisor；Grafana 仅查询 Prometheus。现有业务 `docker-compose.yml` 不包含或依赖任何观测组件。

Story 10.3 新增的根目录 `docker-compose.prod.yml` 是生产应用的容器部署契约，不代表当前宿主 JVM 已迁入 Compose，也不改变仅供本地 Redis 使用的 `docker-compose.yml`。该生产契约显式启用 `prod`、把应用端口绑定到 loopback，并把宿主受控目录挂载到 `/app/logs/gc`；启动前必须设置可写的绝对目录，例如 `export GC_LOG_DIR=/var/log/ai-content-aggregator/gc`。

容器路径将应用端口绑定到 `127.0.0.1`，因此 Linux 上独立观测 Compose 不能再通过 `host-gateway` 直接抓取该端口；迁移为容器部署时，应通过同一受控 Docker 网络或经鉴权反代提供 Prometheus 抓取路径，不能静默复用当前宿主 JVM 的抓取配置。生产 Compose 同时设置 `mem_limit: 1g` 与 `cpus: 1.0`，让 `MaxRAMPercentage` 以容器配额为基准；上线前仍应按工作负载校准限额。

仍使用宿主 JVM 时，先以应用运行用户创建并验证日志目录权限，再把等价 Java 21 参数放入服务管理器的 JVM 参数中：

```bash
install -d -m 0750 -o <app-user> -g <app-group> /var/log/ai-content-aggregator/gc
java -XX:+UseG1GC -XX:MaxRAMPercentage=75.0 \
  '-Xlog:gc*,safepoint:file=/var/log/ai-content-aggregator/gc/gc.log:time,uptime,level,tags:filecount=5,filesize=20M' \
  -jar /opt/ai-content-aggregator/app.jar
```

2026-09-17 的 arm64 Docker spike 证实 Java `21.0.12+8` 能生成并轮转 GC/safepoint 文件，目标 JVM 保持 PID 1；`eclipse-temurin` JRE runtime 因缺少 `com.sun.tools.attach` 无法运行 Arthas 4.3.2，固定 digest 的 JDK runtime 则可由同容器、同 OS 用户成功 attach。生产镜像因此保留 JDK，但 Arthas 工具包仍只按需放入，不进入应用依赖、不常驻，也不发布 Telnet/Web Console 端口。

Story 10.1 的实际部署形态与原 AC3“应用容器资源指标”存在已批准的规格偏差：应用不是容器，而是宿主机 JVM 进程，因此应用自身资源压力以 Micrometer 的 `process_*`、`jvm_*` 指标为准；cAdvisor 只展示本观测栈的 Prometheus、Grafana、cAdvisor 三个容器，不宣称覆盖宿主应用容器。若未来把应用迁入容器，需要重新纳入该容器的 cAdvisor CPU/throttling/内存验收。

`aiaggregator-host` 由 Compose 的 `extra_hosts` 映射，默认使用 Docker `host-gateway`；也可通过 `AI_AGGREGATOR_HOST_GATEWAY` 指定宿主网关地址，因此版本库不硬编码开发机 IP。生产 profile 只暴露 `health`、`info`、`prometheus`，并禁用 `env`。Prometheus/Grafana 只绑定 `127.0.0.1`，cAdvisor 不发布端口。远程访问应通过已鉴权反向代理、VPN 或 SSH 隧道，不能把 Actuator exposure 当作鉴权。

## 版本与不可变镜像

- Prometheus `3.13.3`（3.13 LTS，支持期至 2027-07-31），distroless 多架构 index digest：`sha256:2e9a8ad75536755572d703e645fcc39c8104d9f0215d49d613db35194b0d8bc2`。
- Grafana OSS `13.2.1`，digest：`sha256:b7fcb534f7b3512801bb3f4e658238846435804deb479d105b5cdc680847c272`。
- cAdvisor `0.60.5`，digest：`sha256:763aecf1c32c2be8a1a75f9abfc2fc461005c9dbbaa39cb356b354aac1296dbe`。

版本核验日期为 2026-09-12。Prometheus 采用仍受支持的 3.13 LTS patch，未采用已结束支持的 3.5；三个镜像均同时固定 tag 与 digest。

## 启停与数据保留

启动前必须设置 Grafana 密码；Compose 使用 `${GRAFANA_ADMIN_PASSWORD:?...}` 在变量缺失时直接失败，不提供弱默认值：

```bash
export GRAFANA_ADMIN_PASSWORD='本机强密码'
docker compose -f ops/observability/compose.observability.yml up -d
docker compose -f ops/observability/compose.observability.yml ps
```

查看日志与停止观测栈：

```bash
docker compose -f ops/observability/compose.observability.yml logs --tail=100
docker compose -f ops/observability/compose.observability.yml down
curl -fsS http://127.0.0.1:8080/actuator/health
```

最后一个命令用于证明观测栈关闭后业务应用仍可独立工作。Prometheus 默认保留 15 天且最多使用 5GB，可由 `PROMETHEUS_RETENTION_TIME`、`PROMETHEUS_RETENTION_SIZE` 调整。普通 `down` 保留两个命名卷。只有明确确认无需历史数据时才执行以下破坏性清理，本工作流不会自动执行：

```bash
docker compose -f ops/observability/compose.observability.yml down --volumes
```

Linux Docker Engine 是 cAdvisor 的目标平台。Docker Desktop/macOS 可能缺少 `/dev/kmsg` 或完整的宿主 cgroup/块设备指标，此时 external smoke 应诚实跳过，不能把平台能力缺失解释成业务应用故障。

### cAdvisor 权限边界

cAdvisor 需要 `privileged: true`，并挂载宿主 `/`、`/var/run`、`/sys`、`/var/lib/docker`、`/dev/disk`。文件挂载虽标记为只读，但 `/var/run/docker.sock` 是 Unix socket，`:ro` 不能把 Docker API 变成只读；一旦 cAdvisor 容器被攻陷，该 socket 可能带来等同宿主 Docker 管理权限的攻击面。该服务不发布宿主端口、只加入内部 `observability` 网络，并固定镜像 digest；生产部署仍应限制镜像更新权限、主机访问和网络入口，且不得把 cAdvisor 端口暴露到公网。

## 告警处置

先在 `http://127.0.0.1:9090/alerts` 查看 firing/pending 状态与原始表达式，再在总览面板确认关联指标是否存在。应用 down 先检查宿主进程和 `/actuator/prometheus`；窗口/队列告警检查 scheduler 日志与 Redis；容器压力检查 cAdvisor target 和容器限制；成本告警遵循 Story 5.5 的预算恢复流程，不直接清除 Redis 标记。

两个 WindowMissed 告警表示相应调度窗口长期没有“执行并完整返回”，不表示窗口内每篇内容均业务成功。批量发布的文章级结果由 `aiaggregator_publish_batch_articles_total{outcome="success|failure"}` 独立累计；排查批量窗口时应同时查看该 Counter 的 failure 增量，避免把毒丸文章的持续失败误判为调度器漏跑。注意联动关系：成本达到 stop 阈值停机时，`processContent` 会在预算 gate 处直接跳过，不刷新最近成功时间，因此 `AiAggregatorContentWindowMissed` 会在约 25 小时后随之触发——处置时先看 `AiAggregatorMonthlyCostHalted`，窗口告警属于停机的预期伴生信号，不是独立的调度故障。

`AiAggregatorTaskOldestAgeHigh` 和 `AiAggregatorExternalDependencyConsecutiveFailures` 当前只具备规则与合成测试序列，生产信号分别由 Story 10.5 和 Story 10.3 提供。`AiAggregatorTaskProcessingStuck` 先以 processing 持续非空 30 分钟覆盖现有滞留风险；该阈值尚无生产任务时长测量依据，长批次可能误报，必须由 Story 10.5 根据任务年龄/retry/dead-letter 实测数据定标和细化。Story 10.3 还将增加慢操作计时，Story 10.12 增加媒体阶段指标；本 Story 不伪造这些领域数据。

`AiAggregatorTaskQueueSignalUnavailable` 同时校验队列值和 `aiaggregator_task_queue_collection_last_success_seconds`：即使 Redis 故障时队列 Gauge 保留 last-good 快照，只要连续超过 5 分钟未成功采集仍会进入告警。它与 `AiAggregatorCadvisorDown`、`AiAggregatorCostMetricsInvalid` 一起防止 NaN、缺失/陈旧序列、cAdvisor 停止或成本阈值畸形造成静默盲区。成本业务告警仅在 `0 < warning < stop` 且所需序列完整时判断，避免错误阈值触发虚假停机。

## Redis Slowlog（只读取证）

应用的 `aiaggregator.dependency.operation.duration` 记录端到端 Redis 调用，Slowlog 记录的是 Redis 服务器内命令执行时间；后者以微秒计、不包含网络 I/O、客户端排队或序列化，因此两者不能直接比较。常规排障先在每个目标节点只读查询：

```bash
redis-cli -h <redis-host> -p <port> CONFIG GET slowlog-log-slower-than
redis-cli -h <redis-host> -p <port> CONFIG GET slowlog-max-len
redis-cli -h <redis-host> -p <port> SLOWLOG LEN
redis-cli -h <redis-host> -p <port> SLOWLOG GET 10
```

Redis Cluster 必须对每个 master 分别执行以上查询。取证只记录时间戳、节点、耗时和脱敏后的命令类别；不要把 Slowlog 原始条目复制到工单或仓库，因为其中可能含 Redis key、参数或正文。默认不修改配置、不执行 `SLOWLOG RESET`。若确需临时调低阈值、调整长度或 reset，必须先取得授权、记录旧值，并在演练结束后恢复旧值。

## 受控 Arthas（按需、同 UID attach）

Arthas 不是应用依赖或常驻服务。使用固定的 `4.3.2` 包和 SHA-256 `f45cd49cb69488490736bcbf3f66842e1eb4b55c300a658d51cf45400311d56d`；升级时重新核验版本、校验和与命令。生产容器使用 JDK runtime，进入目标容器后必须以与 Java 进程相同的 OS 用户确认 PID 和健康状态，诊断产物仅写入受控、权限受限的临时目录。

```bash
# 先记录 health/负载快照、目标 PID 与执行人；不开放 3658/8563 或 Tunnel
curl -fsS http://127.0.0.1:8080/actuator/health
ps -o user,pid,cmd -C java
sha256sum arthas-packaging-4.3.2-bin.zip
# 应用镜像会注入 GC 日志参数；Arthas 自身启动时显式关闭继承，避免诊断进程写入应用 GC 目录。
JAVA_TOOL_OPTIONS='-Xlog:disable' java -jar arthas-boot.jar <java-pid>

# 由轻到重：只读概览
dashboard
jvm
thread -n 3

# 精确类/方法、附加条件，最多采样 5 次；完成后 reset
trace com.example.ExactClient exactMethod '#cost>100' -n 5
reset com.example.ExactClient

# 单次不超过 30 秒，输出至受控目录；完成后 stop
profiler start
profiler stop --file /tmp/arthas/profile-<timestamp>.html
quit
```

`watch`、`trace`、`monitor` 会增强字节码，必须限定类、方法、条件和次数，并在结束后 `reset`/`stop`。出现健康检查失败、任务错误增长、持续高 CPU 或命令无法停止时立即中止。禁止 Tunnel、公开 Web Console/Telnet、无限制采样、`heapdump`、`vmtool`、`retransform` 等高影响命令；如确需这些能力，另行授权。归档时仅保留时间窗、PID/用户、执行命令、产物受控路径和停止记录，不保存密钥、响应正文、堆转储或用户数据。

## 配置与告警验证

提交前执行静态配置检查和告警单元测试。`promtool test rules` 的合成序列覆盖应用 down、两个计划窗口的缺失/NaN/陈旧分支、队列积压/processing 滞留/最老任务、队列信号缺失/NaN/陈旧、cAdvisor down、容器内存/CPU、外部依赖连续失败、成本序列/阈值异常（含 warning/stop 标签变体）以及 warning/halted（含 usage 从 10% 直达 100%）的 firing 与 recovered 状态：

```bash
docker compose -f ops/observability/compose.observability.yml config --quiet
promtool check config --syntax-only ops/observability/prometheus.yml
promtool check rules ops/observability/alerts.yml
cd ops/observability/tests && promtool test rules alerts.test.yml
```

可选的真实栈只读冒烟测试不会代替上述确定性检查，也不会自行启停容器。仅在 Linux Docker Engine、宿主应用和观测栈均已就绪时运行：

```bash
OBSERVABILITY_SMOKE=true ./mvnw -Pexternal-tests -Dtest=ObservabilityStackSmokeTest test
```

可通过 `PROMETHEUS_URL`、`GRAFANA_URL` 覆盖默认 loopback 地址。Docker Desktop/macOS 缺少完整 cAdvisor 宿主能力时应将该测试标记为平台受限并跳过。
