FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:inventory-service:installDist --no-daemon
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/inventory-service/build/install/inventory-service/ .
USER 10001
EXPOSE 8087
ENTRYPOINT ["/app/bin/inventory-service"]
