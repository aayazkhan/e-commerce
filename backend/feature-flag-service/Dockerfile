FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:feature-flag-service:installDist --no-daemon
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/feature-flag-service/build/install/feature-flag-service/ .
USER 10001
EXPOSE 8103
ENTRYPOINT ["/app/bin/feature-flag-service"]
