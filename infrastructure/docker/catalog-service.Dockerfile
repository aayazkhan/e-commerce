FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:catalog-service:installDist --no-daemon

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10004 commerce
WORKDIR /app
COPY --from=build /workspace/backend/catalog-service/build/install/catalog-service/ /app/
USER 10004
EXPOSE 8083
ENTRYPOINT ["/app/bin/catalog-service"]
