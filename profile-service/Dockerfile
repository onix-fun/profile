FROM eclipse-temurin:21-jre
WORKDIR /app
COPY profile-service/build/install/profile-service /app
EXPOSE 8080
ENTRYPOINT ["/app/bin/profile-service"]
