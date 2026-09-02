FROM gradle:8.14.3-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN gradle :backend:api-gateway:installDist --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 appuser
COPY --from=build /workspace/backend/api-gateway/build/install/api-gateway/ /app/
USER 10001
EXPOSE 8080
ENTRYPOINT ["/app/bin/api-gateway"]
