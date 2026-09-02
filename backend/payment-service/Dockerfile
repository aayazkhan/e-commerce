FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:payment-service:installDist --no-daemon
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/payment-service/build/install/payment-service/ .
USER 10001
EXPOSE 8092
ENTRYPOINT ["/app/bin/payment-service"]
