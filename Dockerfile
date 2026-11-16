# Multi-stage Dockerfile for LogDate Server
# Optimized for both local development and Google Cloud Run production deployment

# Build stage
FROM mirror.gcr.io/library/gradle:8.10.2-jdk17@sha256:c2900027f3f0681c2cbfb09d527813851ad67aeafbb409997297efa2df20e748 AS build

WORKDIR /workspace
ENV GRADLE_USER_HOME=/home/gradle/.gradle

# Copy only the Gradle files needed for the server dependency graph. The
# normal repo settings pull the full monorepo into configuration, which makes
# container builds slower and invalidates cache layers more often than needed.
COPY --link gradle/wrapper/ gradle/wrapper/
COPY --link gradle/libs.versions.toml gradle/libs.versions.toml
COPY --link gradle/server-docker.settings.gradle.kts settings.gradle.kts
COPY --link gradle/server-docker.build.gradle.kts build.gradle.kts
COPY --link gradlew gradlew.bat gradle.properties ./
COPY --link build-logic/ build-logic/

# Copy only the modules required to build the server artifact.
COPY --link client/util/ client/util/
COPY --link shared/ shared/
# shared/lexicon generates its models from the lexicon schemas at build time.
COPY --link lexicons/ lexicons/
COPY --link server/ server/

# Build only the runnable server artifact plus the OpenAPI verification we need
# for deploy safety; skip packaging work that is irrelevant to the container.
RUN --mount=type=cache,target=/home/gradle/.gradle \
    ./gradlew :server:shadowJar :server:validateOpenApi -x test --no-daemon --build-cache

# Development stage (for docker-compose)
FROM eclipse-temurin:17-jre-jammy AS development

WORKDIR /app

# Install development tools
RUN apt-get update && apt-get install -y \
    curl \
    netcat-openbsd \
    && rm -rf /var/lib/apt/lists/*

# Copy built application
COPY --link --from=build /workspace/server/build/libs/logdate-server-all.jar logdate-server.jar

# Development environment variables
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"
ENV KTOR_DEVELOPMENT=true

# Health check for development
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

EXPOSE 8080

CMD ["java", "-jar", "logdate-server.jar"]

# Production stage (for Cloud Run)
FROM gcr.io/distroless/java17-debian12:nonroot AS production

WORKDIR /app

# Copy built application with locked-down permissions. 65532 is the distroless image's nonroot
# user and group; BuildKit cannot look names up for a --link copy, so they are given as numbers.
COPY --link \
    --chown=65532:65532 \
    --chmod=0444 \
    --from=build \
    /workspace/server/build/libs/logdate-server-all.jar /app/logdate-server.jar

# Production JVM optimizations for Cloud Run
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:G1HeapRegionSize=16m -XX:+UseStringDeduplication -Djava.security.egd=file:/dev/./urandom -Djava.io.tmpdir=/tmp"

# Cloud Run configuration
ENV PORT=8080
ENV HOST=0.0.0.0
ENV KTOR_DEVELOPMENT=false

EXPOSE 8080

# Use exec form for better signal handling
ENTRYPOINT ["java", "-jar", "/app/logdate-server.jar"]
