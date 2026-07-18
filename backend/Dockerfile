FROM gradle:8.13-jdk21 AS build
WORKDIR /workspace/profile/backend
COPY profile/backend/ ./
COPY profile/api/ /workspace/profile/api/
COPY profile/.contracts/ /workspace/profile/.contracts/
RUN gradle --no-daemon :app:installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/profile/backend/app/build/install/profile/ /app/
EXPOSE 8080 9092
ENTRYPOINT ["/app/bin/profile"]
