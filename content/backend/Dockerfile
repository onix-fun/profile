FROM eclipse-temurin:21-jre
WORKDIR /app
COPY content/backend/build/install/content-service /app
EXPOSE 8080 9091
ENTRYPOINT ["/app/bin/content-service"]
