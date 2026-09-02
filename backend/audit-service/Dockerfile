FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:audit-service:installDist --no-daemon
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/audit-service/build/install/audit-service/ .
USER 10001
EXPOSE 8102
ENTRYPOINT ["/app/bin/audit-service"]
