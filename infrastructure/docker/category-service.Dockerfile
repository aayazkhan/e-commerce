FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:category-service:installDist --no-daemon

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10003 commerce
WORKDIR /app
COPY --from=build /workspace/backend/category-service/build/install/category-service/ /app/
USER 10003
EXPOSE 8082
ENTRYPOINT ["/app/bin/category-service"]
