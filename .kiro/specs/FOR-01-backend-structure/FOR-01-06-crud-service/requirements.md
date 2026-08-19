# Requirements Document

## Introduction

This specification defines generic CRUD service layer interfaces for the Foremen project. The service layer sits between the DAO (data access) and Controller layers, providing reusable business logic for entity management. The architecture follows the established pattern from the tickets reference project, adapted for Spring Boot 4.0.0 with Jakarta Persistence.

Two core interfaces are defined:
- **ReadOnlyAdminService** — read-only operations with query parsing, pagination, view support, and i18n sort processing
- **AdminService** — extends ReadOnlyAdminService with create, update, delete (hard and soft), batch operations, and audit support

## Glossary

- **ReadOnlyAdminService**: Generic service interface providing read-only operations including find-by-id, paginated queries, specification-based filtering, view support, and i18n sort processing
- **AdminService**: Generic service interface extending ReadOnlyAdminService with full CRUD operations including create, update, hard delete, soft delete, batch operations, audit, and validation hooks
- **ServiceModel**: Localized representation of an entity at the service layer (locale-resolved fields)
- **ServiceExtendedModel**: Full representation of an entity at the service layer (all locale variants included)
- **DaoModel**: JPA entity class used at the data access layer
- **Specification**: JPA criteria-based query abstraction built from raw query strings
- **RawQuery**: A string-based filter expression using operators (==, !=, ~~, >date, <date, ~in~, ~notin~, ~null~, ~notnull~) separated by delimiters, supporting boolean groups with parentheses and AND/OR operators
- **QueryDSL**: The full boolean-logic query language supporting parenthesized groups, explicit AND/OR operators, and nested expressions
- **NestedFieldPath**: A dot-notation path (e.g., "address.city", "project.manager.name") that navigates entity relationships in JPA criteria queries
- **CollectionJoin**: A JPA JOIN operation applied when a nested field path traverses a collection-typed association (e.g., "tasks.status")
- **AccessCriteria**: Specification predicates returned by `addRequiredQuery()` representing access-control constraints that are always combined with the user query via AND
- **View**: A native SQL query associated with a DAO that replaces the default JPA query for read operations
- **SoftDelete**: A deletion strategy that marks entities as deleted (via a status or flag field) rather than physically removing them
- **Audit**: A record of who performed an operation and when, stored alongside or separately from the entity
- **I18nSort**: Sort processing that resolves locale-specific field names (e.g., sorting by "name" resolves to "nameRU" or "namePL" depending on current locale)
- **BooleanOperator**: Logical operator (AND/OR) used to combine multiple specification predicates
- **EntityManager**: Jakarta Persistence EntityManager for native query execution and flush operations
- **ForemenApiException**: Application exception carrying HTTP status code and i18n message code

## Requirements

### Requirement 1: ReadOnlyAdminService Interface Definition

**User Story:** As a developer, I want a generic read-only service interface, so that I can implement consistent read operations across all entities without duplicating code.

#### Acceptance Criteria

1. THE ReadOnlyAdminService SHALL be a generic interface parameterized with ServiceModel, ServiceExtendedModel, DaoModel, and ID types
2. THE ReadOnlyAdminService SHALL declare a `getMapper()` method returning the ServiceToDaoMapper for the entity
3. THE ReadOnlyAdminService SHALL declare a `getReadDao()` method returning the ReadOnlyAdminDao for the entity
4. THE ReadOnlyAdminService SHALL declare a `getEntityManager()` method returning the Jakarta Persistence EntityManager

### Requirement 2: Find By ID Operations

**User Story:** As a developer, I want to find entities by their identifier, so that I can retrieve single entity details in both extended and localized formats.

#### Acceptance Criteria

1. WHEN `findById` is called with a valid ID, THE ReadOnlyAdminService SHALL return the entity mapped to ServiceExtendedModel
2. WHEN `findById` is called with an ID that does not exist in the database, THE ReadOnlyAdminService SHALL throw a ForemenApiException with HTTP 404 status
3. WHEN `findByIdLocalized` is called with a valid ID, THE ReadOnlyAdminService SHALL return the entity mapped to ServiceModel using locale-resolved fields
4. WHEN `findByIdLocalized` is called with an ID that does not exist in the database, THE ReadOnlyAdminService SHALL throw a ForemenApiException with HTTP 404 status

### Requirement 3: Paginated Find Operations

**User Story:** As a developer, I want paginated query methods with optional filtering, so that I can efficiently retrieve large datasets with server-side pagination and filtering.

#### Acceptance Criteria

1. WHEN `find` is called with a Pageable parameter, THE ReadOnlyAdminService SHALL return a Page of ServiceModel objects
2. WHEN `find` is called with a Pageable and a rawQuery string, THE ReadOnlyAdminService SHALL parse the rawQuery into a Specification, apply it as a filter, and return a Page of ServiceModel objects
3. WHEN `findExtended` is called with a Pageable parameter, THE ReadOnlyAdminService SHALL return a Page of ServiceExtendedModel objects
4. WHEN `findExtended` is called with a Pageable and a rawQuery string, THE ReadOnlyAdminService SHALL parse the rawQuery into a Specification, apply it as a filter, and return a Page of ServiceExtendedModel objects
5. WHEN a DAO provides a non-null viewSelectQuery, THE ReadOnlyAdminService SHALL use a native SQL query with the view definition instead of the standard JPA query for paginated find operations
6. WHEN paginated results are returned, THE ReadOnlyAdminService SHALL process the Sort within Pageable to resolve i18n field names according to the current locale

### Requirement 4: Batch Find Operations

**User Story:** As a developer, I want to find multiple entities by their IDs at once, so that I can efficiently load related entities in batch.

#### Acceptance Criteria

1. WHEN `findAllByIds` is called with a collection of IDs, THE ReadOnlyAdminService SHALL return a list of ServiceExtendedModel objects for all entities matching the provided IDs
2. WHEN `findAllByIds` is called with a collection containing IDs that do not exist, THE ReadOnlyAdminService SHALL return results only for existing entities without throwing an exception

### Requirement 5: Query Parsing and Specification Building

**User Story:** As a developer, I want to parse raw query strings into JPA Specifications using a full boolean-logic DSL, so that I can support flexible filtering with grouping and logical operators via URL query parameters.

#### Acceptance Criteria

1. WHEN `parseSpecification` is called with a rawQuery, THE ReadOnlyAdminService SHALL parse the query string into individual filter expressions and logical groups according to the QueryDSL grammar
2. THE ReadOnlyAdminService SHALL support the equality operator (==) to match exact field values
3. THE ReadOnlyAdminService SHALL support the inequality operator (!=) to exclude matching field values
4. THE ReadOnlyAdminService SHALL support the like operator (~~) to match partial string values using case-insensitive LIKE
5. THE ReadOnlyAdminService SHALL support the greater-than-date operator (>date) to filter date fields after a specified value
6. THE ReadOnlyAdminService SHALL support the less-than-date operator (<date) to filter date fields before a specified value
7. THE ReadOnlyAdminService SHALL support the in operator (~in~) to match field values within a specified set
8. THE ReadOnlyAdminService SHALL support the not-in operator (~notin~) to exclude field values within a specified set
9. THE ReadOnlyAdminService SHALL support the null operator (~null~) to match fields with null values
10. THE ReadOnlyAdminService SHALL support the not-null operator (~notnull~) to match fields with non-null values
11. WHEN expressions are separated by the `;` delimiter without an explicit operator, THE ReadOnlyAdminService SHALL combine them using AND (backward compatibility)
12. WHEN explicit `AND` operator appears between expressions or groups, THE ReadOnlyAdminService SHALL combine those predicates using logical conjunction
13. WHEN explicit `OR` operator appears between expressions or groups, THE ReadOnlyAdminService SHALL combine those predicates using logical disjunction
14. WHEN parentheses enclose a sub-expression (e.g., `(field1==value1 OR field2==value2)`), THE ReadOnlyAdminService SHALL evaluate the parenthesized group as a single compound predicate
15. WHEN parenthesized groups are nested (e.g., `(a==1 AND (b==2 OR c==3))`), THE ReadOnlyAdminService SHALL recursively evaluate nested groups preserving operator precedence
16. WHEN `addRequiredQuery` returns non-null AccessCriteria, THE ReadOnlyAdminService SHALL wrap the entire user query as one block and combine it with the AccessCriteria using AND, producing the structure `(user_query) AND (access_criteria)`
17. WHEN the user query contains OR groups, THE ReadOnlyAdminService SHALL ensure that AccessCriteria are applied to the entire result set and not distributed into individual OR branches

### Requirement 17: Nested Field Support in Specification Parsing

**User Story:** As a developer, I want to filter by nested entity fields using dot-notation, so that I can query across entity relationships without writing custom specifications.

#### Acceptance Criteria

1. WHEN a filter expression references a simple nested field using dot-notation (e.g., `address.city==Warsaw`), THE ReadOnlyAdminService SHALL navigate the entity graph using `root.get("address").get("city")` to build the predicate
2. WHEN a filter expression references a collection-typed association using dot-notation (e.g., `tasks.status==completed`), THE ReadOnlyAdminService SHALL use `root.join("tasks")` and apply the predicate to the joined entity
3. WHEN a collection join is used in a query, THE ReadOnlyAdminService SHALL apply SELECT DISTINCT to the query to avoid duplicate results
4. WHEN multiple filter expressions reference the same collection field, THE ReadOnlyAdminService SHALL reuse the same JOIN instance for all predicates on that collection
5. WHEN a filter expression references a deeply nested field (e.g., `project.manager.name~~John`), THE ReadOnlyAdminService SHALL navigate multiple levels of `get()` to reach the target attribute
6. IF a dot-notation path references a field that does not exist on the entity, THEN THE ReadOnlyAdminService SHALL throw a ForemenApiException with HTTP 400 status indicating an invalid field path

### Requirement 6: Count with Specification

**User Story:** As a developer, I want to count entities matching a query, so that I can display totals without loading full entity data.

#### Acceptance Criteria

1. WHEN `getCount` is called with a rawQuery string, THE ReadOnlyAdminService SHALL parse the query into a Specification and return the count of matching entities
2. WHEN `getCount` is called with a null or empty rawQuery, THE ReadOnlyAdminService SHALL return the total count of all non-deleted entities

### Requirement 7: Soft-Delete Filtering

**User Story:** As a developer, I want soft-deleted entities excluded from read operations by default, so that deleted data is hidden without requiring explicit filters in every query.

#### Acceptance Criteria

1. THE ReadOnlyAdminService SHALL provide a default `isDeleted(DaoModel)` method that returns false
2. WHEN a subclass overrides `isDeleted` to define soft-delete logic, THE ReadOnlyAdminService SHALL exclude entities where `isDeleted` returns true from all find operations
3. WHEN `addRequiredQuery` is used to enforce a soft-delete filter at the specification level, THE ReadOnlyAdminService SHALL apply the filter to all specification-based queries

### Requirement 8: Permission Filtering and Admin-Only Fields

**User Story:** As a developer, I want to hide sensitive fields from non-admin users, so that role-based data visibility is enforced at the service layer.

#### Acceptance Criteria

1. THE ReadOnlyAdminService SHALL provide a default `getAdminOnlyFields()` method that returns an empty set
2. WHEN a subclass overrides `getAdminOnlyFields`, THE ReadOnlyAdminService SHALL null out the specified fields in the response model for non-admin callers
3. WHEN the caller has admin role, THE ReadOnlyAdminService SHALL return all fields including those listed in getAdminOnlyFields

### Requirement 9: View Support with Native SQL

**User Story:** As a developer, I want to execute paginated queries against database views defined as native SQL, so that I can return aggregated or joined data not easily expressed via JPA entities.

#### Acceptance Criteria

1. WHEN the DAO's `getViewSelectQuery()` returns a non-null SQL string, THE ReadOnlyAdminService SHALL execute paginated queries using that native SQL as the select source
2. WHEN a view query is active, THE ReadOnlyAdminService SHALL apply specification-based WHERE clauses to the native SQL query
3. WHEN a view query is active, THE ReadOnlyAdminService SHALL apply pagination (limit/offset) and sorting to the native SQL query
4. WHEN the DAO's `getViewSelectQuery()` returns null, THE ReadOnlyAdminService SHALL use standard JPA repository methods for data access

### Requirement 10: I18n Sort Processing

**User Story:** As a developer, I want sort field names resolved according to the current locale, so that sorting by localized fields (e.g., "name") uses the correct locale column (e.g., "nameRU" or "namePL").

#### Acceptance Criteria

1. WHEN a Sort property matches a field listed in the mapper's `getI18nSupportedProperties()`, THE ReadOnlyAdminService SHALL replace the sort property with the locale-specific variant based on the current request locale
2. WHEN a Sort property does not match any i18n property, THE ReadOnlyAdminService SHALL pass the sort property through unchanged
3. WHEN the current locale is Russian, THE ReadOnlyAdminService SHALL append "RU" suffix to i18n sort field names
4. WHEN the current locale is not Russian, THE ReadOnlyAdminService SHALL append "PL" suffix to i18n sort field names (Polish as default)

### Requirement 18: Nested Field Sorting

**User Story:** As a developer, I want to sort by nested entity fields using dot-notation, so that I can order results by related entity attributes.

#### Acceptance Criteria

1. WHEN a Sort property uses dot-notation for a singular association (e.g., `address.city`), THE ReadOnlyAdminService SHALL navigate the entity graph path to resolve the sort property
2. WHEN a Sort property uses deep dot-notation (e.g., `project.manager.name`), THE ReadOnlyAdminService SHALL navigate multiple levels of entity associations to resolve the sort property
3. IF a Sort property uses dot-notation that traverses a collection-typed association, THEN THE ReadOnlyAdminService SHALL throw a ForemenApiException with HTTP 400 status explaining that sorting by collection fields is not supported
4. WHEN i18n sort processing and nested field sort are both applicable to the same Sort property, THE ReadOnlyAdminService SHALL apply i18n resolution to the final segment of the nested path

### Requirement 11: AdminService Interface Definition

**User Story:** As a developer, I want a generic read-write service interface extending the read-only interface, so that I can implement full CRUD operations with consistent patterns.

#### Acceptance Criteria

1. THE AdminService SHALL extend ReadOnlyAdminService with the same generic type parameters (ServiceModel, ServiceExtendedModel, DaoModel, ID)
2. THE AdminService SHALL declare a `getDao()` method returning the AdminDao for the entity
3. THE AdminService SHALL provide a default `getWriteDao()` method that delegates to `getDao()`
4. THE AdminService SHALL provide a default `getReadDao()` method that delegates to `getDao()`

### Requirement 12: Create Operations

**User Story:** As a developer, I want to create entities through the service layer, so that entity creation includes mapping, validation, and persistence in a consistent manner.

#### Acceptance Criteria

1. WHEN `create` is called with a ServiceExtendedModel, THE AdminService SHALL map the model to a DaoModel, persist it via the DAO, and return the persisted entity mapped back to ServiceExtendedModel
2. WHEN `create` is called with a list of ServiceExtendedModel objects, THE AdminService SHALL map each model to a DaoModel, persist all via the DAO, and return the persisted entities mapped back to ServiceExtendedModel list
3. WHEN a create operation completes successfully, THE AdminService SHALL flush the EntityManager to ensure the entity ID is generated

### Requirement 13: Update Operations

**User Story:** As a developer, I want to update entities through the service layer with validation hooks, so that updates are validated and audited consistently.

#### Acceptance Criteria

1. WHEN `update` is called with an ID and ServiceExtendedModel, THE AdminService SHALL find the existing entity by ID, invoke `validateUpdate`, apply field updates via the mapper, and persist the changes
2. WHEN `update` is called with an ID that does not exist, THE AdminService SHALL throw a ForemenApiException with HTTP 404 status
3. WHEN `updateAll` is called with a list of IDs and a ServiceExtendedModel, THE AdminService SHALL apply the update to each entity matching the provided IDs
4. WHEN `updateSingleField` is called with an ID, a value, and a BiConsumer, THE AdminService SHALL find the entity by ID and apply the value using the provided BiConsumer setter
5. THE AdminService SHALL provide a default `validateUpdate(DaoModel existing, ServiceExtendedModel update)` method that performs no validation, allowing subclasses to override with custom logic

### Requirement 14: Delete Operations

**User Story:** As a developer, I want both hard and soft delete operations, so that I can permanently remove entities or mark them as deleted depending on the business requirement.

#### Acceptance Criteria

1. WHEN `deleteById` is called with an ID, THE AdminService SHALL permanently remove the entity from the database
2. WHEN `deleteById` is called with an ID that does not exist, THE AdminService SHALL throw a ForemenApiException with HTTP 404 status
3. WHEN `deleteAll` is called with a list of IDs, THE AdminService SHALL permanently remove all entities matching the provided IDs
4. WHEN `softDelete` is called with a field name and a set of IDs, THE AdminService SHALL execute a criteria update setting the specified field to mark entities as deleted without physically removing them
5. WHEN `setPropertiesToNull` is called with an ID and a set of property names, THE AdminService SHALL validate that each property name corresponds to an existing field on the DaoModel entity
6. IF `setPropertiesToNull` is called with a property name that does not exist on the DaoModel, THEN THE AdminService SHALL throw a ForemenApiException with HTTP 400 status indicating the invalid field name
7. WHEN `setPropertiesToNull` is called with valid property names, THE AdminService SHALL execute a CriteriaUpdate to SET the specified fields to NULL on the entity matching the given ID

### Requirement 15: Audit Support

**User Story:** As a developer, I want audit records created for write operations, so that entity modification history is tracked for compliance and debugging.

#### Acceptance Criteria

1. WHEN a create operation completes, THE AdminService SHALL invoke the audit mechanism to record the creation event
2. WHEN an update operation completes, THE AdminService SHALL invoke the audit mechanism to record the update event with changed field information
3. THE AdminService SHALL provide a default `saveAudit` method that subclasses can override to implement entity-specific audit logging
4. WHEN audit is not overridden by a subclass, THE AdminService SHALL perform no audit action (no-op default)

### Requirement 16: I18n Translation Processing on Write

**User Story:** As a developer, I want i18n fields processed during create and update operations, so that locale-specific field values are properly normalized before persistence.

#### Acceptance Criteria

1. WHEN a create or update operation processes a DaoModel with i18n-supported properties, THE AdminService SHALL invoke the mapper's `processI18nEmptyValues` to normalize blank locale fields to null
2. WHEN an entity has i18n-supported properties, THE AdminService SHALL ensure both RU and PL locale variants are processed during write operations
