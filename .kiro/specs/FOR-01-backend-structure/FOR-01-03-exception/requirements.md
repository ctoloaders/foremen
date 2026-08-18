# Requirements Document

## Introduction

This specification defines the unified exception handling system for the Foremen backend. The system provides: a custom exception hierarchy with HTTP status and i18n message code, a centralized `@RestControllerAdvice` for producing uniform JSON error responses, internationalization of messages via `ResourceBundleMessageSource`, and structured error logging with request context.

The pattern is taken from the `tickets/api-events` project (`EventsApiException` + `ApiControllerAdvice`) and adapted for Spring Boot 4, Jakarta EE, Java 25, and the RFC 7807 (`ProblemDetail`) standard.

## Glossary

- **ForemenApiException**: Custom RuntimeException for the Foremen application, containing an HTTP status and an i18n message code with parameters.
- **ErrorResponse**: A DTO class (record) representing a structured error response in a format compatible with RFC 7807 (ProblemDetail).
- **ControllerAdvice**: A class annotated with `@RestControllerAdvice` that intercepts exceptions from all controllers and produces uniform HTTP responses.
- **MessageResolver**: A component based on `ResourceBundleMessageSource` that resolves i18n message codes into localized strings.
- **RequestContext**: HTTP request metadata (method, URI, username) extracted for logging purposes.
- **ValidationError**: An input validation error that occurs when `@Valid` / Bean Validation annotations on a DTO are violated.
- **SecurityContext**: The Spring Security context (`SecurityContextHolder`) used to obtain information about the current user.

## Requirements

### Requirement 1: Custom ForemenApiException

**User Story:** As a developer, I want a custom exception class with HTTP status and i18n message code, so that any service layer can throw domain-specific errors that are automatically translated into proper API responses.

#### Acceptance Criteria

1. THE ForemenApiException SHALL extend `RuntimeException`.
2. THE ForemenApiException SHALL contain a field `HttpStatusCode` (HTTP response status).
3. THE ForemenApiException SHALL contain a field `messageCode` of type `String` (i18n message code).
4. THE ForemenApiException SHALL contain a field `messageParams` of type `Object[]` (parameters for substitution into the message template).
5. THE ForemenApiException SHALL reside in the package `com.foremen.exception`.
6. THE ForemenApiException SHALL use Lombok annotations (`@Getter`) for getter generation.
7. WHEN ForemenApiException is created without message parameters, THE ForemenApiException SHALL accept an empty `messageParams` array by default.

### Requirement 2: Error Response Structure (ErrorResponse)

**User Story:** As a frontend developer, I want a consistent error response structure with timestamp, path, status, and message, so that I can uniformly parse and display errors in the UI.

#### Acceptance Criteria

1. THE ErrorResponse SHALL be implemented as a Java `record` in the package `com.foremen.exception.dto`.
2. THE ErrorResponse SHALL contain a field `timestamp` of type `Instant` (the moment the error occurred).
3. THE ErrorResponse SHALL contain a field `status` of type `int` (numeric HTTP code).
4. THE ErrorResponse SHALL contain a field `error` of type `String` (standard HTTP status name, e.g. "Bad Request").
5. THE ErrorResponse SHALL contain a field `message` of type `String` (localized error description).
6. THE ErrorResponse SHALL contain a field `path` of type `String` (URI of the request that caused the error).
7. WHEN the error is related to field validation, THE ErrorResponse SHALL contain a field `fieldErrors` of type `Map<String, String>` (field → error message), allowing `null` for non-validation errors.
8. THE ErrorResponse SHALL be serialized to JSON using standard Spring Boot Jackson mapping.

### Requirement 3: Centralized Exception Handler (ControllerAdvice)

**User Story:** As a developer, I want a single centralized exception handler, so that all REST endpoints return uniform error responses without duplicating error-handling logic in each controller.

#### Acceptance Criteria

1. THE ControllerAdvice SHALL be annotated with `@RestControllerAdvice` and reside in the package `com.foremen.controller.advice`.
2. WHEN ForemenApiException is thrown, THE ControllerAdvice SHALL return a `ResponseEntity<ErrorResponse>` with the status from the exception and a localized message.
3. WHEN `MethodArgumentNotValidException` is thrown, THE ControllerAdvice SHALL return an ErrorResponse with status 400 and a populated `fieldErrors` field.
4. WHEN `DataIntegrityViolationException` is thrown, THE ControllerAdvice SHALL return an ErrorResponse with status 409 (Conflict) and an i18n message with code `error.data.integrity`.
5. WHEN `AccessDeniedException` is thrown, THE ControllerAdvice SHALL return an ErrorResponse with status 403 and an i18n message with code `error.access.denied`.
6. WHEN any uncaught `RuntimeException` is thrown, THE ControllerAdvice SHALL return an ErrorResponse with status 500 and an i18n message with code `error.internal`.
7. THE ControllerAdvice SHALL NOT expose stack traces or internal exception messages in the HTTP response for unexpected RuntimeExceptions.
8. THE ControllerAdvice SHALL populate the `path` field in ErrorResponse from the current HTTP request URI.
9. THE ControllerAdvice SHALL populate the `timestamp` field with `Instant.now()` at the moment the response is formed.

### Requirement 4: Error Message Internationalization

**User Story:** As a product owner, I want error messages to be localized based on the request locale, so that users see errors in their preferred language.

#### Acceptance Criteria

1. THE MessageResolver SHALL use `ResourceBundleMessageSource` with the base name `messages`.
2. THE MessageResolver SHALL resolve the locale from the `Accept-Language` HTTP header via the standard Spring `LocaleResolver`; WHEN no `Accept-Language` header is provided, THE MessageResolver SHALL fall back to the PL (Polish) locale as the default.
3. WHEN a message code is not found in the bundle, THE MessageResolver SHALL return the message code itself as a fallback (rather than throwing an exception).
4. THE MessageResolver SHALL support parameterized messages (substitution of `{0}`, `{1}`, etc. from `messageParams`).
5. THE project SHALL contain files `messages.properties` (default/PL) and `messages_ru.properties` (RU) in `src/main/resources/`.
6. THE message files SHALL contain at minimum the codes: `error.data.integrity`, `error.access.denied`, `error.internal`, `error.validation`.
7. THE ResourceBundleMessageSource SHALL be configured with UTF-8 default encoding.

### Requirement 5: Structured Error Logging

**User Story:** As a DevOps engineer, I want all errors logged with request context (method, URI, user), so that I can trace and debug issues in production without reproducing them.

#### Acceptance Criteria

1. WHEN any exception is caught by ControllerAdvice, THE ControllerAdvice SHALL log the error at the `ERROR` level.
2. THE log entry SHALL contain: the exception message, the HTTP method of the request, the request URI, the authenticated username (or "anonymous"), and the HTTP response status.
3. WHEN the exception is an unexpected RuntimeException, THE ControllerAdvice SHALL log the full stack trace.
4. WHEN the exception is a ForemenApiException or an expected validation error, THE ControllerAdvice SHALL log only the message without the stack trace.
5. THE ControllerAdvice SHALL extract the username from `SecurityContextHolder` (following the same pattern as in `JpaAuditingConfig`).

### Requirement 6: Bean Validation Error Handling

**User Story:** As a frontend developer, I want validation errors returned as a map of field names to error messages, so that I can display inline validation errors next to corresponding form fields.

#### Acceptance Criteria

1. WHEN `MethodArgumentNotValidException` contains field errors, THE ControllerAdvice SHALL include each error in `fieldErrors` as a pair "field name" → "error message".
2. WHEN `MethodArgumentNotValidException` contains global errors (object-level errors), THE ControllerAdvice SHALL include them in `fieldErrors` with the key equal to the object name.
3. THE `message` field in ErrorResponse for validation errors SHALL contain an i18n message with code `error.validation`.
4. WHEN `ConstraintViolationException` is thrown (parameter-level validation), THE ControllerAdvice SHALL return an ErrorResponse with status 400 and a list of violations in `fieldErrors`.

### Requirement 7: Exception Hierarchy Extensibility

**User Story:** As a developer, I want to easily create domain-specific exception subclasses, so that different modules (projects, tasks, finances) can have their own typed exceptions without modifying the base handler.

#### Acceptance Criteria

1. THE ForemenApiException SHALL support inheritance — any subclass is automatically handled by the same handler in ControllerAdvice.
2. WHEN a subclass of ForemenApiException does not override behavior, THE ControllerAdvice SHALL use the `messageCode` and `status` from the subclass without additional configuration.
3. THE project SHALL contain an example subclass `ForemenValidationException` (for domain-specific validations) in the package `com.foremen.exception`, inheriting from ForemenApiException with a preset status of `400 Bad Request`.

### Requirement 8: Correctness and Properties

**User Story:** As a developer, I want formal correctness guarantees for the exception handling system, so that I can rely on consistent behavior regardless of input combinations.

#### Acceptance Criteria

1. FOR ALL instances of ForemenApiException, THE ErrorResponse SHALL contain the same HTTP status as the `status` field of the exception (invariant: status is preserved).
2. FOR ALL handled exceptions, THE ErrorResponse SHALL contain a non-empty `message` field (invariant: message is never null/empty).
3. FOR ALL handled exceptions, THE ErrorResponse SHALL contain a non-empty `path` field (invariant: path is always populated).
4. FOR ALL handled exceptions, THE ErrorResponse SHALL contain a `timestamp` field no later than the current moment and no earlier than the moment the request was received (invariant: timestamp is valid).
5. FOR ALL validation errors with N field errors, THE ErrorResponse.fieldErrors SHALL contain exactly N entries (invariant: all field errors are preserved).
6. FOR ALL message codes, THE MessageResolver SHALL return a string with length > 0 (round-trip: code → non-empty message).
7. THE ControllerAdvice SHALL handle ANY exception without throwing a new exception (idempotent handling — repeated invocation of the handler does not produce cascading errors).
