FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:media-service:installDist --no-daemon

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10006 commerce
WORKDIR /app
COPY --from=build /workspace/backend/media-service/build/install/media-service/ /app/
USER 10006
EXPOSE 8085
ENTRYPOINT ["/app/bin/media-service"]
