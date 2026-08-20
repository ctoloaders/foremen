# Implementation Plan: Generic CRUD Controller Layer (FOR-01-07-crud-controller)

## Overview

Implement the generic CRUD controller layer consisting of `AdminController` and `AdminReadOnlyController` interfaces with default methods, `ForemenLocaleInterceptor` for request locale resolution, and `ForemenWebMvcConfig` for interceptor registration. All code targets Java 25, Spring Boot 4.0.0, Jakarta packages, and uses jqwik 1.9.2 for property-based testing. The controller layer delegates entirely to the service layer (FOR-01-06) and existing `ForemenControllerAdvice` for error handling.

## Tasks

- [x] 1. Implement locale interceptor and web configuration
  - [x] 1.1 Create ForemenLocaleInterceptor
    - Create `com.foremen.config.i18n.ForemenLocaleInterceptor` implementing `HandlerInterceptor`
    - Define `LOCALE_HEADER = "foremen-language"` constant
    - Implement `preHandle`: read header, if non-null and non-blank, parse via `Locale.forLanguageTag()` and set via `LocaleContextHolder.setLocale()`
    - Implement `afterCompletion`: call `LocaleContextHolder.resetLocaleContext()` to prevent locale leaking between requests
    - Annotate with `@Component`
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.5_

  - [x] 1.2 Create ForemenWebMvcConfig
    - Create `com.foremen.config.web.ForemenWebMvcConfig` implementing `WebMvcConfigurer`
    - Annotate with `@Configuration`
    - Inject `ForemenLocaleInterceptor` via constructor
    - Override `addInterceptors(InterceptorRegistry)` to register the interceptor with `addPathPatterns("/**")`
    - _Requirements: 15.5_

  - [x] 1.3 Write unit tests for ForemenLocaleInterceptor
    - Test `preHandle` with "foremen-language: ru" → `LocaleContextHolder.getLocale()` returns Russian locale
    - Test `preHandle` with "foremen-language: pl" → locale set to Polish
    - Test `preHandle` without header → locale unchanged (default)
    - Test `preHandle` with blank header → locale unchanged
    - Test `afterCompletion` resets locale context to default
    - _Requirements: 15.1, 15.2, 15.3_

- [x] 2. Implement AdminController interface
  - [x] 2.1 Create AdminController interface with type parameters and abstract methods
    - Create `com.foremen.controller.AdminController` interface with 10 type parameters: `ServiceModel, ServiceExtendedModel, DtoModel, DtoExtendedModel, DaoModel, ID, CreateRequestModel, CreateResponseModel, UpdateRequestModel, UpdateResponseModel`
    - Declare `getMapper()` returning `ControllerToServiceMapper<SM, SEM, Dto, DtoExt, CReq, CRes, UReq, URes>`
    - Declare `getService()` returning `AdminService<SM, SEM, DM, ID>`
    - Implement `addCustomQueryCondition(String query)` default method returning query unchanged
    - _Requirements: 1.1, 1.2, 1.3_

  - [x] 2.2 Implement create and bulk create default methods
    - Implement `create(@Valid @RequestBody CreateRequestModel)` with `@PostMapping`: map via `getMapper().toServiceExtendedModel()`, call `getService().create()`, map result via `getMapper().toCreateResponse()`, return `ResponseEntity.ok()`
    - Implement `createBulk(@Valid @RequestBody List<CreateRequestModel>)` with `@PostMapping("/bulk")`: stream-map each to service model, call `getService().create(list)`, stream-map results to response, return `ResponseEntity.ok()`
    - _Requirements: 2.1, 2.2, 2.3, 3.1, 3.2_

  - [x] 2.3 Implement update default method
    - Implement `update(@PathVariable ID id, @Valid @RequestBody UpdateRequestModel)` with `@PutMapping("/{id}")`: map via `getMapper().toUpdateServiceExtendedModel()`, call `getService().update(id, model)`, map result via `getMapper().toUpdateResponse()`, return `ResponseEntity.ok()`
    - _Requirements: 4.1, 4.2, 4.3_

  - [x] 2.4 Implement find, findExtended, findById, and getCount default methods
    - Implement `find(Pageable, @RequestParam query)` with `@GetMapping`: call `addCustomQueryCondition(query)`, call `getService().find(pageable, processedQuery)`, map page via `page.map(getMapper()::toDto)`, return `ResponseEntity.ok()`
    - Implement `findExtended(Pageable, @RequestParam query)` with `@GetMapping("/extended")`: same pattern with `getService().findExtended()` and `getMapper()::toExtendedDto`
    - Implement `findById(@PathVariable ID)` with `@GetMapping("/{id}")`: call `getService().findById(id)`, map via `getMapper().toExtendedDto()`, return `ResponseEntity.ok()`
    - Implement `getCount(@RequestParam query)` with `@GetMapping("/count")`: call `addCustomQueryCondition(query)`, call `getService().getCount(processedQuery)`, return `ResponseEntity.ok(count)`
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 6.1, 6.2, 7.1, 7.2, 8.1, 8.2_

  - [x] 2.5 Implement audit, delete, setPropertiesToNull, and i18n default methods
    - Implement `getAudit(@PathVariable ID)` with `@GetMapping("/audit/{id}")`: call `getService().getAuditLogDao().findByEntityClassAndEntityIdOrderByPerformedAtAsc(...)`, return list
    - Implement `deleteById(@PathVariable ID)` with `@DeleteMapping("/{id}")`: call `getService().deleteById(id)`, return `ResponseEntity.ok().build()`
    - Implement `setPropertiesToNull(@PathVariable ID, @RequestParam Set<String> properties)` with `@DeleteMapping("/{id}/property")`: call `getService().setPropertiesToNull(id, properties)`, return `ResponseEntity.ok().build()`
    - Implement `getI18nProperties()` with `@GetMapping("/i18n")`: call `getService().getMapper().getI18nSupportedProperties()`, return `ResponseEntity.ok()`
    - _Requirements: 9.1, 10.1, 10.2, 11.1, 11.2, 12.1_

- [x] 3. Implement AdminReadOnlyController interface
  - [x] 3.1 Create AdminReadOnlyController interface
    - Create `com.foremen.controller.AdminReadOnlyController<ServiceModel, ServiceExtendedModel, DaoModel, ID>` interface
    - Declare `getService()` returning `ReadOnlyAdminService<SM, SEM, DM, ID>`
    - Implement `find(Pageable, @RequestParam query)` with `@GetMapping`: call `getService().find(pageable, query)`, return `ResponseEntity.ok(page)`
    - Implement `findById(@PathVariable ID)` with `@GetMapping("/{id}")`: call `getService().findByIdLocalized(id)`, return `ResponseEntity.ok(model)`
    - _Requirements: 13.1, 13.2, 13.3, 13.4_

- [x] 4. Checkpoint - Ensure all source files compile
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Unit tests for controller default methods
  - [x] 5.1 Write unit tests for AdminController default methods
    - Create a test implementation of `AdminController` with mocked service and mapper
    - Test `create` maps request → calls service.create → maps response
    - Test `createBulk` maps each request → calls service.create(list) → maps each response
    - Test `update` maps request → calls service.update(id, model) → maps response
    - Test `find` with null query → service.find(pageable, null)
    - Test `find` with query → addCustomQueryCondition applied → service.find(pageable, processedQuery)
    - Test `findExtended` calls service.findExtended and maps with toExtendedDto
    - Test `findById` calls service.findById and maps with toExtendedDto
    - Test `getCount` with null query → service.getCount(null)
    - Test `getAudit` calls auditLogDao.findByEntityClassAndEntityId...
    - Test `deleteById` calls service.deleteById
    - Test `setPropertiesToNull` calls service.setPropertiesToNull with ID and property set
    - Test `getI18nProperties` calls service.getMapper().getI18nSupportedProperties()
    - Test `addCustomQueryCondition` returns query unchanged by default
    - _Requirements: 2.1, 3.1, 4.1, 5.1, 5.2, 5.4, 6.1, 7.1, 8.1, 9.1, 10.1, 11.1, 12.1_

  - [x] 5.2 Write unit tests for AdminReadOnlyController default methods
    - Create a test implementation with mocked service
    - Test `find` delegates to service.find(pageable, query)
    - Test `findById` delegates to service.findByIdLocalized(id)
    - _Requirements: 13.3, 13.4_

  - [x] 5.3 Write unit tests for error propagation
    - Test `update` with non-existent ID → ForemenApiException(404) propagates from service
    - Test `deleteById` with non-existent ID → ForemenApiException(404) propagates from service
    - Test `findById` with non-existent ID → ForemenApiException(404) propagates from service
    - Test `setPropertiesToNull` with invalid field → ForemenApiException(400) propagates from service
    - _Requirements: 4.2, 8.2, 10.2, 11.2_

- [x] 6. Property-based tests
  - [x] 6.1 Write property test for create flow data integrity (Property 1)
    - **Property 1: Create Flow Data Integrity**
    - Generate random test model instances (random string fields, random integer fields). Mock service to echo input with generated ID. Call `create` default method. Verify response contains all request fields plus non-null ID.
    - **Validates: Requirements 2.1, 3.1**

  - [x] 6.2 Write property test for pagination structure preservation (Property 2)
    - **Property 2: Pagination Structure Preservation**
    - Generate random Page objects (random totalElements 0–1000, random page number, random size 1–100, random content list). Apply `page.map(mapper::toDto)` where mapper is an identity-like mock. Verify totalElements, number, size, and content.size() all match originals, and each element is the mapped result at the same index.
    - **Validates: Requirements 5.1, 5.2, 7.1, 7.2, 13.3**

  - [x] 6.3 Write property test for query passthrough to service (Property 3)
    - **Property 3: Query Passthrough to Service**
    - Generate random query strings (including null, empty, whitespace, alphanumeric, query DSL patterns). Call controller find/findExtended/getCount. Verify service receives exact same string when addCustomQueryCondition is default (passthrough).
    - **Validates: Requirements 5.2, 5.4, 6.1, 7.2**

  - [x] 6.4 Write property test for locale interceptor round-trip (Property 4)
    - **Property 4: Locale Interceptor Round-Trip**
    - Generate random locale tags ("ru", "pl", "en", "de", "fr", "uk", "ja", etc.). Set header on mock request, call preHandle, verify `LocaleContextHolder.getLocale()` matches. Then call afterCompletion, verify locale is reset to default.
    - **Validates: Requirements 15.1, 15.3**

  - [x] 6.5 Write property test for update path ID preservation (Property 5)
    - **Property 5: Update Flow Preserves Path ID**
    - Generate random IDs (positive longs). Generate random update request models. Mock service.update to capture arguments. Call controller update. Verify captured ID matches path variable, never from request body.
    - **Validates: Requirements 4.1**

- [x] 7. Checkpoint - Ensure all unit and property tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Integration tests with MockMvc
  - [x] 8.1 Write integration tests for AdminController endpoints
    - Set up Spring Boot test context with a concrete test controller implementing `AdminController`, backed by an in-memory test service
    - Test POST with valid JSON → 200 + CreateResponse
    - Test POST with invalid body (missing required field) → 400 + field errors from ControllerAdvice
    - Test POST /bulk with list → 200 + list of responses
    - Test PUT /{id} → 200 + UpdateResponse
    - Test PUT /{nonExistentId} → 404 + ErrorResponse
    - Test GET /?page=0&size=10 → 200 + Page
    - Test GET /?query=name==Test → 200 + filtered Page
    - Test GET /extended?page=0&size=10 → 200 + Page with extended models
    - Test GET /{id} → 200 + DtoExtendedModel
    - Test GET /{nonExistentId} → 404
    - Test GET /count → 200 + number
    - Test GET /count?query=status==active → 200 + filtered count
    - Test GET /audit/{id} → 200 + list of audit records
    - Test DELETE /{id} → 200
    - Test DELETE /{nonExistentId} → 404
    - Test DELETE /{id}/property?properties=field1,field2 → 200
    - Test GET /i18n → 200 + collection of property names
    - _Requirements: 2.1, 2.2, 3.1, 3.2, 4.1, 4.2, 5.1, 5.2, 6.1, 7.1, 8.1, 8.2, 9.1, 10.1, 10.2, 11.1, 11.2, 12.1_

  - [x] 8.2 Write integration tests for AdminReadOnlyController endpoints
    - Set up test controller implementing `AdminReadOnlyController`
    - Test AdminReadOnlyController GET / → 200 + Page of ServiceModel
    - Test AdminReadOnlyController GET /{id} → 200 + localized ServiceModel
    - _Requirements: 13.3, 13.4_

  - [x] 8.3 Write integration tests for locale interceptor in HTTP context
    - Test GET with `foremen-language: ru` header → locale-resolved response (service receives RU locale context)
    - Test GET without header → default locale applies
    - Test multiple sequential requests with different locales → no leaking between requests
    - _Requirements: 15.1, 15.2, 15.3_

- [x] 9. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate 5 universal correctness properties from the design document
- Unit tests validate specific examples and edge cases
- Integration tests use MockMvc with Spring Boot test context — no Testcontainers needed since the controller layer is pure delegation
- The project uses Java 25 with sealed interfaces, records, and pattern matching — leverage these features where appropriate
- `AdminService`, `ReadOnlyAdminService`, `ControllerToServiceMapper`, and `ForemenControllerAdvice` are defined in previous specs (FOR-01-04, FOR-01-06) and assumed to exist
- The controller interfaces use NO Spring annotations on the interface itself (`@RestController`, `@RequestMapping` go on concrete classes only) — only method-level annotations (`@GetMapping`, `@PostMapping`, etc.) on default methods
- `addCustomQueryCondition` is the only extension point hook — it allows concrete controllers to append tenant isolation or status filters without overriding entire find methods
- Error handling is entirely delegated to `ForemenControllerAdvice` — the controller layer adds no new exception types
- Access control for end-user operations is delegated to a dynamic ACL layer (`addRequiredQuery()`, ACL interceptor, `maskAdminOnlyFields()`) — no separate user-facing controller is needed

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2"] },
    { "id": 1, "tasks": ["1.3", "2.1"] },
    { "id": 2, "tasks": ["2.2", "2.3", "2.4", "2.5"] },
    { "id": 3, "tasks": ["3.1"] },
    { "id": 4, "tasks": ["5.1", "5.2", "5.3"] },
    { "id": 5, "tasks": ["6.1", "6.2", "6.3", "6.4", "6.5"] },
    { "id": 6, "tasks": ["8.1", "8.2", "8.3"] }
  ]
}
```
