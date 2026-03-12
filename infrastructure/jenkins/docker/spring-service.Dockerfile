# syntax=docker/dockerfile:1
# Dockerfile — Spring Boot service (multi-stage, extracted layered JAR)
# Place this file at: spring-services/<service-name>/Dockerfile
#
#  ────────────────────────────────────────────────────────────────────────────────────────────────
# | ONLY USED FOR REFERENCE BUILDING IN JENKINS;                                                   |
# | NOT USED BY JENKINS PIPELINE (which builds from repo root with contextPath set to service dir) |
#  ────────────────────────────────────────────────────────────────────────────────────────────────
#
# Build args:
#   SERVICE_NAME  — used for OCI labels
#   IMAGE_TAG     — used for OCI labels
#   BUILD_DATE    — ISO 8601 timestamp
#   VCS_REF       — git commit SHA

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-25 AS builder

WORKDIR /workspace

# Copy the full Maven multi-module project from the repo root.
# Jenkins agent runs in the repo root; contextPath is the service module dir
# but the parent pom is needed.
COPY pom.xml ./
COPY spring-services/common ./spring-services/common
# Copy the specific service module (contextPath passed at build time)
COPY . .

RUN mvn dependency:go-offline -B --no-transfer-progress -q
RUN mvn package -DskipTests --batch-mode --no-transfer-progress

# Extract layered JAR for efficient Docker layer caching
RUN java -Djarmode=layertools \
    -jar target/*.jar \
    extract --destination /workspace/extracted

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine AS runtime

# Security: run as non-root
RUN addgroup --system --gid 1001 sssm \
    && adduser  --system --uid 1001 --ingroup sssm --no-create-home appuser

WORKDIR /app

# Copy extracted layers (least→most frequently changed for max cache reuse)
COPY --from=builder /workspace/extracted/dependencies          ./
COPY --from=builder /workspace/extracted/spring-boot-loader   ./
COPY --from=builder /workspace/extracted/snapshot-dependencies ./
COPY --from=builder /workspace/extracted/application          ./

# Kubernetes-friendly JVM defaults
ENV JAVA_OPTS="\
    -XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -XX:+UseG1GC \
    -XX:+ExitOnOutOfMemoryError \
    -Djava.security.egd=file:/dev/./urandom \
    -Dspring.backgroundpreinitializer.ignore=true"

# OCI labels
ARG BUILD_DATE
ARG VCS_REF
ARG SERVICE_NAME
ARG IMAGE_TAG
LABEL org.opencontainers.image.created="${BUILD_DATE}"   \
    org.opencontainers.image.revision="${VCS_REF}"     \
    org.opencontainers.image.title="${SERVICE_NAME}"   \
    org.opencontainers.image.version="${IMAGE_TAG}"    \
    maintainer="platform-team@sssm.com"

USER appuser

# Spring Boot layered JAR entry point
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]

# Expose the service port (overridden per service; default shown here)
EXPOSE 8080

# Liveness / readiness are checked by K8S probes on /actuator/health
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
