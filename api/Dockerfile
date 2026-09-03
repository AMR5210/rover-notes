# Build and run the Spring service.
#
# Two stages: the JDK and the Gradle cache stay in the builder, and the runtime image
# carries a JRE and one jar. Dependency resolution is a separate layer from the source
# copy, so a code change rebuilds without re-downloading the dependency graph.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# Resolves the dependency graph on its own layer. --no-daemon because the daemon
# outlives the RUN step and holds memory the build does not get back.
RUN ./gradlew --no-daemon dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true

COPY src src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# An unprivileged user. Container Apps does not require one, and a process that never
# needs to write outside /tmp should not run as root regardless.
RUN groupadd --system rover && useradd --system --gid rover --home /app rover
COPY --from=build --chown=rover:rover /build/build/libs/*.jar app.jar
USER rover

EXPOSE 8080

# Container-aware heap sizing. Left to the default the JVM reads the host's memory on
# some runtimes and sizes a heap the container limit will not allow.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=40"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
