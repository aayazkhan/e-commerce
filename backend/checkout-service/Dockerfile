FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:checkout-service:installDist --no-daemon
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/checkout-service/build/install/checkout-service/ .
USER 10001
EXPOSE 8095
ENTRYPOINT ["/app/bin/checkout-service"]
