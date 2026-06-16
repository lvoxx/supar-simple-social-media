# syntax=docker/dockerfile:1
#
# Build context = THIS service directory (standalone Spring Initializr project):
#   docker build -t media-service apps/java/media-service
#
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src ./src
RUN ./mvnw -B -q clean package -DskipTests

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
RUN useradd -r -u 1001 appuser
COPY --from=build /app/target/*.jar app.jar
USER appuser
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
