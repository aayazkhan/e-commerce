FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:review-service:installDist --no-daemon
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/review-service/build/install/review-service/ .
USER 10001
EXPOSE 8097
ENTRYPOINT ["/app/bin/review-service"]
