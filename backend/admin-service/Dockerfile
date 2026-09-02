FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:admin-service:installDist --no-daemon
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/admin-service/build/install/admin-service/ .
USER 10001
EXPOSE 8104
ENTRYPOINT ["/app/bin/admin-service"]
