# ---- Stage 1: build the jar ---------------------------------------------------
# The jar is the same for every CPU, so build it natively (no emulation), even
# when the final image targets another architecture.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
# Tests already ran in CI (they need Docker for Testcontainers, which isn't available here).
# The BuildKit cache mount keeps downloaded dependencies between local builds, and avoids the
# flaky "dependency:go-offline" step that downloaded far more than the build needs.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests

# ---- Stage 2: the runtime image -----------------------------------------------
# JRE only: no compiler, no Maven, no source code. Smaller and less to attack.
FROM eclipse-temurin:21-jre
WORKDIR /app

# Pebble (Canonical's service manager) ships in the base image, but this app never
# uses it, and it was built with a Go version that has known HIGH CVEs. Remove it.
# The user gets a fixed numeric ID so Kubernetes can verify "runAsNonRoot".
RUN rm -f /usr/bin/pebble \
 && groupadd --system --gid 10001 ledgerline \
 && useradd --system --uid 10001 --gid ledgerline ledgerline
COPY --from=build /workspace/target/*.jar app.jar
USER 10001:10001

# Docker Compose support is a local-development convenience; switch it off in the image.
ENV SPRING_DOCKER_COMPOSE_ENABLED=false
# Size the heap from the container's memory limit instead of the host's.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]