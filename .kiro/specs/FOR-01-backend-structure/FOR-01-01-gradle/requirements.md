# Requirements Document

## Introduction

This spec defines the Gradle project initialization for the Foremen backend application — a renovation management system. The backend project resides in the `foremen-backend/` subdirectory of the monorepo (root: `/Users/alexander.borohov/projects/loaders/foremen/`). The git repository is shared across all subprojects (backend, frontend, docs, etc.). It covers the build configuration (Groovy DSL), dependency declarations, application entry point, profile-based configuration, Docker Compose for local PostgreSQL, and Gradle wrapper setup. This is the foundational layer upon which all subsequent FOR-01 specs depend.

## Glossary

- **Build_System**: The Gradle build tool configured via Groovy DSL (`build.gradle`) that manages compilation, dependency resolution, and task execution for the Foremen project.
- **Application**: The Spring Boot 4 application entry point (`ForemenApplication.java`) in package `com.foremen`.
- **Dependency_Manager**: The Spring Dependency Management plugin (`io.spring.dependency-management`) that controls transitive dependency versions aligned with Spring Boot 4.
- **Profile_System**: The Spring Boot profile mechanism (`application.yml` / `application-{profile}.yml`) that switches configuration between local, dev, and prod environments.
- **Docker_Compose**: The `docker-compose.yml` file providing a containerized PostgreSQL instance for local development.
- **Gradle_Wrapper**: The `gradlew` / `gradlew.bat` scripts and `gradle/wrapper/` directory that pin the Gradle version for reproducible builds.
- **Annotation_Processor**: The compile-time code generation toolchain (Lombok + MapStruct) configured in `build.gradle` via `annotationProcessor` dependencies.
- **Monorepo**: The shared git repository at the workspace root (`/Users/alexander.borohov/projects/loaders/foremen/`) containing all subprojects: `foremen-backend/`, `телеграм-бот/`, `docs/`, `mockups/`, and `.kiro/specs/`.

## Requirements

### Requirement 1: Gradle Project Structure

**User Story:** As a developer, I want a properly initialized Gradle project with Groovy DSL, so that I can build, test, and run the Foremen application with a single command.

#### Acceptance Criteria

1. THE Build_System SHALL use Groovy DSL (`build.gradle`) consistent with the tickets project convention.
2. THE Build_System SHALL declare `group = 'com.foremen'` and `version = '0.0.1-SNAPSHOT'`.
3. THE Build_System SHALL set `sourceCompatibility` and `targetCompatibility` to Java 25.
4. THE Build_System SHALL include a `settings.gradle` file that sets `rootProject.name = 'foremen-backend'` and configures `pluginManagement` with `mavenCentral()` and the Spring milestone repository.
5. THE Build_System SHALL include a `gradle.properties` file defining version variables for MapStruct and any other externalized dependency versions.

### Requirement 2: Spring Boot 4 Plugin Configuration

**User Story:** As a developer, I want Spring Boot 4 configured as the application framework, so that the project benefits from auto-configuration, embedded server, and the jakarta.* namespace.

#### Acceptance Criteria

1. THE Build_System SHALL apply the `org.springframework.boot` plugin at version 4.x (latest stable).
2. THE Build_System SHALL apply the `io.spring.dependency-management` plugin to manage transitive dependencies.
3. THE Build_System SHALL apply the `java` plugin.
4. THE Build_System SHALL apply the `io.freefair.lombok` plugin for Lombok integration.

### Requirement 3: Core Dependencies

**User Story:** As a developer, I want all necessary Spring Boot starters and libraries declared, so that the project has web, JPA, validation, security, actuator, cache, and AOP capabilities from the start.

#### Acceptance Criteria

1. THE Build_System SHALL declare `spring-boot-starter-web` as an implementation dependency.
2. THE Build_System SHALL declare `spring-boot-starter-data-jpa` as an implementation dependency.
3. THE Build_System SHALL declare `spring-boot-starter-validation` as an implementation dependency.
4. THE Build_System SHALL declare `spring-boot-starter-security` as an implementation dependency.
5. THE Build_System SHALL declare `spring-boot-starter-actuator` as an implementation dependency.
6. THE Build_System SHALL declare `spring-boot-starter-cache` as an implementation dependency.
7. THE Build_System SHALL declare `spring-boot-starter-aop` as an implementation dependency.
8. THE Build_System SHALL declare the PostgreSQL JDBC driver (`org.postgresql:postgresql`) as a runtime dependency.
9. THE Build_System SHALL declare `org.liquibase:liquibase-core` as an implementation dependency.
10. THE Build_System SHALL declare `org.mapstruct:mapstruct` as an implementation dependency using the version from `gradle.properties`.
11. THE Build_System SHALL declare `org.mapstruct:mapstruct-processor` as an annotation processor dependency using the version from `gradle.properties`.
12. THE Build_System SHALL declare `org.springdoc:springdoc-openapi-starter-webmvc-ui` (version 2.x) as an implementation dependency for API documentation.
13. THE Build_System SHALL declare `com.github.ben-manes.caffeine:caffeine` as an implementation dependency for caching.
14. THE Build_System SHALL declare `spring-boot-starter-test` as a test implementation dependency.
15. THE Build_System SHALL declare `net.jqwik:jqwik` (version 1.9.x) as a test implementation dependency for property-based testing.
16. THE Build_System SHALL configure the `test` task to use the JUnit Platform.

### Requirement 4: Annotation Processor Configuration

**User Story:** As a developer, I want Lombok and MapStruct annotation processors correctly ordered, so that generated code (getters, builders, mappers) compiles without conflicts.

#### Acceptance Criteria

1. THE Build_System SHALL declare the Lombok annotation processor before the MapStruct annotation processor in the `annotationProcessor` configuration.
2. THE Build_System SHALL configure MapStruct to use the `componentModel = "spring"` default via compiler arguments.
3. WHEN the project is compiled, THE Annotation_Processor SHALL generate MapStruct mapper implementations that inject via Spring dependency injection.

### Requirement 5: Application Entry Point

**User Story:** As a developer, I want a Spring Boot main class, so that the application can start via `./gradlew bootRun` or as a packaged JAR.

#### Acceptance Criteria

1. THE Application SHALL reside in the package `com.foremen` in file `src/main/java/com/foremen/ForemenApplication.java`.
2. THE Application SHALL be annotated with `@SpringBootApplication`.
3. THE Application SHALL contain a `main` method that calls `SpringApplication.run(ForemenApplication.class, args)`.
4. WHEN `./gradlew bootRun` is executed, THE Application SHALL start successfully without runtime errors (assuming a running PostgreSQL instance).

### Requirement 6: Profile-Based Configuration

**User Story:** As a developer, I want environment-specific configuration files, so that I can run the application locally, in dev, and in production with different database URLs and settings.

#### Acceptance Criteria

1. THE Profile_System SHALL include an `application.yml` base configuration file in `src/main/resources/`.
2. THE Profile_System SHALL include profile-specific files: `application-local.yml`, `application-dev.yml`, and `application-prod.yml`.
3. THE Profile_System SHALL set the default active profile to `local` in the base `application.yml`.
4. THE Profile_System SHALL configure the datasource in `application-local.yml` to connect to PostgreSQL at `localhost:5432` with database name `foremen`.
5. THE Profile_System SHALL configure Liquibase changelog path as `classpath:database_files/changelog.xml` in the base configuration.
6. THE Profile_System SHALL configure the server port to `8080` in the base configuration.
7. THE Profile_System SHALL configure SpringDoc OpenAPI path as `/api-docs` in the base configuration.

### Requirement 7: Docker Compose for Local Development

**User Story:** As a developer, I want a Docker Compose file, so that I can start a local PostgreSQL database with a single `docker compose up` command.

#### Acceptance Criteria

1. THE Docker_Compose SHALL define a PostgreSQL 16 service named `postgres`.
2. THE Docker_Compose SHALL expose port `5432` on the host mapped to container port `5432`.
3. THE Docker_Compose SHALL set environment variables `POSTGRES_DB=foremen`, `POSTGRES_USER=foremen`, and `POSTGRES_PASSWORD=foremen` for local development.
4. THE Docker_Compose SHALL define a named volume for persistent database storage.
5. THE Docker_Compose SHALL include a healthcheck for the PostgreSQL service.

### Requirement 8: Gradle Wrapper Configuration

**User Story:** As a developer, I want a pinned Gradle wrapper version, so that all team members use the same build tool version regardless of their local Gradle installation.

#### Acceptance Criteria

1. THE Gradle_Wrapper SHALL pin the Gradle version to 8.14 or the latest stable release compatible with Spring Boot 4 and Java 25.
2. THE Gradle_Wrapper SHALL include `foremen-backend/gradlew` (Unix) and `foremen-backend/gradlew.bat` (Windows) scripts.
3. THE Gradle_Wrapper SHALL include `foremen-backend/gradle/wrapper/gradle-wrapper.jar` and `foremen-backend/gradle/wrapper/gradle-wrapper.properties`.
4. THE Build_System SHALL include a `wrapper` task definition specifying the target Gradle version.

### Requirement 9: Liquibase Integration

**User Story:** As a developer, I want Liquibase configured in the build, so that database migrations run automatically on application start and can be triggered via Gradle tasks.

#### Acceptance Criteria

1. THE Build_System SHALL apply the `org.liquibase.gradle` plugin.
2. THE Build_System SHALL declare `liquibaseRuntime` dependencies for `liquibase-core`, `liquibase-groovy-dsl`, and the PostgreSQL driver.
3. THE Build_System SHALL configure the `liquibase` block with a `main` activity pointing to `./database_files/changelog.xml` and a default local PostgreSQL URL.
4. THE Build_System SHALL include a `processResources` task that copies the `database_files/` directory into the classpath.

### Requirement 10: .gitignore Updates

**User Story:** As a developer, I want the existing root `.gitignore` extended with Gradle/Java patterns, so that backend build artifacts are not committed to the shared repository.

#### Acceptance Criteria

1. THE project SHALL use the existing `.gitignore` file at the repository root (not create a separate one in `foremen-backend/`).
2. THE `.gitignore` SHALL be extended with Gradle-specific exclusions: `foremen-backend/build/`, `foremen-backend/.gradle/`.
3. THE `.gitignore` SHALL be extended with Java artifact exclusions: `*.class`, `*.jar` (with exception for `gradle-wrapper.jar`).
4. THE `.gitignore` SHALL be extended with IDE exclusions for Java: `.idea/`, `*.iml`.
5. THE `.gitignore` SHALL exclude `foremen-backend/src/main/resources/application-local.yml` from version control.
6. IF `application-local.yml` is excluded, THEN THE project SHALL include `foremen-backend/src/main/resources/application-local.yml.example` as a template.

### Requirement 11: Monorepo Project Location

**User Story:** As a developer, I want the backend to live in a dedicated subdirectory of the shared monorepo, so that frontend, docs, and other subprojects coexist in the same repository.

#### Acceptance Criteria

1. THE Build_System SHALL reside entirely within the `foremen-backend/` directory relative to the repository root.
2. All Gradle files (`build.gradle`, `settings.gradle`, `gradle.properties`, `gradlew`, `gradlew.bat`, `gradle/`) SHALL be located inside `foremen-backend/`.
3. THE Application source code SHALL be located at `foremen-backend/src/main/java/com/foremen/`.
4. THE Docker_Compose file SHALL be located at `foremen-backend/docker-compose.yml`.
5. THE database migration files SHALL be located at `foremen-backend/database_files/`.
6. Developers SHALL run Gradle commands from the `foremen-backend/` directory (e.g., `cd foremen-backend && ./gradlew bootRun`).
