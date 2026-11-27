# syntax=docker/dockerfile:1

FROM --platform=$BUILDPLATFORM maven:3.9.9-eclipse-temurin-17-noble AS build
WORKDIR /workspace

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
COPY HealthCheck.java .
RUN mvn -B package -DskipTests && javac --release 17 -d /workspace/healthcheck HealthCheck.java

FROM gcr.io/distroless/java17-debian12:nonroot
WORKDIR /app

LABEL org.opencontainers.image.title="livevote-votes-service" \
      org.opencontainers.image.description="LiveVote write service: identity, polls, and votes" \
      org.opencontainers.image.source="https://github.com/1W3-421343-Torres-Marcos/LiveVote"

COPY --from=build --chown=65532:65532 /workspace/target/votes-service-*.jar /app/app.jar
COPY --from=build --chown=65532:65532 /workspace/healthcheck/HealthCheck.class /app/HealthCheck.class

USER nonroot:nonroot
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
