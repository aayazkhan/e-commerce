FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:order-service:installDist --no-daemon
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/order-service/build/install/order-service/ .
USER 10001
EXPOSE 8091
ENTRYPOINT ["/app/bin/order-service"]
