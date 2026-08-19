# Requirements Document

## Introduction

This specification defines the base (super) interfaces for the DAO layer of the Foremen backend. Two generic interfaces are created: `ReadOnlyAdminDao` for read operations and `AdminDao` for full CRUD. Both are marked `@NoRepositoryBean` and are intended exclusively for inheritance by concrete entity repositories.

The pattern is taken from the `tickets/api-events` project and adapted for Spring Boot 4, Spring Data JPA (Jakarta), Java 25, and PostgreSQL.

## Glossary

- **ReadOnlyAdminDao**: Generic interface providing read-only operations (find, count, pagination). Extends `Repository<DaoModel, ID>`. Annotated with `@NoRepositoryBean`.
- **AdminDao**: Generic full-CRUD interface extending ReadOnlyAdminDao, `PagingAndSortingRepository`, and `JpaSpecificationExecutor`. Annotated with `@NoRepositoryBean`.
- **DaoModel**: Type parameter representing the JPA entity class.
- **ID**: Type parameter representing the entity's primary key type.
- **BaseEntity**: Abstract superclass of all project entities (from FOR-01-02), containing `Long id` and audit fields.
- **ViewDao**: Pattern of using ReadOnlyAdminDao for SQL VIEWs — a concrete repository overrides the `getViewSelectQuery()` method.
- **Specification**: Spring Data JPA interface (`org.springframework.data.jpa.domain.Specification`) for dynamic queries.

## Requirements

### Requirement 1: ReadOnlyAdminDao — Read-Only Interface

**User Story:** As a developer, I want a generic read-only DAO interface, so that I can create read-only repositories (including for SQL views) without exposing write operations.

#### Acceptance Criteria

1. THE ReadOnlyAdminDao SHALL be a generic interface with type parameters `<DaoModel, ID>`.
2. THE ReadOnlyAdminDao SHALL extend `org.springframework.data.repository.Repository<DaoModel, ID>`.
3. THE ReadOnlyAdminDao SHALL be annotated with `@NoRepositoryBean`.
4. THE ReadOnlyAdminDao SHALL reside in the package `com.foremen.dao`.
5. THE ReadOnlyAdminDao SHALL declare a method `Page<DaoModel> findAll(Pageable pageable)` for paginated retrieval.
6. THE ReadOnlyAdminDao SHALL declare a method `Page<DaoModel> findAll(Specification<DaoModel> specification, Pageable pageable)` for filtered paginated retrieval.
7. THE ReadOnlyAdminDao SHALL declare a method `Optional<DaoModel> findById(ID id)` for retrieval by primary key.
8. THE ReadOnlyAdminDao SHALL declare a method `long count()` for total entity count.
9. THE ReadOnlyAdminDao SHALL declare a method `List<DaoModel> findAllByIdIn(Collection<ID> entityIds)` for batch retrieval by a collection of IDs; IF the collection is empty, THEN THE method SHALL return an empty list.
10. THE ReadOnlyAdminDao SHALL declare a default method `String getViewSelectQuery()` that returns `null`, indicating the repository is not backed by a SQL VIEW.
11. THE ReadOnlyAdminDao SHALL NOT declare any methods that perform create, update, or delete operations (no `save`, `delete`, `flush`, or equivalent write methods).

### Requirement 2: AdminDao — Full CRUD Interface

**User Story:** As a developer, I want a generic CRUD DAO interface, so that I can create entity repositories with full create/read/update/delete capabilities by simply extending a single interface.

#### Acceptance Criteria

1. THE AdminDao SHALL be a generic interface with type parameters `<DaoModel, ID>`.
2. THE AdminDao SHALL extend `ReadOnlyAdminDao<DaoModel, ID>`.
3. THE AdminDao SHALL extend `org.springframework.data.repository.PagingAndSortingRepository<DaoModel, ID>`.
4. THE AdminDao SHALL extend `org.springframework.data.jpa.repository.JpaSpecificationExecutor<DaoModel>`.
5. THE AdminDao SHALL be annotated with `@NoRepositoryBean`.
6. THE AdminDao SHALL reside in the package `com.foremen.dao`.
7. THE AdminDao SHALL NOT declare any additional methods beyond those inherited from its parent interfaces.
8. THE AdminDao SHALL declare its parent interfaces in the following extends order: `ReadOnlyAdminDao<DaoModel, ID>`, `PagingAndSortingRepository<DaoModel, ID>`, `JpaSpecificationExecutor<DaoModel>`.

### Requirement 3: Correctness Properties

**User Story:** As a developer, I want formal correctness guarantees for the DAO interfaces, so that any concrete repository extending them behaves predictably.

#### Acceptance Criteria

1. FOR ALL concrete repositories extending AdminDao, THE repository SHALL inherit and expose all methods declared in ReadOnlyAdminDao, PagingAndSortingRepository, and JpaSpecificationExecutor (invariant: AdminDao is a superset of ReadOnlyAdminDao).
2. FOR ALL concrete repositories extending ReadOnlyAdminDao without extending AdminDao, THE repository SHALL NOT expose any method that persists, updates, or removes entities — specifically no `save`, `saveAll`, `delete`, `deleteById`, `deleteAll`, or `deleteAllById` methods (invariant: read-only contract is enforced by interface hierarchy).
3. FOR ALL calls to `findAllByIdIn` with a collection of N distinct IDs where all N entities exist in the database, THE ReadOnlyAdminDao SHALL return a list containing exactly N entities (invariant: batch retrieval preserves count for existing entities).
4. IF `findAllByIdIn` is called with an empty collection, THEN THE ReadOnlyAdminDao SHALL return an empty list.
5. IF `findAllByIdIn` is called with a collection containing IDs that do not correspond to existing entities, THEN THE ReadOnlyAdminDao SHALL return a list containing only the entities that exist, without raising an error (invariant: partial matches return partial results).
6. FOR ALL calls to `getViewSelectQuery()` on ReadOnlyAdminDao, THE method SHALL return `null` unless overridden by a concrete implementation (invariant: safe default for non-view repositories).
7. FOR ALL concrete repositories, THE `@NoRepositoryBean` annotation on super interfaces SHALL prevent Spring Data from creating proxy implementations for the interfaces themselves (invariant: only concrete sub-interfaces are instantiated).
