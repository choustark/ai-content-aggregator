# AI Content Aggregator - 本地启动指南

## 📋 前置基础条件

### 必需环境
- **JDK 21** - 项目使用Java 21（`<java.version>21</java.version>`）
- **Maven 3.6+** - 项目使用Maven构建
- **Redis Server** - 项目依赖Redis（功能开关控制）

### 可选环境
- **IDE** - IntelliJ IDEA（推荐）或Eclipse
- **Git** - 版本控制

## 🔧 环境准备检查

### 1. 验证Java版本
```bash
java -version
# 应显示：openjdk version "21.x.x" 或类似
```

### 2. 验证Maven版本  
```bash
mvn -version
# 应显示：Apache Maven 3.6.x 或更高
```

### 3. 检查Redis状态
```bash
# 检查Redis是否运行
redis-cli ping
# 应返回：PONG
```

**如果没有Redis，可以启动Docker Redis：**
```bash
docker run -d -p 6379:6379 redis:latest
```

## 📁 项目配置准备

### 1. 配置文件说明
- `application.yml` - 主配置（已包含Epic 7配置）
- `application-dev.yml` - 开发环境配置
- `application-test.yml` - 测试环境配置  
- `api-keys.yml` - API密钥配置（需要单独设置）

### 2. 关键配置检查

#### Redis配置（必需）
检查`application-dev.yml`中的Redis配置：
```yaml
spring:
  data:
    redis:
      host: localhost  # 默认localhost:6379
      port: 6379
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
mvn clean

# 编译项目（跳过测试）
mvn compile -DskipTests

# 或者完整编译并测试
mvn clean install
```

#### 2. 启动应用
```bash
# 使用dev配置启动
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 或使用主类直接启动
mvn exec:java -Dexec.mainClass="com.choucj.aiaggregator.AiContentAggregatorApplication"
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
  - **Name**: `AiContentAggregator (dev)`
  - **Main class**: `com.choucj.aiaggregator.AiContentAggregatorApplication`
  - **Active profiles**: `dev`
  - **VM options**: `-Dspring.profiles.active=dev`

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

### 3. 运行测试（可选）
```bash
# 运行所有测试
mvn test

# 运行特定测试类
mvn test -Dtest=TweetMediaArchiverTest

# Epic 7功能测试
mvn test -Dtest=*TweetMedia*Test
```

## ⚠️ 常见启动问题排查

### 问题1：Redis连接失败
**症状：** `RedisConnectionException: Unable to connect to Redis`  
**解决：** 
- 检查Redis是否运行：`redis-cli ping`
- 检查配置：`application-dev.yml`中的Redis地址端口
- 启动Docker Redis：`docker run -d -p 6379:6379 redis:latest`

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
- 清理Maven缓存：`mvn dependency:purge-local-repository`
- 重新编译：`mvn clean install`

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
mvn test -Dtest=TweetMediaArchiveWriterTest

# 测试图片下载功能  
mvn test -Dtest=TweetMediaArchiverTest

# 测试publishability gate
mvn test -Dtest=TweetPublishabilityGateTest

# 测试Redis状态恢复
mvn test -Dtest=MediaRuntimeRecoveryServiceTest
```

#### 3. 完整回归测试
```bash
# 运行全部测试（预期916 tests，0 failures）
mvn test
```

## 🎯 开发模式建议

### 推荐的开发配置
- 使用`application-dev.yml`作为active profile
- 启用Spring Boot DevTools（如需要热重载）
- 配置合适的日志级别

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
**文档版本**: v1.0  
**最后更新**: 2024-08-15  
**适用项目**: ai-content-aggregator (Epic 7完成后)