# ---- Stage 1: build the jar ---------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy only the build files first. Docker caches this layer, so dependencies are
# downloaded again only when pom.xml changes, not on every code change.
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src src
# Tests already ran in CI (they need Docker for Testcontainers, which isn't available here).
RUN ./mvnw -B -q package -DskipTests

# ---- Stage 2: the runtime image -----------------------------------------------
# JRE only: no compiler, no Maven, no source code. Smaller and less to attack.
FROM eclipse-temurin:21-jre
WORKDIR /app

# Never run as root inside the container.
#RUN groupadd --system ledgerline && useradd --system --gid ledgerline ledgerline
# Pebble (Canonical's service manager) ships in the base image, but this app never
# uses it, and it was built with a Go version that has known HIGH CVEs. Remove it.
RUN rm -f /usr/bin/pebble \
 && groupadd --system ledgerline && useradd --system --gid ledgerline ledgerline
COPY --from=build /workspace/target/*.jar app.jar
USER ledgerline

# Docker Compose support is a local-development convenience; switch it off in the image.
ENV SPRING_DOCKER_COMPOSE_ENABLED=false
# Size the heap from the container's memory limit instead of the host's.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]