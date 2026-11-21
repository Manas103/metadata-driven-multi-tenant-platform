# 1. the React console
FROM node:22-bookworm-slim AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# 2. the Spring Boot jar, with the console as static content
FROM maven:3.9-eclipse-temurin-21 AS app
WORKDIR /src
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src/ src/
COPY generated-clients/ generated-clients/
COPY --from=web /web/dist/ src/main/resources/static/
RUN mvn -B -q package -DskipTests && cp target/*.jar /app.jar

# 3. runtime: JRE only, non-root
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
COPY --from=app /app.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
