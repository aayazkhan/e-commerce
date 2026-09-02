FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:seller-service:installDist --no-daemon
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/seller-service/build/install/seller-service/ .
USER 10001
EXPOSE 8100
ENTRYPOINT ["/app/bin/seller-service"]
