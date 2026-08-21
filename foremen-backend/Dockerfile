# Stage 1: Build
FROM eclipse-temurin:25-jdk AS build

WORKDIR /app

# Copy Gradle wrapper and config
COPY gradlew gradlew.bat settings.gradle build.gradle gradle.properties ./
COPY gradle/ gradle/

# Download dependencies (cacheable layer)
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

# Copy source
COPY src/ src/
COPY database_files/ database_files/

# Build fat jar
RUN ./gradlew bootJar --no-daemon -x test

# Stage 2: Run
FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
