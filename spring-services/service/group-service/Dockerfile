# ========= BUILD STAGE =========
FROM maven:3.9-eclipse-temurin-25 AS builder

WORKDIR /build

# copy dependency trước để cache
COPY pom.xml .
RUN mvn -B -q -e -C dependency:go-offline

# copy source
COPY src ./src

# build jar
RUN mvn -B -q package -DskipTests

# extract layered jar (spring boot feature)
RUN java -Djarmode=layertools -jar target/*.jar extract


# ========= RUNTIME STAGE =========
FROM eclipse-temurin:25-jre-jammy

# security
RUN useradd --system spring

WORKDIR /app

# copy layers (cache friendly)
COPY --from=builder build/dependencies/ ./
COPY --from=builder build/spring-boot-loader/ ./
COPY --from=builder build/snapshot-dependencies/ ./
COPY --from=builder build/application/ ./

USER spring

EXPOSE 8087

# healthcheck
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD wget -qO- http://localhost:8087/actuator/health || exit 1

# JVM optimized for containers
ENTRYPOINT ["java", \
"-XX:+UseContainerSupport", \
"-XX:MaxRAMPercentage=75", \
"-XX:InitialRAMPercentage=50", \
"-XX:+ExitOnOutOfMemoryError", \
"-XX:+UseG1GC", \
"-XX:MaxGCPauseMillis=200", \
"-Djava.security.egd=file:/dev/./urandom", \
"org.springframework.boot.loader.launch.JarLauncher"]