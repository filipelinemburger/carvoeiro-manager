# syntax=docker/dockerfile:1

# --- build stage: compile the Spring Boot fat JAR with the Gradle wrapper ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Copy build inputs first so the Gradle wrapper/distribution layer caches
# independently of source changes.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle build.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon --version

# Now the sources. bootJar does NOT run tests (which need Testcontainers/Docker).
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar \
    && find build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} /app/app.jar \;

# --- runtime stage: slim JRE, non-root ---
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
RUN useradd -r -u 1001 appuser
COPY --from=build /app/app.jar app.jar
EXPOSE 8088
USER appuser
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
