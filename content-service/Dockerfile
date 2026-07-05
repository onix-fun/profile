FROM gradle:8.12-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src ./src
RUN gradle installDist --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/build/install/content-service /app
EXPOSE 8080 9091
ENTRYPOINT ["/app/bin/content-service"]
