# 部署与运维指南

> 本文档承接 architecture.md 中剥离的运维 HOW（命令、构建、部署）。
> 架构决策（WHY/WHAT）见 `_bmad-output/planning-artifacts/architecture.md`。

## 开发服务器

```bash
# 启动 Redis Cluster
docker-compose up -d redis-node-1 redis-node-2 redis-node-3
./setup-cluster.sh

# 启动应用（开发模式）
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 运行测试
mvn test

# 构建镜像
docker-compose build
```

## 构建过程

```bash
# Maven 构建流程
mvn clean package          # 编译 + 打包
  → target/*.jar           # 可执行 JAR

# Docker 构建流程
docker-compose build       # 多阶段构建
  → 构建阶段：Maven 编译
  → 运行阶段：JRE + Python + twscrape
```

## 生产环境部署

```bash
# 生产环境部署
docker-compose up -d       # 启动所有服务
  ├── Redis Cluster（3 主节点）
  └── 应用容器（自动重启）

# 数据持久化
  ├── Redis 数据：docker volumes
  ├── Markdown 归档：archive/ 目录
  └── 输出文件：output/ 目录
```

## 项目初始化（已完成，留作记录）

```bash
# 第一步：初始化项目
curl https://start.spring.io/starter.zip \
  -d type=maven-project \
  -d groupId=com.choucj \
  -d artifactId=ai-content-aggregator \
  -d name=ai-content-aggregator \
  -d description="AI Content Aggregation and Auto-Publishing System" \
  -d bootVersion=3.5.14 \
  -d javaVersion=21 \
  -d dependencies=web,data-redis,lombok,configuration-processor,actuator \
  -d packageName=com.choucj.aiaggregator \
  -o ai-content-aggregator.zip
```

_最后更新: 2026-07-07 (从 architecture.md 抽离, retro-3 C1)_
