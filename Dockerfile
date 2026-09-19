# 多阶段构建 — Story 2.2b 引入 twscrape CLI 依赖 Python 运行时.
# 构建阶段: 用 Maven 镜像编译打包, 产物为 spring-boot fat jar.
# 运行阶段: 使用 Java 21 JDK 以保留受控 attach 能力,并 apk 装 python3 + pip 装 twscrape.

# ---------- 构建阶段 ----------
FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS builder
WORKDIR /build
COPY pom.xml .
# 预下载依赖, 利用 Docker 层缓存(后续代码变更不重复下载)
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21.0.12_8-jdk-alpine-3.24@sha256:6ea5548706b60ac0a602eaf48af74792cbab012d90e811ca8db6184b16b5c3d6
# python3 / py3-pip: twscrape CLI 运行所需.
# tzdata: 容器时区(默认 UTC), 调度器 cron 需本地时区时设 TZ=Asia/Shanghai.
# apk 包版本随 digest 固定的 alpine 3.24 仓库解析(数据类包 tzdata/ca-certificates 接受补丁漂移);
# twscrape 是应用级运行时依赖, 必须钉死版本 — 上游发不兼容版本时重建镜像才不会静默破坏抓取链路.
RUN apk add --no-cache python3 py3-pip tzdata ca-certificates && \
    pip3 install --no-cache-dir --break-system-packages twscrape==0.20.1 && \
    twscrape --version
WORKDIR /app
COPY --from=builder /build/target/ai-content-aggregator-*.jar app.jar
RUN mkdir -p /app/logs/gc
# 基础诊断参数由 JVM 自动读取；JAVA_OPTS 仅供运维追加，避免覆盖 GC 契约。
ENV JAVA_TOOL_OPTIONS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -Xlog:gc*,safepoint:file=/app/logs/gc/gc.log:time,uptime,level,tags:filecount=5,filesize=20M"
ENV JAVA_OPTS=""
ENV TZ=Asia/Shanghai
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
