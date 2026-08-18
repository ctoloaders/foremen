# Implementation Plan: Gradle Project Initialization (FOR-01-01-gradle)

## Overview

Initialize the Foremen backend as a Gradle project in `foremen-backend/` subdirectory of the monorepo. This establishes the build system, dependencies, application entry point, profile-based configuration, Docker Compose for local PostgreSQL, and Liquibase integration. All files are created from scratch since this is a greenfield project setup.

## Tasks

- [x] 1. Initialize Gradle wrapper and project skeleton
  - [x] 1.1 Generate Gradle wrapper files (v9.1.0)
    - Create `foremen-backend/gradle/wrapper/gradle-wrapper.properties` pinning `distributionUrl` to Gradle 9.1.0
    - Create `foremen-backend/gradle/wrapper/gradle-wrapper.jar` (binary, use `gradle wrapper` command)
    - Create `foremen-backend/gradlew` (Unix) and `foremen-backend/gradlew.bat` (Windows) scripts
    - _Requirements: 8.1, 8.2, 8.3, 11.1, 11.2_

  - [x] 1.2 Create `settings.gradle` with plugin management
    - Set `rootProject.name = 'foremen-backend'`
    - Configure `pluginManagement` block with `mavenCentral()` and Spring milestone repository (`https://repo.spring.io/milestone`)
    - _Requirements: 1.4, 11.2_

  - [x] 1.3 Create `gradle.properties` with version variables
    - Define `mapstructVersion=1.6.3`
    - Define any other externalized dependency versions (jqwik, springdoc)
    - _Requirements: 1.5_

- [x] 2. Configure build.gradle with plugins and dependencies
  - [x] 2.1 Create `build.gradle` with plugin declarations
    - Apply `java` plugin
    - Apply `org.springframework.boot` plugin (version 4.x latest stable)
    - Apply `io.spring.dependency-management` plugin
    - Apply `io.freefair.lombok` plugin (version 9.1.0)
    - Apply `org.liquibase.gradle` plugin (version 3.1.0)
    - Set `group = 'com.foremen'` and `version = '0.0.1-SNAPSHOT'`
    - Set `sourceCompatibility` and `targetCompatibility` to Java 25
    - Include `wrapper` task definition specifying Gradle version 9.1.0
    - _Requirements: 1.1, 1.2, 1.3, 2.1, 2.2, 2.3, 2.4, 8.4, 9.1_

  - [x] 2.2 Add all dependencies to `build.gradle`
    - `implementation`: spring-boot-starter-web, spring-boot-starter-data-jpa, spring-boot-starter-validation, spring-boot-starter-security, spring-boot-starter-actuator, spring-boot-starter-cache, spring-boot-starter-aop
    - `implementation`: org.mapstruct:mapstruct (version from gradle.properties), org.springdoc:springdoc-openapi-starter-webmvc-ui (2.x), com.github.ben-manes.caffeine:caffeine, org.liquibase:liquibase-core
    - `runtimeOnly`: org.postgresql:postgresql
    - `annotationProcessor`: org.mapstruct:mapstruct-processor (version from gradle.properties)
    - `testImplementation`: spring-boot-starter-test, net.jqwik:jqwik (1.9.x)
    - `liquibaseRuntime`: liquibase-core, liquibase-groovy-dsl, PostgreSQL driver
    - Configure `test` task to use JUnit Platform
    - _Requirements: 3.1–3.16, 9.2_

  - [x] 2.3 Configure annotation processors and compiler arguments
    - Ensure Lombok annotation processor is declared before MapStruct processor in `annotationProcessor` configuration
    - Add compiler arguments for MapStruct: `-Amapstruct.defaultComponentModel=spring`
    - _Requirements: 4.1, 4.2, 4.3_

  - [x] 2.4 Configure Liquibase plugin block and processResources
    - Add `liquibase` DSL block with `main` activity pointing to `./database_files/changelog.xml` and local PostgreSQL URL
    - Extend `processResources` task to copy `database_files/` directory into classpath
    - _Requirements: 9.2, 9.3, 9.4_

- [x] 3. Checkpoint
  - Ensure `./gradlew build` compiles successfully (without running application). Ask the user if questions arise.

- [x] 4. Create application entry point and source structure
  - [x] 4.1 Create `ForemenApplication.java`
    - Create directory structure `foremen-backend/src/main/java/com/foremen/`
    - Create `ForemenApplication.java` with `@SpringBootApplication` annotation
    - Implement `main` method calling `SpringApplication.run(ForemenApplication.class, args)`
    - _Requirements: 5.1, 5.2, 5.3, 11.3_

  - [x] 4.2 Create profile-based YAML configuration files
    - Create `src/main/resources/application.yml` with base config: server.port=8080, spring.profiles.active=local, liquibase changelog path, springdoc api-docs path
    - Create `src/main/resources/application-local.yml` with datasource config for localhost:5432/foremen
    - Create `src/main/resources/application-dev.yml` with placeholder dev database URL
    - Create `src/main/resources/application-prod.yml` with placeholder production settings
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7_

- [x] 5. Set up Docker Compose and Liquibase files
  - [x] 5.1 Create `docker-compose.yml` for local PostgreSQL
    - Define `postgres` service using `postgres:16` image
    - Map port 5432:5432
    - Set environment: POSTGRES_DB=foremen, POSTGRES_USER=foremen, POSTGRES_PASSWORD=foremen
    - Define named volume for persistent storage
    - Add healthcheck using `pg_isready`
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 11.4_

  - [x] 5.2 Create Liquibase database_files directory and initial changelog
    - Create `foremen-backend/database_files/` directory
    - Create empty `changelog.xml` as initial placeholder with proper Liquibase XML header
    - _Requirements: 9.3, 11.5_

- [x] 6. Update .gitignore and add local config template
  - [x] 6.1 Extend root `.gitignore` with backend-specific exclusions
    - Add Gradle exclusions: `foremen-backend/build/`, `foremen-backend/.gradle/`
    - Add Java artifact exclusions: `*.class`, `*.jar` with negation for `gradle-wrapper.jar`
    - Add IDE exclusions: `.idea/`, `*.iml`
    - Add local secrets exclusion: `foremen-backend/src/main/resources/application-local.yml`
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5_

  - [x] 6.2 Create `application-local.yml.example` template
    - Create `foremen-backend/src/main/resources/application-local.yml.example` with documented placeholder values
    - _Requirements: 10.6_

- [x] 7. Create initial test file
  - [x] 7.1 Create `ForemenApplicationTests.java`
    - Create directory structure `foremen-backend/src/test/java/com/foremen/`
    - Create `ForemenApplicationTests.java` with `@SpringBootTest` annotation
    - Add `contextLoads()` test method verifying application context starts
    - _Requirements: 3.14, 5.4, 11.3_

- [x] 8. Final checkpoint
  - Ensure all tests pass (`./gradlew test`), verify `./gradlew build` succeeds. Ask the user if questions arise.

## Notes

- All files are created inside `foremen-backend/` subdirectory of the monorepo root
- The Gradle wrapper can be bootstrapped by running `gradle wrapper --gradle-version 9.1.0` from the `foremen-backend/` directory (requires Gradle installed locally) or by copying wrapper files from a known-good source
- Docker must be running for `bootRun` to succeed (PostgreSQL dependency)
- The `application-local.yml` is git-ignored; developers copy from `.example` file
- No property-based tests are included because this is infrastructure/configuration setup with no testable business logic

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "1.3"] },
    { "id": 2, "tasks": ["2.1"] },
    { "id": 3, "tasks": ["2.2", "2.3", "2.4"] },
    { "id": 4, "tasks": ["4.1", "4.2", "5.1", "5.2"] },
    { "id": 5, "tasks": ["6.1", "6.2", "7.1"] }
  ]
}
```
