FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:shipping-service:installDist --no-daemon
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/shipping-service/build/install/shipping-service/ .
USER 10001
EXPOSE 8093
ENTRYPOINT ["/app/bin/shipping-service"]
