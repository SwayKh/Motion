# syntax=docker/dockerfile:1

FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
# Build context is the repository root so web/ and backend-java/ are both available.
COPY backend-java/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend-java/src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl ffmpeg \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --uid 10001 --shell /usr/sbin/nologin motion
WORKDIR /app
COPY --from=build /workspace/target/motion-java-*.jar /app/app.jar
# The Java server serves the existing frontend directly; no frontend source is modified.
COPY web /app/web
RUN mkdir -p /app/data /app/data/tmp /app/data/previews /app/uploads /app/outputs \
    && chown -R motion:motion /app

USER motion
ENV PORT=8080 \
    APP_ENV=production \
    SPRING_PROFILES_ACTIVE=production \
    SECURE_COOKIES=true \
    MOTION_DATA_DIR=/app/data \
    MOTION_UPLOAD_DIR=/app/uploads \
    MOTION_OUTPUT_DIR=/app/outputs \
    MOTION_STATIC_DIR=/app/web

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=30s \
    CMD curl -fsS http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
