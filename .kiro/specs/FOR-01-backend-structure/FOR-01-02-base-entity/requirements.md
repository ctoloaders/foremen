# Requirements Document

## Introduction

This spec defines the `BaseEntity` — the foundational `@MappedSuperclass` that all domain entities in the Foremen system will extend. It provides auto-generated primary keys, automatic auditing fields (creation/modification timestamps and user tracking), and a consistent identity pattern (equals/hashCode). It also covers the JPA auditing configuration required to populate audit fields automatically.

This is the second layer in the FOR-01 backend structure. It depends on FOR-01-01-gradle (completed) which established the Gradle project with Spring Boot 4, JPA, Lombok, and PostgreSQL dependencies.

## Glossary

- **Base_Entity**: The abstract `@MappedSuperclass` class (`com.foremen.dao.model.BaseEntity`) that provides shared fields and behavior for all JPA entities in the system.
- **Auditing_System**: The Spring Data JPA auditing mechanism that automatically populates creation and modification metadata on entity persistence events.
- **Auditing_Configuration**: The Spring `@Configuration` class (`com.foremen.config.audit.JpaAuditingConfig`) that enables JPA auditing and provides the `AuditorAware` bean.
- **Auditor_Provider**: The `AuditorAware<String>` implementation that resolves the current user's identity for audit fields.
- **Entity_Listener**: The `AuditingEntityListener` registered on the Base_Entity via `@EntityListeners` that intercepts JPA lifecycle events to populate audit fields.
- **ID_Generator**: The PostgreSQL sequence-based strategy used to auto-generate primary key values for all entities extending Base_Entity.

## Requirements

### Requirement 1: BaseEntity Class Definition

**User Story:** As a developer, I want a shared mapped superclass with common fields, so that all domain entities inherit a consistent structure without repeating boilerplate.

#### Acceptance Criteria

1. THE Base_Entity SHALL be an abstract class annotated with `@MappedSuperclass`.
2. THE Base_Entity SHALL reside in package `com.foremen.dao.model`.
3. THE Base_Entity SHALL be annotated with `@EntityListeners(AuditingEntityListener.class)` to enable automatic audit field population.
4. THE Base_Entity SHALL use Lombok `@Getter` and `@Setter` annotations to generate accessor methods for all fields.
5. THE Base_Entity SHALL use Lombok `@NoArgsConstructor` annotation to generate the required JPA no-argument constructor.

### Requirement 2: Primary Key Field

**User Story:** As a developer, I want every entity to have a Long auto-generated primary key, so that each row is uniquely identifiable without manual ID assignment.

#### Acceptance Criteria

1. THE Base_Entity SHALL define a field `id` of type `Long`.
2. THE Base_Entity SHALL annotate the `id` field with `@Id` from `jakarta.persistence`.
3. THE Base_Entity SHALL annotate the `id` field with `@GeneratedValue` using `GenerationType.IDENTITY` strategy for PostgreSQL auto-increment.
4. WHILE an entity is in transient state (not yet persisted), THE Base_Entity `id` field SHALL be `null`.
5. WHEN an entity is persisted, THE ID_Generator SHALL assign a unique non-null Long value to the `id` field.

### Requirement 3: Audit Fields — Creation Tracking

**User Story:** As a developer, I want automatic tracking of who created an entity and when, so that the system maintains a creation audit trail without manual intervention.

#### Acceptance Criteria

1. THE Base_Entity SHALL define a field `createdDate` of type `LocalDateTime`.
2. THE Base_Entity SHALL annotate `createdDate` with `@CreatedDate` from Spring Data.
3. THE Base_Entity SHALL annotate `createdDate` with `@Column(nullable = false, updatable = false)` to prevent modification after creation.
4. THE Base_Entity SHALL define a field `createdBy` of type `String`.
5. THE Base_Entity SHALL annotate `createdBy` with `@CreatedBy` from Spring Data.
6. THE Base_Entity SHALL annotate `createdBy` with `@Column(updatable = false)` to prevent modification after creation.
7. WHEN a new entity is persisted, THE Auditing_System SHALL automatically populate `createdDate` with the current timestamp.
8. WHEN a new entity is persisted, THE Auditing_System SHALL automatically populate `createdBy` with the value from the Auditor_Provider.

### Requirement 4: Audit Fields — Modification Tracking

**User Story:** As a developer, I want automatic tracking of who last modified an entity and when, so that the system maintains a modification audit trail.

#### Acceptance Criteria

1. THE Base_Entity SHALL define a field `updatedDate` of type `LocalDateTime`.
2. THE Base_Entity SHALL annotate `updatedDate` with `@LastModifiedDate` from Spring Data.
3. THE Base_Entity SHALL define a field `updatedBy` of type `String`.
4. THE Base_Entity SHALL annotate `updatedBy` with `@LastModifiedBy` from Spring Data.
5. WHEN an existing entity is updated, THE Auditing_System SHALL automatically populate `updatedDate` with the current timestamp.
6. WHEN an existing entity is updated, THE Auditing_System SHALL automatically populate `updatedBy` with the value from the Auditor_Provider.

### Requirement 5: JPA Auditing Configuration

**User Story:** As a developer, I want JPA auditing enabled at the application level with a configurable auditor provider, so that audit annotations on BaseEntity are processed automatically.

#### Acceptance Criteria

1. THE Auditing_Configuration SHALL be a Spring `@Configuration` class annotated with `@EnableJpaAuditing`.
2. THE Auditing_Configuration SHALL reside in package `com.foremen.config.audit`.
3. THE Auditing_Configuration SHALL declare a `@Bean` of type `AuditorAware<String>`.
4. THE Auditor_Provider SHALL return the currently authenticated user's identifier from the Spring Security context.
5. IF no authenticated user is present in the security context, THEN THE Auditor_Provider SHALL return `"system"` as the default auditor value.

### Requirement 6: Equals and HashCode

**User Story:** As a developer, I want a consistent equals/hashCode implementation based on entity identity, so that entities behave correctly in collections and JPA operations across managed/detached states.

#### Acceptance Criteria

1. THE Base_Entity SHALL override `equals()` to compare entities based on the `id` field and class type.
2. THE Base_Entity SHALL override `hashCode()` to return a constant value (or class-based hash) ensuring consistency across entity state transitions.
3. WHEN two entities of the same type have the same non-null `id`, THE Base_Entity `equals()` SHALL return `true`.
4. WHEN an entity has a null `id` (transient state), THE Base_Entity `equals()` SHALL fall back to reference equality (`this == other`).
5. THE Base_Entity SHALL NOT use Lombok `@EqualsAndHashCode` to avoid proxy and lazy-loading issues with JPA.

### Requirement 7: Column Mapping Conventions

**User Story:** As a developer, I want consistent column naming conventions for audit fields, so that the database schema is predictable and aligned with PostgreSQL snake_case standards.

#### Acceptance Criteria

1. THE Base_Entity `id` field SHALL map to a column named `id`.
2. THE Base_Entity `createdDate` field SHALL map to a column named `created_date`.
3. THE Base_Entity `createdBy` field SHALL map to a column named `created_by`.
4. THE Base_Entity `updatedDate` field SHALL map to a column named `updated_date`.
5. THE Base_Entity `updatedBy` field SHALL map to a column named `updated_by`.
6. THE Auditing_Configuration or application configuration SHALL enable a physical naming strategy that converts camelCase field names to snake_case column names (Spring Boot default `CamelCaseToUnderscoresNamingStrategy`).
