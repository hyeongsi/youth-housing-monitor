# syntax=docker/dockerfile:1

FROM node:22-alpine AS web-build

WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /workspace
COPY spring-app/ spring-app/

RUN chmod +x spring-app/gradlew \
    && spring-app/gradlew -p spring-app bootJar --no-daemon

FROM eclipse-temurin:21-jre-jammy

RUN groupadd --system housing \
    && useradd --system --gid housing --home-dir /app --create-home housing

WORKDIR /app

COPY --from=build --chown=housing:housing /workspace/spring-app/build/libs/*.jar /app/app.jar
COPY --from=web-build --chown=housing:housing /web/dist/ /app/public/
COPY --chown=housing:housing data/ /app/data/

ENV SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=4173 \
    HOUSING_ROOT=/app \
    STATIC_LOCATIONS=file:/app/public/

VOLUME ["/app/data"]
EXPOSE 4173

USER housing
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
