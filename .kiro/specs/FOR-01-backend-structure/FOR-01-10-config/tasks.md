# Implementation Plan: FOR-01-10-config

## Overview

Создание трёх конфигурационных классов Spring Boot (SecurityConfig, CacheConfig, CorsConfig) в соответствующих подпакетах `com.foremen.config.*`, добавление свойств в application.yml и написание тестов.

## Tasks

- [x] 1. Create configuration classes and application.yml properties
  - [x] 1.1 Create SecurityConfig in `com.foremen.config.security`
    - Create `src/main/java/com/foremen/config/security/SecurityConfig.java`
    - `@Configuration` + `@EnableWebSecurity`, bean `SecurityFilterChain`
    - permitAll для всех запросов, disable CSRF, STATELESS sessions, disable frameOptions
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_

  - [x] 1.2 Create CacheConfig in `com.foremen.config.cache`
    - Create `src/main/java/com/foremen/config/cache/CacheConfig.java`
    - `@Configuration` + `@EnableCaching`, bean `CaffeineCacheManager`
    - `@Value("${foremen.cache.spec:maximumSize=500,expireAfterWrite=10m}")` для спецификации
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_

  - [x] 1.3 Create CorsConfig in `com.foremen.config.web`
    - Create `src/main/java/com/foremen/config/web/CorsConfig.java`
    - `@Configuration`, implements `WebMvcConfigurer`, override `addCorsMappings`
    - `@Value("${foremen.cors.allowed-origins:http://localhost:3000}")` для origins
    - Все пути `/**`, методы GET/POST/PUT/DELETE/PATCH/OPTIONS, headers `*`, credentials true, maxAge 3600
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9_

  - [x] 1.4 Add foremen properties to application.yml
    - Append `foremen.cache.spec` and `foremen.cors.allowed-origins` to existing `application.yml`
    - _Requirements: 4.1, 4.2_

- [x] 2. Write tests
  - [x] 2.1 Write SecurityConfigTest
    - `@WebMvcTest` slice test: verify GET/POST/PUT/DELETE return non-401/403, POST without CSRF not rejected, no X-Frame-Options header, no Set-Cookie (stateless)
    - _Requirements: 1.3, 1.4, 1.5, 1.6_

  - [x] 2.2 Write CacheConfigTest
    - `@SpringBootTest` with `@TestPropertySource`: verify CacheManager bean is `CaffeineCacheManager`, verify property override works
    - _Requirements: 2.2, 2.3, 2.4_

  - [x] 2.3 Write CorsConfigTest
    - `@WebMvcTest` slice test: OPTIONS preflight from allowed origin returns CORS headers, all 6 methods in Allow-Methods, credentials true, max-age 3600, disallowed origin gets no CORS headers
    - _Requirements: 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9_

  - [x] 2.4 Write property tests (jqwik) for Security and CORS
    - **Property 1: Security permits all requests without authentication** — for any path and method, response is never 401/403
    - **Property 3: CORS preflight allows configured methods for all paths** — for any path and allowed method, preflight returns correct headers
    - **Property 4: CORS origin filtering** — preflight returns Allow-Origin iff origin is in configured list
    - **Validates: Requirements 1.3, 3.3, 3.4, 3.5, 3.8, 3.9**

- [x] 3. Checkpoint
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- All three config classes are independent — tasks 1.1–1.3 can be implemented in any order
- Property 2 (cache spec override) is already covered by CacheConfigTest (task 2.2) since it requires Spring context with property override
- Existing `ForemenWebMvcConfig` in `com.foremen.config.web` handles locale/interceptors — CorsConfig is a separate class in the same package

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.3", "1.4"] },
    { "id": 1, "tasks": ["2.1", "2.2", "2.3", "2.4"] }
  ]
}
```
