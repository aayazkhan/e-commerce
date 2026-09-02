FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:recommendation-service:installDist --no-daemon
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/recommendation-service/build/install/recommendation-service/ .
USER 10001
EXPOSE 8098
ENTRYPOINT ["/app/bin/recommendation-service"]
