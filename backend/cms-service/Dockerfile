FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:cms-service:installDist --no-daemon
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/cms-service/build/install/cms-service/ .
USER 10001
EXPOSE 8101
ENTRYPOINT ["/app/bin/cms-service"]
