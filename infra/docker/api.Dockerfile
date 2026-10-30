# syntax=docker/dockerfile:1
# Saathi API — Java 21 / Spring Boot 4.1.x (System Design §2.1, D11/D12).
#
# Two stages: Maven builds, a JRE-only alpine runtime carries the result. No JDK,
# no Maven and no sources end up in the shipped image. The runtime runs as uid
# 10001, not root.
#
#   docker build -f infra/docker/api.Dockerfile --target runtime -t saathi-api:dev .
#
# BuildKit cache mounts keep ~/.m2 warm between rebuilds, so editing one Java
# file does not re-resolve the dependency tree.

# ─── build ────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolved first, and only invalidated when the POM changes.
COPY services/api/pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -q dependency:go-offline

COPY services/api ./
# Tests run in CI against a real PostgreSQL, not here: an image build must not
# be the only place a suite executes, and `-DskipTests` keeps this stage honest
# about what it is for.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package

# ─── runtime ──────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Timezone matters: business_day, quote expiry-on-read and audit timestamps are
# all evaluated in Asia/Kolkata (PRD §8, System Design §14).
RUN apk add --no-cache tzdata \
    && addgroup -g 10001 -S saathi \
    && adduser -u 10001 -S -G saathi -h /app saathi

WORKDIR /app
COPY --from=build --chown=saathi:saathi /build/target/saathi-api.jar ./app.jar

USER saathi
EXPOSE 8080

# Heap follows the container limit instead of assuming the host's RAM.
# Exec form keeps java as PID 1, so SIGTERM reaches the JVM and Spring Boot's
# graceful shutdown (server.shutdown=graceful) can drain in-flight requests.
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/urandom", \
  "-jar", "/app/app.jar"]

STOPSIGNAL SIGTERM

HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=6 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health/readiness >/dev/null || exit 1
