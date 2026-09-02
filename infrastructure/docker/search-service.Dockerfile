FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:search-service:installDist --no-daemon

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10007 commerce
WORKDIR /app
COPY --from=build /workspace/backend/search-service/build/install/search-service/ /app/
USER 10007
EXPOSE 8086
ENTRYPOINT ["/app/bin/search-service"]
