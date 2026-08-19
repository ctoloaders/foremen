# Implementation Plan: Generic CRUD Service Layer (FOR-01-06-crud-service)

## Overview

Implement the generic CRUD service layer consisting of `ReadOnlyAdminService` and `AdminService` interfaces with default methods, a full recursive-descent QueryDSL parser (20 operators, no semicolons, i18n filter resolution), universal audit logging via `AuditLogEntity` + `AuditLogDao`, and a Liquibase migration for the `audit_log` table. The implementation follows the package structure: `com.foremen.service` for interfaces, `com.foremen.service.audit` for audit infrastructure, and `com.foremen.service.query` for parser components. All code targets Java 25, Spring Boot 4.0.0, Jakarta Persistence, and uses jqwik 1.9.2 for property-based testing.

## Tasks

- [x] 1. Implement audit infrastructure and QueryDSL parser foundations
  - [x] 1.1 Create AuditLogEntity, AuditLogDao, and Liquibase migration
    - Create `com.foremen.service.audit.AuditLogEntity` extending `BaseEntity` with fields: `entityClass` (VARCHAR 255, NOT NULL), `entityId` (BIGINT), `operation` (VARCHAR 50, NOT NULL), `performedBy` (VARCHAR 255, NOT NULL), `performedAt` (TIMESTAMP, NOT NULL)
    - Create `com.foremen.service.audit.AuditLogDao` interface extending `JpaRepository<AuditLogEntity, Long>`
    - Create Liquibase changeset in `database_files/changelog.xml` for `audit_log` table with columns: id (BIGSERIAL PK), entity_class, entity_id, operation, performed_by, performed_at (DEFAULT NOW()), created_date (DEFAULT NOW()), modified_date (DEFAULT NOW())
    - Add indexes: `idx_audit_log_entity` on (entity_class, entity_id), `idx_audit_log_performed_at` on (performed_at)
    - _Requirements: 15.1, 15.2, 15.3_

  - [x] 1.2 Create QueryOperator enum with full 20-operator set
    - Create `com.foremen.service.query.QueryOperator` enum with operators: EQUALS(`==`), NOT_EQUALS(`!=`), CONTAINS(`~ct~`), STARTS_WITH(`~sw~`), ENDS_WITH(`~ew~`), CONTAINS_CS(`~CT~`), STARTS_WITH_CS(`~SW~`), ENDS_WITH_CS(`~EW~`), LIKE(`~~`), GREATER_THAN(`>`), LESS_THAN(`<`), GREATER_THAN_OR_EQUAL(`>=`), LESS_THAN_OR_EQUAL(`<=`), GT_DATE(`>date`), LT_DATE(`<date`), IN(`~in~`), NOT_IN(`~notin~`), NULL(`~null~`), NOT_NULL(`~notnull~`)
    - Add `getSymbol()` method and `ORDERED_FOR_MATCHING` static list (longest symbols first to avoid partial matches: NOT_NULL, NOT_IN, NULL, IN, CONTAINS, STARTS_WITH, ENDS_WITH, CONTAINS_CS, STARTS_WITH_CS, ENDS_WITH_CS, GT_DATE, LT_DATE, GTE, LTE, GT, LT, EQUALS, NOT_EQUALS, LIKE)
    - _Requirements: 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 5.9, 5.10_

  - [x] 1.3 Create QueryToken sealed interface and TokenStream utility
    - Create `com.foremen.service.query.QueryToken` sealed interface with permitted records: `Filter(String field, QueryOperator operator, String value)`, `And`, `Or`, `OpenParen`, `CloseParen` — NO Semicolon variant
    - Create `com.foremen.service.query.TokenStream` utility class with methods: `hasMore()`, `peek()`, `consume()`, `position()`
    - _Requirements: 5.1, 5.12, 5.13, 5.14_

- [x] 2. Implement QueryTokenizer and SpecificationBuilder
  - [x] 2.1 Implement QueryTokenizer
    - Create `com.foremen.service.query.QueryTokenizer` class that converts raw query strings into `List<QueryToken>`
    - Handle: whitespace skipping, parentheses `(` `)`, AND/OR keyword detection (case-insensitive, word boundary), filter expression extraction using `ORDERED_FOR_MATCHING` (longest-first operator matching)
    - Semicolons are NOT valid — any `;` character triggers `ForemenApiException(400, "error.query.tokenize.unexpected.char")`
    - Implement `tryParseFilter` scanning for the first matching operator symbol between the current position and the next delimiter
    - Implement `findValueEnd` — value ends at `)`, `(`, or whitespace followed by AND/OR keyword
    - _Requirements: 5.1, 5.12, 5.13, 5.14_

  - [x] 2.2 Implement SpecificationBuilder with all 20 operators and automatic collection join detection
    - Create `com.foremen.service.query.SpecificationBuilder` with static `buildPredicate(QueryToken.Filter, Class<T>)` method returning `Specification<T>`
    - Implement `resolvePath(Root<T>, String field, CriteriaQuery<?>)`:
      - Simple fields: `root.get(field)`
      - Dot-notation with automatic collection join detection: inspect each segment via `from.getModel().getAttribute(name).isCollection()` — if collection, use `join()` + `query.distinct(true)`; if association, use `join()` for navigation; if simple attribute, use `get()`
      - Reuse existing JOIN instances via `from.getJoins()` lookup by attribute name
    - Implement `buildCriteriaPredicate` dispatching on all 20 operators:
      - EQUALS/NOT_EQUALS: `cb.equal`/`cb.notEqual` with type conversion
      - CONTAINS/LIKE: `cb.like(cb.lower(path), "%value%")` (case-insensitive)
      - STARTS_WITH: `cb.like(cb.lower(path), "value%")` (case-insensitive)
      - ENDS_WITH: `cb.like(cb.lower(path), "%value")` (case-insensitive)
      - CONTAINS_CS: `cb.like(path, "%value%")` (case-sensitive)
      - STARTS_WITH_CS: `cb.like(path, "value%")` (case-sensitive)
      - ENDS_WITH_CS: `cb.like(path, "%value")` (case-sensitive)
      - GREATER_THAN/LESS_THAN/GTE/LTE: `cb.greaterThan`/`cb.lessThan`/`cb.greaterThanOrEqualTo`/`cb.lessThanOrEqualTo`
      - GT_DATE/LT_DATE: date-specific comparison with `parseDateTime`
      - IN/NOT_IN: `path.in(parseInValues)` / `cb.not(path.in(...))`
      - NULL/NOT_NULL: `cb.isNull`/`cb.isNotNull`
    - Implement `convertValue` for Long, Integer, Double, Float, Boolean, LocalDateTime, LocalDate, String
    - Throw `ForemenApiException(400)` for invalid field paths and invalid dates
    - _Requirements: 5.2–5.10, 17.1, 17.2, 17.3, 17.4, 17.5, 17.6_

  - [x] 2.3 Write unit tests for QueryOperator enum
    - Test all 20 operators have correct symbols
    - Test `ORDERED_FOR_MATCHING` contains all operators and longest-first ordering prevents partial matches
    - _Requirements: 5.2–5.10_

  - [x] 2.4 Write unit tests for QueryTokenizer
    - Test tokenization of simple filters with all operator types
    - Test parentheses, AND/OR keywords (case-insensitive)
    - Test semicolon rejection → ForemenApiException 400
    - Test error cases: unrecognized characters, edge cases
    - _Requirements: 5.1, 5.12, 5.13, 5.14_

- [x] 3. Implement QueryParser with i18n filter field resolution
  - [x] 3.1 Implement QueryParser (recursive-descent with i18n resolution)
    - Create `com.foremen.service.query.QueryParser` with static `parse(String rawQuery, Class<T> entityClass, Set<String> i18nProperties, String localeSuffix)` method
    - Implement `resolveI18nFilterFields(List<QueryToken>, Set<String>, String)` — maps over tokens, resolving i18n field names in Filter tokens BEFORE spec building (append localeSuffix to fields in i18nProperties, handle dot-notation by resolving final segment)
    - Implement `parseQuery` handling AND and OR token dispatch (no semicolons)
    - Implement `parseExpression` handling OpenParen (recursive group) and Filter (leaf) tokens
    - Implement `expectToken` for CloseParen validation
    - Throw `ForemenApiException(400)` for unexpected tokens and unexpected end of input
    - _Requirements: 5.1, 5.12, 5.13, 5.14, 5.15_

  - [x] 3.2 Write unit tests for QueryParser
    - Test simple equality: `name==John` → Filter(name, EQUALS, John)
    - Test AND: `a==1 AND b==2` → AND(Filter(a), Filter(b))
    - Test OR: `a==1 OR b==2` → OR(Filter(a), Filter(b))
    - Test parenthesized: `(a==1 OR b==2) AND c==3`
    - Test nested parentheses: `(a==1 AND (b==2 OR c==3))`
    - Test all 20 operators produce correct Filter tokens
    - Test i18n resolution: `name==John` with i18nProperties={"name"}, localeSuffix="RU" → Filter(nameRU, EQUALS, John)
    - Test i18n nested: `project.name==Test` → Filter(project.nameRU, EQUALS, Test)
    - Test non-i18n unchanged: `status==active` → Filter(status, EQUALS, active)
    - Test error: semicolons in query → 400
    - Test error: unclosed parenthesis → 400
    - Test error: unexpected tokens → 400
    - Test empty/null query → no-op specification
    - _Requirements: 5.1–5.15_

- [x] 4. Checkpoint - Ensure QueryDSL parser tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Implement ReadOnlyAdminService interface
  - [x] 5.1 Create ReadOnlyAdminService interface with abstract methods and find-by-ID defaults
    - Create `com.foremen.service.ReadOnlyAdminService<ServiceModel, ServiceExtendedModel, DaoModel, ID>` interface
    - Declare abstract methods: `getMapper()`, `getReadDao()`, `getEntityManager()`, `getDaoModelClass()`
    - Implement default `findById(ID)` — delegates to DAO, maps via mapper, throws 404 if not found
    - Implement default `findByIdLocalized(ID)` — same pattern with `toServiceModel`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.1, 2.2, 2.3, 2.4_

  - [x] 5.2 Implement paginated find methods and view support
    - Implement default `find(Pageable)` and `find(Pageable, String rawQuery)` returning `Page<ServiceModel>`
    - Implement default `findExtended(Pageable)` and `findExtended(Pageable, String rawQuery)` returning `Page<ServiceExtendedModel>`
    - Wire `processSort`, `buildFinalSpecification`, `isDeleted` filtering, `maskAdminOnlyFields`
    - Implement `executeViewQuery` using `EntityManager.createNativeQuery` with DAO's `getViewSelectQuery()` SQL, pagination (setFirstResult/setMaxResults), and count query
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 9.1, 9.2, 9.3, 9.4_

  - [x] 5.3 Implement query parsing (with i18n context), batch find, count, and extension points
    - Implement default `parseSpecification(String rawQuery)` — obtains `i18nProperties` from mapper, resolves `localeSuffix`, delegates to `QueryParser.parse(rawQuery, entityClass, i18nProperties, localeSuffix)`
    - Implement default `buildFinalSpecification(String rawQuery)` — combines user spec with `addRequiredQuery()` via AND
    - Implement default `findAllByIds(Collection<ID>)` — delegates to DAO, filters deleted, maps
    - Implement default `getCount(String rawQuery)` — builds spec, counts via DAO
    - Implement extension point defaults: `addRequiredQuery()` → null, `isDeleted()` → false, `getAdminOnlyFields()` → empty set
    - Implement `resolveI18nFilterField(String, Set<String>, String)` — resolves i18n field for nested and simple fields
    - _Requirements: 4.1, 4.2, 5.1, 5.16, 5.17, 6.1, 6.2, 7.1, 7.2, 7.3_

  - [x] 5.4 Implement I18n sort processing and permission masking
    - Implement default `processSort(Sort)` — resolves i18n field names using `getMapper().getI18nSupportedProperties()` and `LocaleContextHolder`
    - Implement `resolveI18nSortProperty` — handles both simple and dot-notation (resolve final segment)
    - Implement `resolveLocaleSuffix` — "RU" for Russian locale, "PL" otherwise
    - Validate that sort on collection-typed associations throws ForemenApiException(400)
    - Implement default `maskAdminOnlyFields(Object)` — null out admin-only fields for non-admin callers via reflection/SecurityContext check
    - _Requirements: 8.1, 8.2, 8.3, 10.1, 10.2, 10.3, 10.4, 18.1, 18.2, 18.3, 18.4_

- [x] 6. Implement AdminService interface with universal audit
  - [x] 6.1 Create AdminService interface with create, update operations and universal saveAudit
    - Create `com.foremen.service.AdminService<ServiceModel, ServiceExtendedModel, DaoModel, ID>` extending `ReadOnlyAdminService`
    - Declare abstract `getDao()` returning `AdminDao<DaoModel, ID>`
    - Declare abstract `getAuditLogDao()` returning `AuditLogDao`
    - Implement default `getWriteDao()` delegating to `getDao()`
    - Override default `getReadDao()` delegating to `getDao()`
    - Implement default `create(ServiceExtendedModel)` — map via `toCreateDaoModel`, save, flush, `saveAudit(entity, "CREATE")`, map back
    - Implement default `create(List<ServiceExtendedModel>)` — batch map, saveAll, flush, `saveAudit` each with "CREATE", map back
    - Implement default `update(ID, ServiceExtendedModel)` — findById (404), validateUpdate, updateFields, save, flush, `saveAudit(saved, "UPDATE")`, map back
    - Implement default `updateAll(List<ID>, ServiceExtendedModel)` — iterate update per ID
    - Implement default `updateSingleField(ID, V, BiConsumer)` — findById, apply setter, save, flush
    - Implement default `validateUpdate(DaoModel, ServiceExtendedModel)` — no-op default
    - Implement default `saveAudit(DaoModel, String operation)` — REAL implementation: extract entity ID via reflection, get entity class simple name, resolve current user from `SecurityContextHolder` (fallback "SYSTEM"), create `AuditLogEntity`, persist via `getAuditLogDao().save()`
    - Implement private `extractEntityId`, `findField`, `resolveCurrentUser` helper methods
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 12.1, 12.2, 12.3, 13.1, 13.2, 13.3, 13.4, 13.5, 15.1, 15.2, 15.3, 16.1, 16.2_

  - [x] 6.2 Implement delete operations (hard, soft, setPropertiesToNull)
    - Implement default `deleteById(ID)` — findById (404 if missing), `saveAudit(entity, "DELETE")`, deleteById, flush
    - Implement default `deleteAll(List<ID>)` — audit each via `saveAudit(entity, "DELETE")`, deleteAllById, flush
    - Implement default `softDelete(String field, Set<ID>)` — audit each via `saveAudit(entity, "SOFT_DELETE")`, CriteriaUpdate to set field=true where id in ids, executeUpdate, flush
    - Implement default `setPropertiesToNull(ID, Set<String>)` — validate field names exist via `fieldExistsOnEntity` (traverse class hierarchy), CriteriaUpdate to set fields=null where id=id, executeUpdate, flush
    - Implement `fieldExistsOnEntity` utility — traverse class hierarchy looking for declared field
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6, 14.7_

- [x] 7. Checkpoint - Ensure all unit tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Property-based tests
  - [x] 8.1 Write property test for query parser round-trip (Property 1)
    - **Property 1: Query Parser Structural Correctness (Round-Trip)**
    - Generate random query ASTs (Filter nodes with AND/OR operators — NO semicolons, nesting depth 0-3, operators from full 20-operator enum, alphanumeric fields/values)
    - Serialize AST to string using only AND/OR as combinators, parse via `QueryParser.parse()`, evaluate both specs against in-memory dataset, compare result sets
    - **Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 5.9, 5.10, 5.12, 5.13, 5.14, 5.15**

  - [x] 8.2 Write property test for AccessCriteria conjunction (Property 2)
    - **Property 2: AccessCriteria Always Conjunctive with User Query**
    - Generate random user query specs (including OR groups) and random access criteria specs, call `buildFinalSpecification`, verify no result violates access criteria (filter result with access criteria alone — same set)
    - **Validates: Requirements 5.16, 5.17**

  - [x] 8.3 Write property test for I18n sort resolution (Property 3)
    - **Property 3: I18n Sort Resolution**
    - Generate random sort properties (simple + dot-notation), random i18n property sets, random locales (RU and various non-RU)
    - Verify: i18n properties get correct suffix (RU/PL), non-i18n unchanged, direction preserved, nested paths resolve on final segment
    - **Validates: Requirements 3.6, 10.1, 10.2, 10.3, 10.4, 18.1, 18.2, 18.4**

  - [x] 8.4 Write property test for I18n filter field resolution (Property 4)
    - **Property 4: I18n Filter Field Resolution**
    - Generate random field names (plain and dot-notation), random i18n property sets, random locale suffixes
    - Call `resolveI18nFilterField`. Verify: i18n fields get suffix, non-i18n unchanged, nested paths resolve on final segment only, operator and value never modified
    - **Validates: Requirements 5.1 (i18n context)**

  - [x] 8.5 Write property test for permission field masking (Property 5)
    - **Property 5: Permission Field Masking**
    - Generate random test POJOs with known fields, random admin-only field subsets, random security contexts (admin/non-admin)
    - Verify: non-admin callers see null for admin-only fields, admin callers see all fields unchanged
    - **Validates: Requirements 8.2, 8.3**

  - [x] 8.6 Write property test for nested field path parsing (Property 6)
    - **Property 6: Nested Field Path Parsing with Automatic Join Detection**
    - Generate random dot-notation paths (1-4 segments, valid Java identifiers), mock Root/From that tracks `get()` and `join()` calls
    - Verify: collection attributes trigger join + distinct, associations trigger join, simple attributes trigger get, same JOIN reused for repeated references
    - **Validates: Requirements 17.1, 17.2, 17.3, 17.4, 17.5**

  - [x] 8.7 Write property test for universal audit (Property 7)
    - **Property 7: Universal Audit Records All Write Operations**
    - Generate random entities (various classes, various IDs) and random operations (CREATE, UPDATE, DELETE, SOFT_DELETE)
    - Call `saveAudit`, verify AuditLogEntity contains: correct entityClass (simple name), correct entityId, correct operation, non-null performedBy (or "SYSTEM"), performedAt within 1 second tolerance
    - **Validates: Requirements 15.1, 15.2, 15.3**

- [x] 9. Integration tests with Testcontainers
  - [x] 9.1 Write integration tests for read operations and query filtering
    - Set up Testcontainers PostgreSQL + Spring Boot test context with a sample entity implementing the service interfaces
    - Test `find` with query filter — persist entities, call `find(pageable, "field==value")`, verify filtered results
    - Test `find` with i18n filter — persist entities with i18n fields, query `name==Тест` with RU locale, verify `nameRU` is queried
    - Test `find` with paginated sort — sort by "name" with RU locale, verify ordered by `nameRU`
    - Test `findById`/`findByIdLocalized` — verify extended vs localized responses
    - Test `findAllByIds` batch — verify partial results
    - Test `isDeleted` filtering — subclass with soft-delete logic, verify deleted excluded
    - Test `addRequiredQuery` (access criteria always applied with AND)
    - _Requirements: 2.1–2.4, 3.1–3.6, 4.1, 4.2, 5.16, 5.17, 6.1, 6.2, 7.2, 7.3, 10.1–10.4_

  - [x] 9.2 Write integration tests for text search and numeric operators
    - Test case-insensitive contains (`~ct~`): persist "John", query `name~ct~john`, verify found
    - Test case-insensitive starts with (`~sw~`): persist "Warsaw", query `city~sw~war`, verify found
    - Test case-insensitive ends with (`~ew~`): persist "Poland", query `country~ew~land`, verify found
    - Test case-sensitive contains (`~CT~`): persist "ABCdef", query `code~CT~ABC`, verify found; query `code~CT~abc`, verify NOT found
    - Test case-sensitive starts with (`~SW~`) and ends with (`~EW~`)
    - Test legacy LIKE (`~~`) produces same result as `~ct~`
    - Test numeric `>`, `<`, `>=`, `<=` operators with integer and decimal values
    - _Requirements: 5.2, 5.3, 5.4_

  - [x] 9.3 Write integration tests for write operations and audit log
    - Test `create` + flush — verify ID generated, audit_log entry created with entityClass, entityId, operation="CREATE", performedBy, performedAt
    - Test `update` with `validateUpdate` hook — verify hook called before persistence, audit_log entry with operation="UPDATE"
    - Test `deleteById` — verify entity gone + audit_log entry with operation="DELETE"
    - Test `softDelete` via CriteriaUpdate — verify flag set + audit_log entries with operation="SOFT_DELETE"
    - Test `setPropertiesToNull` — persist entity, null specific fields, verify via findById
    - Query audit_log table directly after multiple operations, verify all operations recorded
    - _Requirements: 12.1–12.3, 13.1–13.5, 14.1–14.7, 15.1–15.3, 16.1, 16.2_

  - [x] 9.4 Write integration tests for nested field filtering and collection joins
    - Test nested field filtering: persist entities with nested associations, query `address.city==Warsaw`, verify filtered
    - Test collection join filtering: persist entities with collection associations, query `items.status==done`, verify auto-join + distinct results
    - Test deeply nested: `project.manager.name~ct~john` across multiple associations
    - Test `find` with view query — configure DAO with `getViewSelectQuery`, verify native SQL execution
    - _Requirements: 9.1–9.4, 17.1–17.6_

- [-] 10. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.
  - If build succeeds fully - prepare a commit. Include spec files and all spec related files

- [x] 11. Add audit entity snapshots (before/after JSON) and restructure changelog
  - [x] 11.1 Restructure Liquibase changelog to use external changeset files
    - Convert `database_files/changelog.xml` to a master file with only `<include>` references
    - Move existing changeset into `database_files/changesets/001-create-audit-log.xml`
    - Add `snapshot_before` (JSONB) and `snapshot_after` (JSONB) columns to audit_log table
    - _Requirements: 15.1, 15.2, 15.3_

  - [x] 11.2 Update AuditLogEntity with snapshot fields
    - Add `snapshotBefore` (String, JSONB) and `snapshotAfter` (String, JSONB) fields to AuditLogEntity
    - Use `@JdbcTypeCode(SqlTypes.JSON)` annotation for Hibernate 6+ JSONB mapping
    - _Requirements: 15.1, 15.2, 15.3_

  - [x] 11.3 Update AdminService.saveAudit to capture before/after snapshots
    - Change `saveAudit` signature to `saveAudit(DaoModel before, DaoModel after, String operation)`
    - Add `saveAuditWithSnapshot(String beforeSnapshot, DaoModel after, String operation)` for update flow
    - Implement `serializeEntity(DaoModel)` using Jackson ObjectMapper (with JavaTimeModule, FAIL_ON_EMPTY_BEANS disabled)
    - Update all CRUD methods: CREATE passes `(null, entity, "CREATE")`, UPDATE captures before-state pre-mutation then calls `saveAuditWithSnapshot`, DELETE passes `(entity, null, "DELETE")`, SOFT_DELETE passes `(entity, null, "SOFT_DELETE")`
    - Handle serialization failures gracefully (fallback JSON error message, don't crash the operation)
    - _Requirements: 15.1, 15.2, 15.3_

  - [x] 11.4 Add history query to AuditLogDao
    - Add `findByEntityClassAndEntityIdOrderByPerformedAtAsc(String entityClass, Long entityId)` method to AuditLogDao
    - This enables full change history reconstruction for any entity by class + ID
    - _Requirements: 15.1, 15.2, 15.3_

  - [x] 11.5 Update tests for new audit snapshot functionality
    - Update `UniversalAuditPropertyTest` to verify snapshot serialization (before/after populated correctly per operation type)
    - Update `AdminServiceIntegrationTest` to verify JSON snapshots in audit_log records
    - Update test SQL init script to include snapshot_before and snapshot_after columns
    - Verify: CREATE has null before + non-null after, UPDATE has both non-null, DELETE has non-null before + null after
    - _Requirements: 15.1, 15.2, 15.3_

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate 7 universal correctness properties from the design document
- Unit tests validate specific examples and edge cases
- Integration tests require Testcontainers (PostgreSQL) which is already configured in `build.gradle`
- The project uses Java 25 with sealed interfaces, records, and pattern matching in switch — leverage these features in the parser implementation
- `ServiceToDaoMapper` and `ReadOnlyAdminDao`/`AdminDao` are defined in previous specs (FOR-01-04, FOR-01-05) and assumed to exist
- **No semicolons in DSL** — only AND and OR are valid expression combinators; semicolons are rejected as invalid characters
- **QueryParser accepts i18n context** — `Set<String> i18nProperties` and `String localeSuffix` are passed to the parser so filter fields are resolved BEFORE spec building
- **Universal saveAudit** — the default implementation is a REAL working audit mechanism (not a no-op); it creates AuditLogEntity records for every write operation
- **20 operators total** — includes case-insensitive text search (~ct~, ~sw~, ~ew~), case-sensitive text search (~CT~, ~SW~, ~EW~), legacy ~~ alias, numeric (>, <, >=, <=), and the existing equality/date/set/null operators
- **Collection join detection is automatic** — `SpecificationBuilder.resolvePath` internally inspects metamodel attributes and uses JOIN for collections without any external operator distinction

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3"] },
    { "id": 1, "tasks": ["2.1", "2.2"] },
    { "id": 2, "tasks": ["2.3", "2.4", "3.1"] },
    { "id": 3, "tasks": ["3.2"] },
    { "id": 4, "tasks": ["5.1", "5.2"] },
    { "id": 5, "tasks": ["5.3", "5.4"] },
    { "id": 6, "tasks": ["6.1"] },
    { "id": 7, "tasks": ["6.2"] },
    { "id": 8, "tasks": ["8.1", "8.2", "8.3", "8.4", "8.5", "8.6", "8.7"] },
    { "id": 9, "tasks": ["9.1", "9.2", "9.3", "9.4"] },
    { "id": 10, "tasks": ["11.1", "11.2", "11.3", "11.4", "11.5"] }
  ]
}
```
