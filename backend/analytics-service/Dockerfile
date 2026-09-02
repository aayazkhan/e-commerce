FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:analytics-service:installDist --no-daemon
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/analytics-service/build/install/analytics-service/ .
USER 10001
EXPOSE 8099
ENTRYPOINT ["/app/bin/analytics-service"]
