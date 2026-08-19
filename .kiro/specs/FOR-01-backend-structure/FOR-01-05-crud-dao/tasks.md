# Implementation Plan: Generic DAO Interfaces (FOR-01-05-crud-dao)

## Overview

Implement the two generic base DAO interfaces: `ReadOnlyAdminDao` (read-only, extends `Repository`) and `AdminDao` (full CRUD, extends `ReadOnlyAdminDao` + `PagingAndSortingRepository` + `JpaSpecificationExecutor`). Both are `@NoRepositoryBean` and serve as parent interfaces for all concrete entity repositories. The implementation builds incrementally: read-only interface first, then CRUD interface, then tests layered on top.

## Tasks

- [x] 1. Create ReadOnlyAdminDao interface
  - [x] 1.1 Create `ReadOnlyAdminDao.java` in `com.foremen.dao`
    - Create file `src/main/java/com/foremen/dao/ReadOnlyAdminDao.java`
    - Declare as `public interface ReadOnlyAdminDao<DaoModel, ID>` extending `Repository<DaoModel, ID>`
    - Annotate with `@NoRepositoryBean`
    - Declare method `Page<DaoModel> findAll(Pageable pageable)`
    - Declare method `Page<DaoModel> findAll(Specification<DaoModel> specification, Pageable pageable)`
    - Declare method `Optional<DaoModel> findById(ID id)`
    - Declare method `long count()`
    - Declare method `List<DaoModel> findAllByIdIn(Collection<ID> entityIds)`
    - Declare default method `String getViewSelectQuery()` returning `null`
    - Import: `org.springframework.data.domain.Page`, `Pageable`, `org.springframework.data.jpa.domain.Specification`, `org.springframework.data.repository.NoRepositoryBean`, `org.springframework.data.repository.Repository`, `java.util.Collection`, `java.util.List`, `java.util.Optional`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10, 1.11_

  - [x] 1.2 Verify compilation succeeds
    - Run `./gradlew compileJava` and ensure zero errors
    - _Requirements: 1.1, 1.2, 1.3, 1.4_

- [x] 2. Create AdminDao interface
  - [x] 2.1 Create `AdminDao.java` in `com.foremen.dao`
    - Create file `src/main/java/com/foremen/dao/AdminDao.java`
    - Declare as `public interface AdminDao<DaoModel, ID>` extending `ReadOnlyAdminDao<DaoModel, ID>`, `PagingAndSortingRepository<DaoModel, ID>`, `JpaSpecificationExecutor<DaoModel>` (in this exact order)
    - Annotate with `@NoRepositoryBean`
    - Empty body — no additional methods declared
    - Import: `org.springframework.data.jpa.repository.JpaSpecificationExecutor`, `org.springframework.data.repository.NoRepositoryBean`, `org.springframework.data.repository.PagingAndSortingRepository`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8_

  - [x] 2.2 Verify compilation succeeds
    - Run `./gradlew compileJava` and ensure zero errors
    - _Requirements: 2.1, 2.5, 2.6_

- [x] 3. Checkpoint — Verify core implementation compiles
  - Ensure all tests pass (`./gradlew test`), ask the user if questions arise.

- [x] 4. Write unit tests for DAO interface structure
  - [x] 4.1 Create `ReadOnlyAdminDaoStructureTest.java` in `com.foremen.dao`
    - Create file `src/test/java/com/foremen/dao/ReadOnlyAdminDaoStructureTest.java`
    - Use reflection to verify `@NoRepositoryBean` annotation present
    - Verify interface extends `Repository`
    - Verify interface has exactly 2 type parameters (`DaoModel`, `ID`)
    - Verify interface resides in `com.foremen.dao` package
    - Verify declared methods: `findAll(Pageable)`, `findAll(Specification, Pageable)`, `findById(Object)`, `count()`, `findAllByIdIn(Collection)`, `getViewSelectQuery()`
    - Verify `getViewSelectQuery()` is a default method
    - Verify no write methods (`save`, `delete`, `flush`, etc.) are declared
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10, 1.11_

  - [x] 4.2 Create `AdminDaoStructureTest.java` in `com.foremen.dao`
    - Create file `src/test/java/com/foremen/dao/AdminDaoStructureTest.java`
    - Use reflection to verify `@NoRepositoryBean` annotation present
    - Verify interface extends `ReadOnlyAdminDao`, `PagingAndSortingRepository`, `JpaSpecificationExecutor`
    - Verify `getGenericInterfaces()` order: `ReadOnlyAdminDao` first, `PagingAndSortingRepository` second, `JpaSpecificationExecutor` third
    - Verify interface has exactly 2 type parameters (`DaoModel`, `ID`)
    - Verify interface resides in `com.foremen.dao` package
    - Verify zero declared methods (empty body)
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8_

  - [x] 4.3 Verify all unit tests pass
    - Run `./gradlew test` and ensure zero failures
    - _Requirements: 1.1, 2.1_

- [x] 5. Write property-based tests (jqwik)
  - [x] 5.1 Create `DaoInterfaceContractPropertyTest.java` in `com.foremen.dao`
    - Create file `src/test/java/com/foremen/dao/DaoInterfaceContractPropertyTest.java`
    - **Property 1: AdminDao is a Superset of ReadOnlyAdminDao**
    - Iterate all declared methods of `ReadOnlyAdminDao` via reflection
    - For each method, verify it is resolvable (accessible) on `AdminDao` via `getMethod(name, parameterTypes)`
    - Use `@Property(tries = 100)` minimum
    - **Validates: Requirements 3.1**

  - [x] 5.2 Create `ReadOnlyContractPropertyTest.java` in `com.foremen.dao`
    - Create file `src/test/java/com/foremen/dao/ReadOnlyContractPropertyTest.java`
    - **Property 2: Read-Only Contract Enforcement**
    - Define write-method patterns: `save`, `saveAll`, `delete`, `deleteById`, `deleteAll`, `deleteAllById`, `flush`, `saveAndFlush`
    - Reflect all methods on `ReadOnlyAdminDao` (declared + inherited from `Repository`)
    - Verify none match any write-method pattern
    - Generate random strings matching write-method patterns via jqwik `@Provide` and verify they do not appear in the interface's method names
    - Use `@Property(tries = 100)` minimum
    - **Validates: Requirements 1.11, 3.2**

  - [x] 5.3 Verify all property tests pass
    - Run `./gradlew test` and ensure zero failures including property tests
    - _Requirements: 3.1, 3.2_

- [x] 6. Write integration tests (Testcontainers)
  - [x] 6.1 Create `ReadOnlyAdminDaoIntegrationTest.java` in `com.foremen.dao.integration`
    - Create file `src/test/java/com/foremen/dao/integration/ReadOnlyAdminDaoIntegrationTest.java`
    - Create a test-scoped `@Entity` extending `BaseEntity` and a concrete `ReadOnlyAdminDao` for it
    - Use `@DataJpaTest` with `@Testcontainers` and PostgreSQL container
    - Test `findAllByIdIn` with all existing IDs → returns exactly N entities (Property 3)
    - Test `findAllByIdIn` with empty collection → returns empty list
    - Test `findAllByIdIn` with partial matches → returns only existing entities (Property 4)
    - Test `findAll(Pageable)` → verify page structure
    - Test `findById` existing → verify present
    - Test `findById` non-existing → verify empty Optional
    - Test `count()` → verify matches persisted count
    - Test `getViewSelectQuery()` default returns `null`
    - _Requirements: 3.3, 3.4, 3.5, 3.6, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10_

  - [x] 6.2 Create `AdminDaoIntegrationTest.java` in `com.foremen.dao.integration`
    - Create file `src/test/java/com/foremen/dao/integration/AdminDaoIntegrationTest.java`
    - Create a test-scoped `@Entity` extending `BaseEntity` and a concrete `AdminDao` for it
    - Use `@DataJpaTest` with `@Testcontainers` and PostgreSQL container
    - Test full CRUD lifecycle: save → findById → update (save again) → delete → verify gone
    - Test batch `saveAll` and `findAllByIdIn` combination
    - Test `findAll(Pageable)` with sorting
    - Test that `@NoRepositoryBean` prevents direct proxy for base interfaces (verify no bean of type `ReadOnlyAdminDao` or `AdminDao` directly in context)
    - _Requirements: 2.2, 2.3, 2.4, 3.1, 3.7_

  - [x] 6.3 Verify all integration tests pass
    - Run `./gradlew test` and ensure zero failures
    - _Requirements: 3.3, 3.5, 3.7_

- [x] 7. Final checkpoint — Ensure all tests pass
  - Run `./gradlew test` and ensure all unit, property, and integration tests pass. Ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each sub-task produces compilable code suitable for a git commit
- The project uses Java 25, Spring Boot 4.0.0, Spring Data JPA (Jakarta), Lombok, and jqwik 1.9.2
- Property tests validate universal correctness properties from the design document (Properties 1 and 2)
- Integration tests validate database-level properties (Properties 3 and 4) with Testcontainers + PostgreSQL
- Unit tests focus on structural/reflection-based verification of interface contracts
- `ReadOnlyAdminDao` extends only `Repository` (marker) — no write methods inherited
- `AdminDao` composes `ReadOnlyAdminDao`, `PagingAndSortingRepository`, and `JpaSpecificationExecutor`

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "2.1"] },
    { "id": 2, "tasks": ["2.2"] },
    { "id": 3, "tasks": ["4.1", "4.2"] },
    { "id": 4, "tasks": ["4.3"] },
    { "id": 5, "tasks": ["5.1", "5.2"] },
    { "id": 6, "tasks": ["5.3", "6.1", "6.2"] },
    { "id": 7, "tasks": ["6.3"] }
  ]
}
```
