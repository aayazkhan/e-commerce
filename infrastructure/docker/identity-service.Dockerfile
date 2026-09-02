FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN gradle :backend:identity-service:installDist --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10002 appuser
COPY --from=build /workspace/backend/identity-service/build/install/identity-service/ /app/
USER 10002
EXPOSE 8081
ENTRYPOINT ["/app/bin/identity-service"]
