# Requirements Document

## Introduction

This specification defines the generic CRUD controller layer for the Foremen project. The controller layer sits above the service layer (AdminService / ReadOnlyAdminService) and provides reusable REST API endpoints for entity management. It follows the pattern established in the tickets reference project, adapted for Spring Boot 4.0.0 with Jakarta packages and Java 25.

Two controller interfaces are defined:
- **AdminController** — full CRUD REST interface for admin-facing entity management (create, read, update, delete, bulk operations, audit)
- **AdminReadOnlyController** — simplified read-only REST interface for entities that should not be modified via API (view-backed/reference entities)

Access control for end-user operations is delegated to a dynamic ACL layer: `addRequiredQuery()` in the service layer filters visible entities, a future ACL interceptor handles operation-level permission checks, and `maskAdminOnlyFields()` provides field-level access control. This eliminates the need for a separate user-facing controller.

One mapper interface supports the controller layer:
- **ControllerToServiceMapper** — maps between request/response DTOs and service-layer models for full CRUD controllers

A locale interceptor resolves the request language header into Spring's `LocaleContextHolder`.

## Glossary

- **AdminController**: Generic interface with default methods providing full CRUD REST endpoints for admin-facing entity management
- **AdminReadOnlyController**: Generic interface with default methods providing read-only REST endpoints for admin-facing entity access
- **ControllerToServiceMapper**: MapStruct-based generic mapper interface that converts between REST request/response models and service-layer models
- **ForemenLocaleInterceptor**: Spring HandlerInterceptor that reads a locale header from the HTTP request and sets the locale via LocaleContextHolder
- **CreateRequestModel**: DTO representing the HTTP request body for entity creation
- **CreateResponseModel**: DTO representing the HTTP response body after entity creation
- **UpdateRequestModel**: DTO representing the HTTP request body for entity updates
- **UpdateResponseModel**: DTO representing the HTTP response body after entity update
- **DtoModel**: Localized DTO returned to clients from paginated list endpoints
- **DtoExtendedModel**: Full (all locales) DTO returned to admin clients from get-by-id and extended-list endpoints
- **ServiceModel**: Localized entity representation at the service layer
- **ServiceExtendedModel**: Full (all locales) entity representation at the service layer
- **DaoModel**: JPA entity at the data access layer
- **Pageable**: Spring Data pagination and sorting abstraction passed via request parameters
- **Page**: Spring Data paginated result container with content, total count, and page metadata
- **ForemenApiException**: Application-level exception carrying HTTP status and i18n message code
- **LocaleContextHolder**: Spring utility that holds the current request locale in a ThreadLocal

## Requirements

### Requirement 1: AdminController Interface Definition

**User Story:** As a developer, I want a generic admin controller interface with default CRUD endpoint implementations, so that I can expose full REST APIs for any entity by simply implementing the interface and providing the mapper and service.

#### Acceptance Criteria

1. THE AdminController SHALL be a generic interface parameterized with ServiceModel, ServiceExtendedModel, DtoModel, DtoExtendedModel, DaoModel, ID, CreateRequestModel, CreateResponseModel, UpdateRequestModel, and UpdateResponseModel types
2. THE AdminController SHALL declare a `getMapper()` method returning the ControllerToServiceMapper for the entity
3. THE AdminController SHALL declare a `getService()` method returning the AdminService for the entity

### Requirement 2: Create Endpoint

**User Story:** As an admin user, I want to create entities via a POST endpoint, so that new data can be added to the system through the REST API.

#### Acceptance Criteria

1. WHEN a POST request is received at the base path with a valid CreateRequestModel body, THE AdminController SHALL map the request to ServiceExtendedModel, invoke the service create method, and return the result mapped to CreateResponseModel
2. WHEN a POST request is received at the base path with an invalid request body, THE AdminController SHALL trigger Jakarta Bean Validation and delegate error handling to ForemenControllerAdvice
3. THE AdminController SHALL annotate the create request body parameter with `@Valid` to enforce Jakarta Bean Validation constraints

### Requirement 3: Bulk Create Endpoint

**User Story:** As an admin user, I want to create multiple entities in a single request, so that bulk data import operations are efficient.

#### Acceptance Criteria

1. WHEN a POST request is received at `/bulk` with a list of CreateRequestModel objects, THE AdminController SHALL map each request to ServiceExtendedModel, invoke the service bulk create method, and return a list of CreateResponseModel objects
2. WHEN any item in the bulk create request fails validation, THE AdminController SHALL trigger Jakarta Bean Validation for each item in the list

### Requirement 4: Update Endpoint

**User Story:** As an admin user, I want to update an existing entity via a PUT endpoint, so that entity data can be modified through the REST API.

#### Acceptance Criteria

1. WHEN a PUT request is received at `/{id}` with a valid UpdateRequestModel body, THE AdminController SHALL map the request to ServiceExtendedModel, invoke the service update method with the path ID, and return the result mapped to UpdateResponseModel
2. WHEN a PUT request is received with an ID that does not exist, THE AdminController SHALL propagate the ForemenApiException with HTTP 404 status from the service layer
3. THE AdminController SHALL annotate the update request body parameter with `@Valid` to enforce Jakarta Bean Validation constraints

### Requirement 5: Paginated Find Endpoint

**User Story:** As an admin user, I want to list entities with pagination and optional query filtering, so that I can browse and search large datasets efficiently.

#### Acceptance Criteria

1. WHEN a GET request is received at the base path with pagination parameters, THE AdminController SHALL invoke the service find method and return a Page of DtoModel objects
2. WHEN a GET request is received at the base path with a `query` request parameter, THE AdminController SHALL pass the query string to the service find method for specification-based filtering
3. WHEN the query parameter is null or blank, THE AdminController SHALL invoke the service find method without a query filter
4. THE AdminController SHALL provide a default `addCustomQueryCondition(String query)` method that returns the query unchanged, allowing subclasses to append additional filters

### Requirement 6: Count Endpoint

**User Story:** As an admin user, I want to retrieve the total count of entities matching a query, so that I can display totals in the UI without loading full entity data.

#### Acceptance Criteria

1. WHEN a GET request is received at `/count` with an optional `query` parameter, THE AdminController SHALL invoke the service getCount method and return the count as a Long value
2. WHEN the query parameter is null or blank, THE AdminController SHALL return the total count of all entities

### Requirement 7: Extended Find Endpoint

**User Story:** As an admin user, I want to list entities with full i18n data (all locale variants), so that I can manage translations through the admin interface.

#### Acceptance Criteria

1. WHEN a GET request is received at `/extended` with pagination parameters, THE AdminController SHALL invoke the service findExtended method and return a Page of DtoExtendedModel objects
2. WHEN a GET request is received at `/extended` with a `query` parameter, THE AdminController SHALL pass the query string to the service findExtended method for specification-based filtering

### Requirement 8: Get By ID Endpoint

**User Story:** As an admin user, I want to retrieve a single entity with full detail by its ID, so that I can view and edit all entity fields including i18n variants.

#### Acceptance Criteria

1. WHEN a GET request is received at `/{id}`, THE AdminController SHALL invoke the service findById method and return the result mapped to DtoExtendedModel
2. WHEN a GET request is received with an ID that does not exist, THE AdminController SHALL propagate the ForemenApiException with HTTP 404 status from the service layer

### Requirement 9: Audit History Endpoint

**User Story:** As an admin user, I want to view the audit history of an entity, so that I can track changes and identify who modified data and when.

#### Acceptance Criteria

1. WHEN a GET request is received at `/audit/{id}` with pagination parameters, THE AdminController SHALL invoke the service getEntityAudit method and return a Page of audit records

### Requirement 10: Delete Endpoint

**User Story:** As an admin user, I want to delete an entity by its ID, so that unwanted data can be removed from the system.

#### Acceptance Criteria

1. WHEN a DELETE request is received at `/{id}`, THE AdminController SHALL invoke the service deleteById method to remove the entity
2. WHEN a DELETE request is received with an ID that does not exist, THE AdminController SHALL propagate the ForemenApiException with HTTP 404 status from the service layer

### Requirement 11: Set Properties To Null Endpoint

**User Story:** As an admin user, I want to null out specific properties of an entity, so that I can clear optional fields without providing a full update payload.

#### Acceptance Criteria

1. WHEN a DELETE request is received at `/{id}/property` with a `properties` request parameter containing field names, THE AdminController SHALL invoke the service setPropertiesToNull method with the entity ID and the set of property names
2. IF the properties parameter contains a field name that does not exist on the entity, THEN THE AdminController SHALL propagate the ForemenApiException with HTTP 400 status from the service layer

### Requirement 12: I18n Properties Endpoint

**User Story:** As an admin user, I want to discover which entity fields support i18n, so that the UI can render locale-specific editing controls for those fields.

#### Acceptance Criteria

1. WHEN a GET request is received at `/i18n`, THE AdminController SHALL return the collection of i18n-supported property names from the service mapper

### Requirement 13: AdminReadOnlyController Interface Definition

**User Story:** As a developer, I want a generic read-only admin controller interface, so that I can expose read-only REST APIs for reference-data entities without CRUD mutation endpoints.

#### Acceptance Criteria

1. THE AdminReadOnlyController SHALL be a generic interface parameterized with ServiceModel, ServiceExtendedModel, DaoModel, and ID types
2. THE AdminReadOnlyController SHALL declare a `getService()` method returning the ReadOnlyAdminService for the entity
3. WHEN a GET request is received at the base path with pagination and optional query parameters, THE AdminReadOnlyController SHALL invoke the service find method and return a Page of ServiceModel objects
4. WHEN a GET request is received at `/{id}`, THE AdminReadOnlyController SHALL invoke the service findByIdLocalized method and return the localized ServiceModel

### Requirement 14: ControllerToServiceMapper Interface

**User Story:** As a developer, I want a generic mapper interface for admin controllers, so that I can define type-safe mappings between REST request/response DTOs and service-layer models using MapStruct.

#### Acceptance Criteria

1. THE ControllerToServiceMapper SHALL be a generic interface parameterized with ServiceModel, ServiceExtendedModel, DtoModel, DtoExtendedModel, CreateRequestModel, CreateResponseModel, UpdateRequestModel, and UpdateResponseModel types
2. THE ControllerToServiceMapper SHALL declare a `toServiceExtendedModel(CreateRequestModel)` method that maps create requests to service extended models, ignoring the `id` field on the target
3. THE ControllerToServiceMapper SHALL declare a `toUpdateServiceExtendedModel(UpdateRequestModel)` method that maps update requests to service extended models
4. THE ControllerToServiceMapper SHALL declare a `toDto(ServiceModel)` method that maps service models to localized DTOs
5. THE ControllerToServiceMapper SHALL declare a `toExtendedDto(ServiceExtendedModel)` method that maps service extended models to full DTOs
6. THE ControllerToServiceMapper SHALL declare a `toCreateResponse(ServiceExtendedModel)` method that maps persisted service models to create response DTOs
7. THE ControllerToServiceMapper SHALL declare a `toUpdateResponse(ServiceExtendedModel)` method that maps persisted service models to update response DTOs

### Requirement 15: ForemenLocaleInterceptor

**User Story:** As a developer, I want an HTTP request interceptor that resolves the locale from a request header, so that service-layer i18n operations use the correct locale without explicit locale passing in every controller method.

#### Acceptance Criteria

1. WHEN an HTTP request contains the `foremen-language` header with a valid locale value, THE ForemenLocaleInterceptor SHALL set the resolved Locale in Spring's LocaleContextHolder before the controller method is invoked
2. WHEN an HTTP request does not contain the `foremen-language` header, THE ForemenLocaleInterceptor SHALL leave LocaleContextHolder unchanged (default locale applies)
3. WHEN the request processing completes, THE ForemenLocaleInterceptor SHALL clear the locale from LocaleContextHolder to prevent locale leaking between requests
4. THE ForemenLocaleInterceptor SHALL implement Spring's HandlerInterceptor interface
5. THE ForemenLocaleInterceptor SHALL be registered as a Spring component and configured via WebMvcConfigurer to apply to all request paths
