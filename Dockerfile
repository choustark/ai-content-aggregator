# 多阶段构建 — Story 2.2b 引入 twscrape CLI 依赖 Python 运行时.
# 构建阶段: 用 Maven 镜像编译打包, 产物为 spring-boot fat jar.
# 运行阶段: eclipse-temurin:21-jre-alpine 基础上 apk 装 python3 + pip 装 twscrape.

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
COPY pom.xml .
# 预下载依赖, 利用 Docker 层缓存(后续代码变更不重复下载)
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre-alpine
# python3 / py3-pip: twscrape CLI 运行所需.
# tzdata: 容器时区(默认 UTC), 调度器 cron 需本地时区时设 TZ=Asia/Shanghai.
RUN apk add --no-cache python3 py3-pip tzdata ca-certificates && \
    pip3 install --no-cache-dir --break-system-packages twscrape && \
    twscrape --version
WORKDIR /app
COPY --from=builder /build/target/ai-content-aggregator-*.jar app.jar
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0"
ENV TZ=Asia/Shanghai
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
