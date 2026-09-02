FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:notification-service:installDist --no-daemon
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/notification-service/build/install/notification-service/ .
USER 10001
EXPOSE 8096
ENTRYPOINT ["/app/bin/notification-service"]
