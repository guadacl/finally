# syntax=docker/dockerfile:1

# ---- Stage 1: Angular build ----
FROM node:22-slim AS frontend
WORKDIR /frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
# Angular must output the browser bundle to dist/frontend/browser (see frontend/angular.json)
RUN npm run build

# ---- Stage 2: Maven build ----
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /src
COPY backend/ ./backend/
# the workflow module packages BPMN models from ../../camunda/bpmn
COPY camunda/bpmn/ ./camunda/bpmn/
COPY --from=frontend /frontend/dist/frontend/browser/ ./backend/app/src/main/resources/static/
WORKDIR /src/backend
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

# ---- Stage 3: runtime ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=backend /src/backend/app/target/finally-app.jar app.jar
EXPOSE 8000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
