FROM gradle:8.14.4-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :backend:pricing-service:installDist --no-daemon

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10005 commerce
WORKDIR /app
COPY --from=build /workspace/backend/pricing-service/build/install/pricing-service/ /app/
USER 10005
EXPOSE 8084
ENTRYPOINT ["/app/bin/pricing-service"]
