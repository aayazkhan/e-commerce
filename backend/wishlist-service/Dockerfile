FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:wishlist-service:installDist --no-daemon
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/wishlist-service/build/install/wishlist-service/ .
USER 10001
EXPOSE 8089
ENTRYPOINT ["/app/bin/wishlist-service"]
