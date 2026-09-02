FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:cart-service:installDist --no-daemon
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/backend/cart-service/build/install/cart-service/ .
USER 10001
EXPOSE 8088
ENTRYPOINT ["/app/bin/cart-service"]
