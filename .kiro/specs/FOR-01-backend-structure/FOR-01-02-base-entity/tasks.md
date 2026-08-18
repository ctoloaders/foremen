# Implementation Plan: BaseEntity and JPA Auditing (FOR-01-02-base-entity)

## Overview

Implement the foundational `BaseEntity` mapped superclass and JPA auditing infrastructure. Each sub-task produces a compilable, meaningful commit. The implementation builds incrementally: configuration first, then the entity class, then tests layered on top.

## Tasks

- [x] 1. Create JPA Auditing Configuration
  - [x] 1.1 Create `JpaAuditingConfig.java` in `com.foremen.config.audit`
    - Create file `src/main/java/com/foremen/config/audit/JpaAuditingConfig.java`
    - Annotate with `@Configuration` and `@EnableJpaAuditing`
    - Declare a `@Bean` method returning `AuditorAware<String>`
    - Implement auditor logic: read `SecurityContextHolder.getContext().getAuthentication()`
    - Return authenticated user's name, or `"system"` for null/unauthenticated/anonymous states
    - Handle `AnonymousAuthenticationToken` explicitly
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

  - [x] 1.2 Verify compilation succeeds
    - Run `./gradlew compileJava` and ensure zero errors
    - _Requirements: 5.1, 5.2, 5.3_

- [x] 2. Create BaseEntity abstract class
  - [x] 2.1 Create `BaseEntity.java` in `com.foremen.dao.model`
    - Create file `src/main/java/com/foremen/dao/model/BaseEntity.java`
    - Declare as `public abstract class` with `@MappedSuperclass`
    - Add `@EntityListeners(AuditingEntityListener.class)`
    - Add Lombok `@Getter`, `@Setter`, `@NoArgsConstructor`
    - Define `id` field: `Long`, annotated with `@Id` and `@GeneratedValue(strategy = GenerationType.IDENTITY)`
    - Define `createdDate` field: `LocalDateTime`, annotated with `@CreatedDate` and `@Column(nullable = false, updatable = false)`
    - Define `createdBy` field: `String`, annotated with `@CreatedBy` and `@Column(updatable = false)`
    - Define `updatedDate` field: `LocalDateTime`, annotated with `@LastModifiedDate`
    - Define `updatedBy` field: `String`, annotated with `@LastModifiedBy`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 2.1, 2.2, 2.3, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 4.1, 4.2, 4.3, 4.4_

  - [x] 2.2 Implement equals() and hashCode() (Vlad Mihalcea pattern)
    - Override `equals()`: return `true` only if same class, both IDs non-null, and IDs equal; use `this == o` for reference check first
    - Override `hashCode()`: return `getClass().hashCode()` (constant per entity type)
    - Do NOT use Lombok `@EqualsAndHashCode`
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

  - [x] 2.3 Verify compilation succeeds
    - Run `./gradlew compileJava` and ensure zero errors
    - _Requirements: 1.1, 2.1, 7.1, 7.2, 7.3, 7.4, 7.5_

- [x] 3. Checkpoint — Verify core implementation compiles
  - Ensure all tests pass (`./gradlew test`), ask the user if questions arise.

- [x] 4. Write unit tests for BaseEntity and JpaAuditingConfig
  - [x] 4.1 Create `BaseEntityStructureTest.java` in `com.foremen.dao.model`
    - Create file `src/test/java/com/foremen/dao/model/BaseEntityStructureTest.java`
    - Use reflection to verify `@MappedSuperclass` annotation on BaseEntity
    - Verify `@EntityListeners(AuditingEntityListener.class)` present
    - Verify `@Id` and `@GeneratedValue(strategy = IDENTITY)` on `id` field
    - Verify `@CreatedDate` and `@Column(nullable = false, updatable = false)` on `createdDate`
    - Verify `@CreatedBy` and `@Column(updatable = false)` on `createdBy`
    - Verify `@LastModifiedDate` on `updatedDate`
    - Verify `@LastModifiedBy` on `updatedBy`
    - Verify class is abstract
    - _Requirements: 1.1, 1.3, 2.2, 2.3, 3.2, 3.3, 3.5, 3.6, 4.2, 4.4_

  - [x] 4.2 Create `BaseEntityEqualsTest.java` in `com.foremen.dao.model`
    - Create file `src/test/java/com/foremen/dao/model/BaseEntityEqualsTest.java`
    - Create a concrete test subclass of BaseEntity for testing
    - Test: transient entity (null id) is not equal to another transient entity
    - Test: `entity.equals(null)` returns `false`
    - Test: two entities with same non-null ID are equal
    - Test: two entities with different non-null IDs are not equal
    - Test: entities of different concrete types with same ID are not equal
    - Test: `hashCode()` is consistent before and after setting ID
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

  - [x] 4.3 Create `JpaAuditingConfigTest.java` in `com.foremen.config.audit`
    - Create file `src/test/java/com/foremen/config/audit/JpaAuditingConfigTest.java`
    - Verify `@Configuration` and `@EnableJpaAuditing` annotations on class
    - Test auditor returns `"system"` when authentication is null
    - Test auditor returns `"system"` when authentication is anonymous (`AnonymousAuthenticationToken`)
    - Test auditor returns username when authentication is valid and authenticated
    - Use `SecurityContextHolder` to set up mock security contexts
    - _Requirements: 5.1, 5.4, 5.5_

  - [x] 4.4 Verify all tests pass
    - Run `./gradlew test` and ensure zero failures
    - _Requirements: 1.1, 5.1, 6.1_

- [x] 5. Write property-based tests (jqwik)
  - [x] 5.1 Create `BaseEntityEqualsHashCodePropertyTest.java` in `com.foremen.dao.model`
    - Create file `src/test/java/com/foremen/dao/model/BaseEntityEqualsHashCodePropertyTest.java`
    - **Property 1: Equals Contract (reflexivity, symmetry, transitivity)**
    - Generate pairs/triples of test entities with random Long IDs
    - Verify reflexivity: `entity.equals(entity)` is true
    - Verify symmetry: if `a.equals(b)` then `b.equals(a)`
    - Verify transitivity: if `a.equals(b)` and `b.equals(c)` then `a.equals(c)`
    - Verify ID-based equality: same ID → equal, different ID → not equal
    - **Property 3: Equals/HashCode Contract Compatibility**
    - For any two entities where `a.equals(b)`, verify `a.hashCode() == b.hashCode()`
    - Use `@Property(tries = 100)` minimum
    - **Validates: Requirements 6.1, 6.2, 6.3**

  - [x] 5.2 Create `BaseEntityHashCodeConsistencyPropertyTest.java` in `com.foremen.dao.model`
    - Create file `src/test/java/com/foremen/dao/model/BaseEntityHashCodeConsistencyPropertyTest.java`
    - **Property 2: HashCode Consistency Across State Transitions**
    - Generate entity, record hashCode, set random Long ID via `setId()`, verify hashCode unchanged
    - Verify hashCode is stable across multiple calls
    - Use `@Property(tries = 100)` minimum
    - **Validates: Requirements 6.2**

  - [x] 5.3 Create `AuditorAwarePropertyTest.java` in `com.foremen.config.audit`
    - Create file `src/test/java/com/foremen/config/audit/AuditorAwarePropertyTest.java`
    - **Property 4: Auditor Provider Returns Authenticated Principal**
    - Generate random non-empty username strings
    - Configure mock SecurityContext with generated username
    - Verify auditor returns the exact username
    - Test with null/anonymous/unauthenticated states → verify returns `"system"`
    - Use `@Property(tries = 100)` minimum
    - **Validates: Requirements 5.4, 5.5**

  - [x] 5.4 Create `NamingStrategyPropertyTest.java` in `com.foremen.naming`
    - Create file `src/test/java/com/foremen/naming/NamingStrategyPropertyTest.java`
    - **Property 5: Naming Strategy CamelCase-to-Snake_Case Conversion**
    - Generate random valid camelCase Java field names
    - Apply `CamelCaseToUnderscoresNamingStrategy` and verify correct snake_case output
    - Verify known field mappings: `createdDate` → `created_date`, `createdBy` → `created_by`, etc.
    - Use `@Property(tries = 100)` minimum
    - **Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5**

  - [x] 5.5 Verify all property tests pass
    - Run `./gradlew test` and ensure zero failures including property tests
    - _Requirements: 6.1, 6.2, 5.4, 5.5, 7.1_

- [-] 6. Final checkpoint — Ensure all tests pass
  - Run `./gradlew test` and ensure all unit tests and property tests pass. Ask the user if questions arise.
  - Commit the change including specs

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each sub-task produces compilable code suitable for a git commit
- The project uses Java 25, Spring Boot 4, Jakarta Persistence, Lombok, and jqwik 1.9.2
- Spring Boot's default `CamelCaseToUnderscoresNamingStrategy` handles column naming — no explicit mapping needed
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific examples and edge cases

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "2.1"] },
    { "id": 2, "tasks": ["2.2"] },
    { "id": 3, "tasks": ["2.3"] },
    { "id": 4, "tasks": ["4.1", "4.2", "4.3"] },
    { "id": 5, "tasks": ["4.4"] },
    { "id": 6, "tasks": ["5.1", "5.2", "5.3", "5.4"] },
    { "id": 7, "tasks": ["5.5"] }
  ]
}
```
