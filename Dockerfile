# 多阶段构建：Maven 构建（阿里云镜像加速）→ JRE 运行（约 200MB）
# 构建：docker build -t sentry-agent:latest .
# 运行：见 docker-compose.yml（推荐）
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml settings.xml ./
COPY src ./src
# settings.xml 的 localRepository=./m2repo 是本机隔离设计；容器内回到默认 ~/.m2
RUN sed -i 's|<localRepository>./m2repo</localRepository>|<localRepository>/root/.m2/repository</localRepository>|' settings.xml \
    && mvn -s settings.xml clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# 非 root：容器被攻破不等于宿主机沦陷
RUN addgroup -S app && adduser -S app -G app
COPY --from=builder /build/target/sentry-agent-*.jar app.jar
USER app
EXPOSE 8084
# 健康检查：编排器据此判断是否拉起（Phase 4 落库后 DB 不通会启动失败，此处自然失败）
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8084/actuator/health || exit 1
# 内存自适应容器限制（不写死 -Xmx）；OutOfMemoryError 直接退出让编排器重建
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", \
            "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
