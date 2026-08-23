# Stage 1: Build — use official Gradle image so the distribution is pre-installed
FROM gradle:9.1.0-jdk25 AS build

WORKDIR /app

# Copy build config (dependencies layer)
COPY settings.gradle build.gradle gradle.properties ./

# Download dependencies (cacheable layer — Gradle itself is already in the image)
RUN gradle dependencies --no-daemon

# Copy source
COPY src/ src/
COPY database_files/ database_files/

# Build fat jar
RUN gradle bootJar --no-daemon -x test

# Stage 2: Run
FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
