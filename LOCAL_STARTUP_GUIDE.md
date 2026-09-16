# AI Content Aggregator - 本地启动指南

## 📋 前置基础条件

### 必需环境
- **JDK 21** - 项目使用Java 21（`<java.version>21</java.version>`）
- **Maven Wrapper** - 项目使用仓库内的 `./mvnw` 构建
- **Redis Cluster** - 默认本地配置连接 `localhost:7001`、`7002`、`7003`

### 可选环境
- **IDE** - IntelliJ IDEA（推荐）或Eclipse
- **Git** - 版本控制

## 🔧 环境准备检查

### 1. 验证Java版本
```bash
java -version
# 应显示：openjdk version "21.x.x" 或类似
```

### 2. 验证 Maven Wrapper
```bash
./mvnw -version
```

### 3. 检查Redis状态
```bash
# 检查Redis是否运行
redis-cli -c -p 7001 ping
# 应返回：PONG
```

## 📁 项目配置准备

### 1. 配置文件说明
- `src/main/resources/application.yml` - 本地主配置，不提交 Git；首次使用从 `application.example.yml` 复制
- `src/main/resources/application-prod.yml` - 生产环境覆盖配置
- `src/test/resources/application.yml` - 自动化测试专用配置，不打包到生产 JAR
- `src/main/resources/api-keys.yml` - API 密钥配置，不提交 Git；首次使用从 `api-keys.example.yml` 复制
- `feature-flags.yml`、`resilience.yml` - 提交到 Git 的公共功能开关与韧性参数

### 2. 关键配置检查

#### Redis配置（必需）
检查 `application.yml` 中两组 Redis Cluster 节点配置：
```yaml
spring:
  data:
    redis:
      cluster:
        nodes:
          - localhost:7001
          - localhost:7002
          - localhost:7003

redis:
  cluster:
    nodes:
      - { host: localhost, port: 7001 }
      - { host: localhost, port: 7002 }
      - { host: localhost, port: 7003 }
```

#### Epic 7功能开关（当前关闭）
```yaml
twitter:
  media:
    enabled: false  # Epic 7媒体归档功能，默认关闭
    base-directory: ./archive  # 媒体归档基础目录
```

#### LLM配置（如需使用AI功能）
检查`api-keys.yml`，确保LLM提供商API密钥已配置。

## 🚀 启动步骤

### 方式一：命令行启动（推荐测试）

#### 1. 清理并编译
```bash
# 进入项目目录
cd /Users/chou/work_space/ai-content-aggregator

# 清理旧的编译
./mvnw clean

# 编译项目（跳过测试）
./mvnw compile -DskipTests

# 或者完整编译并测试
./mvnw clean install
```

#### 2. 启动应用
```bash
# 默认使用 application.yml 启动
./mvnw spring-boot:run
```

#### 3. 验证启动成功
启动成功后，控制台应显示：
```
Started AiContentAggregatorApplication in X.xx seconds
JVM running for ...
```

访问Actuator端点验证：
```bash
curl http://localhost:8080/actuator/health
# 应返回：{"status":"UP"}
```

### 方式二：IntelliJ IDEA启动

#### 1. 导入项目
- 打开IntelliJ IDEA
- File → Open → 选择项目根目录`/Users/chou/work_space/ai-content-aggregator`
- 等待Maven依赖下载完成

#### 2. 配置运行
- Run → Edit Configurations
- 点击 `+` → 选择 `Spring Boot`
- 配置如下：
  - **Name**: `AiContentAggregator`
  - **Main class**: `com.choucj.aiaggregator.AiContentAggregatorApplication`
  - **Active profiles**: 留空（`application.yml` 的默认 profile 为 `dev`）

#### 3. 运行
- 点击绿色运行按钮或按 `Shift+F10`
- 首次运行会下载Maven依赖，可能需要几分钟

## 🔍 启动后验证

### 1. 健康检查
```bash
curl http://localhost:8080/actuator/health
```

### 2. 查看日志
启动日志应显示：
- ✅ Redis连接成功
- ✅ 各模块初始化完成
- ✅ 无ERROR级别日志

本地日志同时保存在 `logs/ai-content-aggregator.log`。日志按日期或单文件达到 20 MB 时滚动，压缩保留 14 天，总量上限 1 GB；应用连续运行数天也可回溯排查。

### 3. 运行测试（可选）
```bash
# 运行所有测试
./mvnw test

# 运行特定测试类
./mvnw test -Dtest=TweetMediaArchiverTest

# Epic 7功能测试
./mvnw test -Dtest='*TweetMedia*Test'
```

## ⚠️ 常见启动问题排查

### 问题1：Redis连接失败
**症状：** `RedisConnectionException: Unable to connect to Redis`  
**解决：** 
- 检查 Redis Cluster 是否运行：`redis-cli -c -p 7001 ping`
- 检查 `application.yml` 中两组 Redis Cluster 节点是否都指向实际地址
- 默认本地端口为 `7001`、`7002`、`7003`，单节点 `localhost:6379` 不能替代集群

### 问题2：端口被占用
**症状：** `Web server failed to start. Port 8080 was already in use`  
**解决：**
- 检查端口占用：`lsof -i :8080`
- 修改配置文件中的端口：`server.port=8081`
- 或终止占用进程

### 问题3：Maven依赖下载失败
**症状：** `Could not resolve dependencies`  
**解决：**
- 检查网络连接
- 清理Maven缓存：`./mvnw dependency:purge-local-repository`
- 重新编译：`./mvnw clean install`

### 问题4：Java版本不匹配
**症状：** `Unsupported class file major version 65`  
**解决：**
- 确认JAVA_HOME指向JDK 21
- 验证版本：`java -version`
- IDEA设置：File → Project Structure → Project SDK → 选择JDK 21

## 📊 启动后的功能验证

### 验证Epic 7基础设施（可选）

#### 1. 测试媒体归档配置
```bash
# 检查配置是否加载
curl http://localhost:8080/actuator/configprops | grep twitter.media
```

#### 2. 运行Epic 7相关测试
```bash
# 测试媒体归档Writer
./mvnw test -Dtest=TweetMediaArchiveWriterTest

# 测试图片下载功能  
./mvnw test -Dtest=TweetMediaArchiverTest

# 测试publishability gate
./mvnw test -Dtest=TweetPublishabilityGateTest

# 测试Redis状态恢复
./mvnw test -Dtest=MediaRuntimeRecoveryServiceTest
```

#### 3. 完整回归测试
```bash
# 运行全部测试（当前基线 1171 tests，0 failures / 0 errors）
./mvnw clean test
```

## 🎯 开发模式建议

### 推荐的开发配置
- 默认直接使用 `application.yml`；不再维护 `application-dev.yml`
- 启用Spring Boot DevTools（如需要热重载）
- 在 `application.yml` 中配置日志级别、持久化和轮转策略

### IDEA开发优化
- 启用`Build project automatically`
- 配置代码格式化和检查
- 使用Spring Boot Dashboard监控应用状态

## 📝 下一步操作

启动成功后，你可以：

1. **Epic 8端到端验证** - 验证Epic 7 → Epic 8的完整链路
2. **功能开发** - 开始Epic 8的stories开发
3. **调试现有功能** - 使用Actuator端点和日志排查问题

## 🆘 需要帮助？

如果遇到启动问题：
1. 检查上述常见问题排查部分
2. 查看项目日志：控制台输出或日志文件
3. 确认环境版本是否符合前置条件要求

---
**文档版本**: v1.1
**最后更新**: 2026-09-13
**适用项目**: ai-content-aggregator
