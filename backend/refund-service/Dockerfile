FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:refund-service:installDist --no-daemon
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/refund-service/build/install/refund-service/ .
USER 10001
EXPOSE 8094
ENTRYPOINT ["/app/bin/refund-service"]
