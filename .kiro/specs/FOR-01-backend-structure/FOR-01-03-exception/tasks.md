# Implementation Plan: Exception Handling System (FOR-01-03-exception)

## Overview

Implement a unified exception handling infrastructure for the Foremen backend: a custom exception hierarchy with HTTP status and i18n message codes, a centralized `@RestControllerAdvice` producing uniform JSON error responses, a `MessageResolver` component for localized messages, and structured error logging with request context.

All code resides in `foremen-backend/src/main/java/com/foremen/` and tests in `foremen-backend/src/test/java/com/foremen/`.

## Tasks

- [x] 1. Implement exception hierarchy and ErrorResponse record
  - [x] 1.1 Create ForemenApiException base class
    - Create `com.foremen.exception.ForemenApiException` extending `RuntimeException`
    - Include fields: `HttpStatusCode status`, `String messageCode`, `Object[] messageParams`
    - Use `@Getter` from Lombok
    - Implement two constructors: full (status, messageCode, messageParams...) and short (status, messageCode)
    - Normalize null messageParams to empty `Object[]`
    - `super(messageCode)` in constructor
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_

  - [x] 1.2 Create ForemenValidationException subclass
    - Create `com.foremen.exception.ForemenValidationException` extending `ForemenApiException`
    - Preset status to `HttpStatus.BAD_REQUEST` (400)
    - Constructor accepts only messageCode and optional messageParams
    - _Requirements: 7.1, 7.2, 7.3_

  - [x] 1.3 Create ErrorResponse record
    - Create `com.foremen.exception.dto.ErrorResponse` as a Java record
    - Fields: `Instant timestamp`, `int status`, `String error`, `String message`, `String path`, `Map<String, String> fieldErrors`
    - Add `@JsonInclude(JsonInclude.Include.NON_NULL)` so fieldErrors is omitted when null
    - Add convenience constructor without fieldErrors
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8_

- [x] 2. Implement MessageResolver and resource bundles
  - [x] 2.1 Create MessageResolver component with MessageSourceConfig
    - Create `com.foremen.config.i18n.MessageResolver` annotated with `@Component`
    - Inject `MessageSource`, implement `resolve(String code, Object[] params, Locale locale)` method
    - Use `getMessage(code, params, code, locale)` — fallback to code itself
    - Create nested `@Configuration` class `MessageSourceConfig` with:
      - `ResourceBundleMessageSource` bean: basename `messages`, defaultEncoding `UTF-8`, useCodeAsDefaultMessage `true`
      - `LocaleResolver` bean: `AcceptHeaderLocaleResolver` with default locale `Locale("pl")`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.7_

  - [x] 2.2 Create resource bundle files
    - Create `src/main/resources/messages.properties` (Polish, default) with codes: `error.data.integrity`, `error.access.denied`, `error.internal`, `error.validation`
    - Create `src/main/resources/messages_ru.properties` (Russian) with the same codes translated
    - _Requirements: 4.5, 4.6_

- [x] 3. Implement ForemenControllerAdvice
  - [x] 3.1 Create ForemenControllerAdvice class
    - Create `com.foremen.controller.advice.ForemenControllerAdvice` annotated with `@RestControllerAdvice`, `@Slf4j`, `@RequiredArgsConstructor`
    - Inject `MessageResolver`
    - Implement `handleForemenApiException` — status from exception, resolve message via messageCode, log without stack trace
    - Implement `handleValidationException` for `MethodArgumentNotValidException` — extract fieldErrors + globalErrors into map, status 400, message from `error.validation`
    - Implement `handleConstraintViolation` for `ConstraintViolationException` — extract violations into fieldErrors map, status 400
    - Implement `handleDataIntegrity` for `DataIntegrityViolationException` — status 409, message from `error.data.integrity`
    - Implement `handleAccessDenied` for `AccessDeniedException` — status 403, message from `error.access.denied`
    - Implement `handleGenericException` for `RuntimeException` — status 500, message from `error.internal`, log WITH stack trace
    - Implement private `logError()` helper with structured logging (code, method, uri, user, status)
    - Implement private `extractUsername()` from `SecurityContextHolder` (anonymous fallback)
    - All handlers populate `path` from `request.getRequestURI()` and `timestamp` with `Instant.now()`
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 5.1, 5.2, 5.3, 5.4, 5.5, 6.1, 6.2, 6.3, 6.4_

- [x] 4. Checkpoint - Verify compilation
  - Ensure all code compiles with `./gradlew compileJava`, ask the user if questions arise.

- [x] 5. Property-based tests
  - [x] 5.1 Write property test: Status Preservation Invariant
    - **Property 1: Status Preservation Invariant**
    - Generate random `HttpStatus` values + random message codes → create `ForemenApiException` → invoke handler → assert response status == exception status
    - **Validates: Requirements 3.2, 7.1, 7.2, 8.1**

  - [x] 5.2 Write property test: Field Errors Count Preservation
    - **Property 2: Field Errors Count Preservation**
    - Generate random list of (fieldName, errorMessage) pairs (1..20) → mock `MethodArgumentNotValidException` → invoke handler → assert `fieldErrors.size()` == input count
    - **Validates: Requirements 3.3, 6.1, 6.2, 8.5**

  - [x] 5.3 Write property test: No Internal Details Exposure
    - **Property 3: No Internal Details Exposure**
    - Generate random RuntimeException messages (including SQL, stack-trace-like strings) → invoke generic handler → assert response message does NOT contain original exception message
    - **Validates: Requirements 3.7**

  - [x] 5.4 Write property test: Path Populated From Request URI
    - **Property 4: Path Populated From Request URI**
    - Generate random valid URI paths → mock `HttpServletRequest` → invoke any handler → assert `response.path` == generated URI
    - **Validates: Requirements 3.8, 8.3**

  - [x] 5.5 Write property test: Message Resolution Always Non-Empty
    - **Property 5: Message Resolution Always Non-Empty**
    - Generate random code strings (existing codes + random non-existent codes) → call `resolve()` → assert result is non-null and non-empty
    - **Validates: Requirements 4.3, 8.2, 8.6**

  - [x] 5.6 Write property test: Handler Never Throws
    - **Property 6: Handler Never Throws**
    - Generate random `RuntimeException` subclasses with random messages (including null message) → invoke handler → assert no exception thrown + valid ResponseEntity returned
    - **Validates: Requirements 8.7**

  - [x] 5.7 Write property test: ErrorResponse JSON Serialization Round-Trip
    - **Property 7: ErrorResponse JSON Serialization Round-Trip**
    - Generate random `ErrorResponse` instances → serialize to JSON via Jackson → deserialize → assert equality
    - **Validates: Requirements 2.8**

  - [x] 5.8 Write property test: ConstraintViolation Mapping Completeness
    - **Property 8: ConstraintViolation Mapping Completeness**
    - Generate random sets of (propertyPath, message) pairs → mock `ConstraintViolationException` → invoke handler → assert `fieldErrors` matches input
    - **Validates: Requirements 6.4**

  - [x] 5.9 Write property test: Subclass Polymorphic Handling
    - **Property 9: Subclass Polymorphic Handling**
    - Generate random anonymous subclasses of `ForemenApiException` with random status/code → invoke handler → assert response matches exception fields
    - **Validates: Requirements 7.1, 7.2**

- [x] 6. Unit tests
  - [x] 6.1 Write unit tests for ForemenApiException
    - Verify extends RuntimeException, @Getter generates accessors
    - Verify default params constructor → `messageParams` is empty array
    - Verify null messageParams normalized to empty array
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.6, 1.7_

  - [x] 6.2 Write unit tests for ForemenValidationException
    - Verify extends ForemenApiException, preset status is 400
    - _Requirements: 7.3_

  - [x] 6.3 Write unit tests for ErrorResponse
    - Verify record structure with expected components
    - Verify convenience constructor sets fieldErrors to null
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7_

  - [x] 6.4 Write unit tests for ForemenControllerAdvice
    - Test DataIntegrityViolationException → 409 mapping
    - Test AccessDeniedException → 403 mapping
    - Test generic RuntimeException → 500 mapping
    - Verify logging with stack trace for unexpected exceptions
    - Verify logging without stack trace for ForemenApiException
    - _Requirements: 3.4, 3.5, 3.6, 3.7, 5.3, 5.4_

  - [x] 6.5 Write unit tests for MessageResolver
    - Test resolve with existing code returns translated message
    - Test resolve with parameters (substitution of `{0}`, `{1}`)
    - Test resolve with missing code returns code itself
    - Test default locale is PL when no Accept-Language
    - Verify resource bundles contain required keys
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_

- [x] 7. Integration tests
  - [x] 7.1 Write integration tests for exception handling flow
    - `@SpringBootTest` + `MockMvc` — throw ForemenApiException from a test controller → verify JSON response structure
    - Verify locale switching with `Accept-Language: ru` → Russian message in response
    - Verify Bean Validation — POST invalid DTO → 400 + fieldErrors populated
    - Verify SecurityContext — authenticated request → username logged
    - _Requirements: 3.2, 3.3, 3.8, 4.2, 5.2, 6.1_

- [x] 8. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass with `./gradlew test`, ask the user if questions arise.
  - commit all changes

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document (Properties 1–9)
- Unit tests validate specific examples and edge cases
- The implementation language is Java 25 with Spring Boot 4.0.0 and Jakarta EE 11
- jqwik 1.9.2 is already configured in build.gradle for property-based testing

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.3", "2.1", "2.2"] },
    { "id": 1, "tasks": ["1.2"] },
    { "id": 2, "tasks": ["3.1"] },
    { "id": 3, "tasks": ["5.1", "5.2", "5.3", "5.4", "5.5", "5.6", "5.7", "5.8", "5.9"] },
    { "id": 4, "tasks": ["6.1", "6.2", "6.3", "6.4", "6.5"] },
    { "id": 5, "tasks": ["7.1"] }
  ]
}
```
