FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:promotion-service:installDist --no-daemon
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/promotion-service/build/install/promotion-service/ .
USER 10001
EXPOSE 8090
ENTRYPOINT ["/app/bin/promotion-service"]
