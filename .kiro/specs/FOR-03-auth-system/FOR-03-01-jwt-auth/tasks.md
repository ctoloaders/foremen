# Implementation Plan: FOR-03-01 JWT Authentication

## Overview

This plan implements stateless JWT authentication for the Foremen Spring Boot backend (module `foremen-backend`, package root `com.foremen`). It proceeds bottom-up: dependencies and configuration first, then the database migration and entities, then the pure logic components (token provider, encoder, refresh service) with their property tests, then the service and controller layers, and finally the Spring Security wiring, admin bootstrap, and integration tests.

Each task builds on the previous one and ends by wiring the new pieces into the running application. Property tests use jqwik (`*PropertyTest.java`, `@Property(tries = 100)`) and are tagged `// Feature: FOR-03-01-jwt-auth, Property N: ...`. Example tests use JUnit 5. Integration tests use Testcontainers (postgresql) + Spring Security Test.

Test conventions:
- Property test files: `*PropertyTest.java`, minimum 100 iterations, tagged with the design property number.
- Optional test sub-tasks are postfixed with `*` and may be skipped for a faster MVP.
- Each task cites the requirement numbers it satisfies and, where applicable, the design property numbers.

## Tasks

- [x] 1. Add dependencies and JWT/admin configuration
  - [x] 1.1 Add Gradle dependencies
    - In `foremen-backend/build.gradle` add `io.jsonwebtoken:jjwt-api` as `implementation`, `io.jsonwebtoken:jjwt-impl` and `io.jsonwebtoken:jjwt-jackson` as `runtimeOnly`, and `org.springframework.boot:spring-boot-starter-mail` as `implementation`
    - Confirm `spring-boot-starter-security` (already present) supplies `BCryptPasswordEncoder`
    - _Requirements: 4.3, 10.1, 13.2_

  - [x] 1.2 Create JwtProperties and application.yml config keys
    - Add `com.foremen.config.security.JwtProperties` as a `@Validated @ConfigurationProperties(prefix = "foremen.jwt")` record with `@Positive Integer accessTtlMinutes` (default 30), `@Positive Integer refreshTtlDays` (default 7), `@NotBlank String secret`; apply defaults in the compact constructor
    - Add config keys to `application.yml` under `foremen.jwt` (`access-ttl-minutes`, `refresh-ttl-days`, `secret`) and `foremen.admin` (`create`, `email`, `password`) bound to the environment variables per design
    - Fail fast on non-positive/non-numeric lifetimes via bean validation and relaxed binding
    - _Requirements: 14.1, 14.2, 14.5_

  - [x] 1.3 Write example tests for configuration defaults and fail-fast
    - Use `ApplicationContextRunner` with `foremen.jwt.*` properties: assert defaults 30/7 when unset (14.1, 14.2) and startup failure for non-positive or non-numeric lifetimes (14.5)
    - _Requirements: 14.1, 14.2, 14.5_

- [x] 2. Extend the user model and add token entities
  - [x] 2.1 Add UserStatus enum and extend UserEntity
    - Create `com.foremen.dao.model.UserStatus` enum with constants `INVITED`, `ACTIVE`, `DEACTIVATED`
    - Add `passwordHash` (String, nullable, column `password_hash` length 255) and `status` (`@Enumerated(STRING)`, column `status` length 20, not-null, default `INVITED`) fields to `com.foremen.dao.model.UserEntity`
    - _Requirements: 1.1, 1.2, 1.3, 1.4_

  - [x] 2.2 Add RefreshTokenEntity and PasswordResetTokenEntity
    - Create `RefreshTokenEntity` (`refresh_tokens`) extending `BaseEntity` with `token` (not-null, unique), `user` (`@ManyToOne` LAZY, `user_id` not-null), `expiresAt` (`expires_at`, not-null), `revoked` (not-null, default false)
    - Create `PasswordResetTokenEntity` (`password_reset_tokens`) extending `BaseEntity` with `token`, `user`, `expiresAt`, and `used` (not-null, default false)
    - Both in `com.foremen.dao.model`
    - _Requirements: 2.3, 2.4, 7.1, 13.2_

  - [x] 2.3 Write example tests for entity defaults
    - Assert a new `UserEntity` has `status == INVITED` and `UserStatus.values()` equals the three expected constants
    - _Requirements: 1.2, 1.3_

- [x] 3. Create Liquibase changesets for the auth schema
  - [x] 3.1 Author changesets 010, 011, 012 and register them
    - Create `database_files/changesets/010-add-user-auth-columns.xml` adding `password_hash VARCHAR(255)` nullable and `status VARCHAR(20) DEFAULT 'INVITED' NOT NULL` to `users`, with `preConditions onFail="MARK_RAN"` negating column existence
    - Create `011-create-refresh-tokens.xml` (`refresh_tokens` table per design) and `012-create-password-reset-tokens.xml` (`password_reset_tokens` table per design), each with `preConditions onFail="MARK_RAN"` negating table existence
    - Register all three changesets in `database_files/changelog.xml`; add no admin seed row
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_

  - [x] 3.2 Write Testcontainers migration idempotency test
    - Apply the changelog against a Testcontainers Postgres, assert the new columns/tables and constraints exist, then apply again to confirm `MARK_RAN` preConditions produce no duplicate changes
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_

- [x] 4. Add DAOs for the new entities
  - [x] 4.1 Create RefreshTokenDao and PasswordResetTokenDao
    - `RefreshTokenDao extends AdminDao<RefreshTokenEntity, Long>` with `findByToken` and `findByUserIdAndRevokedFalse`
    - `PasswordResetTokenDao extends AdminDao<PasswordResetTokenEntity, Long>` with `findByToken`
    - Place in `com.foremen.dao`
    - _Requirements: 7.1, 7.4, 13.2, 13.5_

- [x] 5. Implement password encoding
  - [x] 5.1 Create PasswordEncoderConfig
    - Add `com.foremen.config.security.PasswordEncoderConfig` exposing a `BCryptPasswordEncoder(12)` bean
    - _Requirements: 10.1_

  - [x] 5.2 Write property tests for bcrypt hashing
    - **Property 23: Bcrypt hash/verify round-trip** — **Validates: Requirements 10.3, 10.4**
    - **Property 24: Stored password is never plaintext** — **Validates: Requirements 10.2**
    - Use a real `BCryptPasswordEncoder(12)`; also assert the hash matches the `$2a$12$` format
    - _Requirements: 10.1, 10.2, 10.3, 10.4_

- [x] 6. Implement the JWT token provider
  - [x] 6.1 Create JwtTokenProvider and JwtClaims
    - Add `com.foremen.config.security.JwtTokenProvider` wrapping JJWT (HS256, key derived from `JwtProperties.secret`, fail fast if key < 32 bytes)
    - `generateAccessToken(Long userId, String roleCode, String email)` sets `sub`/`role`/`email`, `iat = now`, `exp = now + accessTtlMinutes`
    - `Optional<JwtClaims> validate(String token)` catches all JJWT exceptions and returns `Optional.empty()` on invalid/malformed/expired/wrong-signature; after parse, returns empty if any of `sub`/`role`/`email` is missing or null
    - Add `record JwtClaims(Long sub, String role, String email, Instant expiresAt)`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 14.3_

  - [x] 6.2 Write property tests for JwtTokenProvider
    - **Property 1: JWT generation/validation round-trip** — **Validates: Requirements 4.1, 4.4, 4.9**
    - **Property 2: Wrong-signature tokens are invalid** — **Validates: Requirements 4.5**
    - **Property 3: Expired tokens are invalid** — **Validates: Requirements 4.6**
    - **Property 4: Malformed tokens are invalid without throwing** — **Validates: Requirements 4.7**
    - **Property 5: Missing required claims make a token invalid** — **Validates: Requirements 4.8**
    - **Property 6: Access-token expiry bound (±2s)** — **Validates: Requirements 4.2, 14.3**
    - Use jqwik `@Provide` generators for user ids, role codes, emails, and malformed tokens
    - _Requirements: 4.1, 4.2, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 14.3_

- [x] 7. Implement the refresh token service
  - [x] 7.1 Create RefreshTokenService
    - Add `com.foremen.service.RefreshTokenService` with `issue(UserEntity)` (256-bit `SecureRandom`, base64url token; expiry = now + `refreshTtlDays`; `revoked=false`), `rotate(String token)` (validate + revoke old, return owner context), `revoke(String token)` (idempotent), `revokeAllForUser(Long userId)`
    - Distinguish not-found / revoked / expired for the caller so the service can raise the correct 401 codes
    - _Requirements: 7.1, 7.3, 7.4, 7.5, 7.6, 7.7, 8.2, 8.3, 13.5, 14.4_

  - [x] 7.2 Write property tests for RefreshTokenService
    - **Property 15: Refresh-token issuance invariant** — **Validates: Requirements 7.1, 14.4**
    - **Property 16: Refresh rotation is one-time-use** — **Validates: Requirements 7.3, 7.7, 8.4**
    - **Property 17: Unknown refresh token is rejected (401 error.auth.refresh.invalid)** — **Validates: Requirements 7.4**
    - **Property 18: Revoked refresh token is rejected (401 error.auth.refresh.revoked)** — **Validates: Requirements 7.5**
    - **Property 19: Expired refresh token is rejected (401 error.auth.refresh.expired)** — **Validates: Requirements 7.6**
    - **Property 20: Logout revokes a known refresh token** — **Validates: Requirements 8.2**
    - **Property 21: Logout is idempotent for unknown tokens** — **Validates: Requirements 8.3**
    - Use an in-memory/mock `RefreshTokenDao`
    - _Requirements: 7.1, 7.3, 7.4, 7.5, 7.6, 7.7, 8.2, 8.3, 8.4, 14.4_

- [x] 8. Add the mail abstraction and error message localization
  - [x] 8.1 Create MailSender abstraction and SMTP implementation
    - Add `com.foremen.service.mail.MailSender` interface with `sendPasswordReset(String toEmail, String token)` and `SmtpMailSender` implementation over Spring's `JavaMailSender`, configured for Gmail SMTP (`smtp.gmail.com:587`, STARTTLS)
    - Add `spring.mail.*` config (host/port/username/password + `starttls`) and `foremen.mail.from` / `foremen.mail.reset-base-url` to `application.yml` bound to env vars (`MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` as a Gmail App Password, `MAIL_FROM`, `MAIL_RESET_BASE_URL`); add these as placeholders to `.env.example`
    - _Requirements: 13.2_

  - [x] 8.2 Add auth error message codes in PL and RU
    - Add non-blank entries for `error.auth.invalid.credentials`, `error.auth.account.not.activated`, `error.auth.account.deactivated`, `error.auth.refresh.invalid`, `error.auth.refresh.revoked`, `error.auth.refresh.expired`, `error.auth.reset.token.invalid`, `error.auth.unauthorized`, and `error.user.admin.role.forbidden` to BOTH `messages.properties` (PL) and `messages_ru.properties` (RU)
    - _Requirements: 15.1, 12.5_

  - [x] 8.3 Write property test for message localization
    - **Property 31: All authentication message codes are localized in PL and RU** — **Validates: Requirements 15.1**
    - Read both resources from the classpath and assert every required code has a non-blank entry
    - _Requirements: 15.1, 12.5_

- [x] 9. Implement authentication DTOs and AuthService
  - [x] 9.1 Create request/response DTO records
    - Add `LoginRequest(@NotBlank email, @NotBlank password)`, `RefreshRequest(@NotBlank refreshToken)`, `PasswordResetRequest(@NotBlank @Email email)`, `PasswordResetConfirm(@NotBlank token, @NotBlank @Size(min=8) newPassword)`, `TokenResponse`, `CurrentUserResponse`, `PermissionView` in package `com.foremen.controller.dto.auth`
    - _Requirements: 3.1, 3.2, 7.2, 8.1, 9.2, 13.1, 13.4, 13.7_

  - [x] 9.2 Implement AuthService.login with status branching and timing resistance
    - Add `com.foremen.service.AuthService`; `login(email, rawPassword)`: case-insensitive `findByEmail`, precompute a startup `DUMMY_HASH`, run `matches` against it in the no-user path; branch INVITED (403 `error.auth.account.not.activated`), DEACTIVATED (403 `error.auth.account.deactivated`), ACTIVE (verify hash, 401 `error.auth.invalid.credentials` on mismatch); on success emit access + refresh tokens with `expiresIn = accessTtlMinutes * 60`
    - _Requirements: 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 10.3_

  - [x] 9.3 Write property tests for AuthService.login
    - **Property 7: Login rejects blank credential fields before verification** — **Validates: Requirements 3.2**
    - **Property 8: Login rejects non-matching credentials with 401** — **Validates: Requirements 3.4, 3.5**
    - **Property 9: Login failure timing is bounded (≤100ms median)** — **Validates: Requirements 3.6**
    - **Property 10: INVITED users cannot log in (403)** — **Validates: Requirements 3.7**
    - **Property 11: DEACTIVATED users cannot log in (403)** — **Validates: Requirements 3.8**
    - **Property 12: Login expiresIn matches configured lifetime × 60** — **Validates: Requirements 3.9**
    - Use a mock `UserDao` and a real `BCryptPasswordEncoder`
    - _Requirements: 3.2, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9_

  - [x] 9.4 Implement AuthService refresh, logout, and currentUser
    - `refresh(refreshToken)` delegates rotation to `RefreshTokenService` and issues a fresh access token for the owner; `logout(refreshToken)` idempotent revoke; `currentUser(userId)` returns id/name/email/roleCode plus permissions derived from the role's resource-operation associations
    - _Requirements: 7.3, 7.4, 7.5, 7.6, 8.2, 8.3, 9.2, 9.4_

  - [x] 9.5 Write property test for current-user response
    - **Property 22: Current-user response reflects identity and derived permissions** — **Validates: Requirements 9.2, 9.4**
    - Generate role/resource/operation graphs and assert the permission projection
    - _Requirements: 9.2, 9.4_

  - [x] 9.6 Implement AuthService password reset request and confirm
    - `requestPasswordReset(email)`: only ACTIVE users get a single-use token (TTL 60 min) persisted and emailed; all other cases return 200 silently with no token and no email (anti-enumeration)
    - `confirmPasswordReset(token, newPassword)`: validate token exists/unused/unexpired else 400 `error.auth.reset.token.invalid`; set bcrypt hash, mark token used, revoke all of the user's refresh tokens
    - _Requirements: 13.2, 13.3, 13.5, 13.6, 13.8_

  - [x] 9.7 Write property tests for password reset
    - **Property 26: Reset request issues a token only for ACTIVE users** — **Validates: Requirements 13.2**
    - **Property 27: Reset request does not enumerate users** — **Validates: Requirements 13.3**
    - **Property 28: Reset confirm sets the hash and revokes refresh tokens** — **Validates: Requirements 13.5, 13.8**
    - **Property 29: Reset confirm rejects invalid tokens (400)** — **Validates: Requirements 13.6**
    - **Property 30: Reset confirm enforces minimum password length (400)** — **Validates: Requirements 13.7**
    - Use a mock `MailSender`
    - _Requirements: 13.2, 13.3, 13.5, 13.6, 13.7, 13.8_

- [x] 10. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 11. Implement the JWT filter and security wiring
  - [x] 11.1 Create JwtAuthenticationFilter and JwtAuthenticationEntryPoint
    - `JwtAuthenticationFilter extends OncePerRequestFilter`: extract `Bearer ` token, on valid claims set `UsernamePasswordAuthenticationToken` (principal = `sub`, authority = `ROLE_<role>`); no header / non-`Bearer ` / invalid token leaves context untouched and continues the chain (not a `@Component`)
    - `JwtAuthenticationEntryPoint implements AuthenticationEntryPoint`: write HTTP 401 with an `ErrorResponse`-shaped body resolving `error.auth.unauthorized` in the request locale
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 6.4, 9.3_

  - [x] 11.2 Write property tests for JwtAuthenticationFilter
    - **Property 13: Filter authenticates on a valid Bearer token** — **Validates: Requirements 5.1**
    - **Property 14: Filter leaves context unauthenticated without a valid Bearer token** — **Validates: Requirements 5.2, 5.3, 5.4**
    - Use `MockHttpServletRequest`/`MockFilterChain` and assert `SecurityContextHolder` state
    - _Requirements: 5.1, 5.2, 5.3, 5.4_

  - [x] 11.3 Update SecurityConfig
    - Enable `@ConfigurationProperties(JwtProperties.class)`; configure `/api/auth/me` `authenticated()` before `/api/auth/**` `permitAll()`, keep `anyRequest().permitAll()`; STATELESS session; CSRF disabled; frame options disabled; register `jwtAuthenticationEntryPoint`; `addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)`
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

- [x] 12. Implement AuthController
  - [x] 12.1 Create AuthController endpoints
    - `@RestController @RequestMapping("/api/auth")` with `POST /login` (200 `TokenResponse`), `POST /refresh` (200), `POST /logout` (204), `GET /me` (200 `CurrentUserResponse`), `POST /password-reset/request` (200), `POST /password-reset/confirm` (200); use `@Valid` on request records so blank/short fields yield 400 via the existing handler
    - _Requirements: 3.1, 3.2, 7.2, 8.1, 9.1, 13.1, 13.4, 13.7_

  - [x] 12.2 Write example tests for endpoint wiring
    - MockMvc: `/login` returns both tokens for a seeded ACTIVE user (3.3); each endpoint is reachable at its path; blank/short fields yield 400
    - _Requirements: 3.1, 3.3, 7.2, 8.1, 13.1, 13.4_

- [x] 13. Enforce ADMIN-role prohibition in UserService
  - [x] 13.1 Add `validateCreate` hook to the AdminService CRUD framework
    - In `com.foremen.service.AdminService`, add a `default void validateCreate(ServiceExtendedModel model)` no-op hook (symmetric to the existing `validateUpdate`) and invoke it as the FIRST statement of the default single-entity `create(ServiceExtendedModel)`; leave batch `create(List<...>)` unchanged
    - _Requirements: 12.1_

  - [x] 13.2 Refactor UserService onto validateCreate/validateUpdate with ADMIN check first
    - Refactor `UserService` to stop overriding `create()`/`update()` wholesale and instead override `validateCreate(model)` and `validateUpdate(existing, model)` so the framework's create/update (with audit/snapshot) are reused; move the existing email-uniqueness/locale/role-resolution checks into these hooks
    - `validateCreate`: FIRST line — if the resolved target role code equals `ADMIN`, throw `ForemenApiException(FORBIDDEN, "error.user.admin.role.forbidden")` (12.1); then existing validations
    - `validateUpdate`: FIRST line — if existing role code is not `ADMIN` and the resolved target role code equals `ADMIN`, throw the same exception (12.2); allow unchanged role incl. ADMIN→ADMIN (12.6); then existing validations; enforce independently of caller role (12.3, 12.4)
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.6_

  - [x] 13.3 Write property test for ADMIN prohibition
    - **Property 25: ADMIN-role assignment prohibition invariant** — **Validates: Requirements 12.1, 12.2, 12.4, 12.6**
    - Exercise `create`/`update` (via the framework, hitting `validateCreate`/`validateUpdate`) with generated role transitions and mock DAOs
    - _Requirements: 12.1, 12.2, 12.4, 12.6_

- [x] 14. Implement the admin bootstrap bean
  - [x] 14.1 Create AdminBootstrap
    - Add `com.foremen.service.AdminBootstrap` as an `ApplicationRunner` guarded by `@ConditionalOnProperty(name = "foremen.admin.create", havingValue = "true")`; transactional, run-once logic: blank email/password → fail startup; count ADMIN users (> 1 → fail); 0 → create ACTIVE ADMIN with bcrypt(password); 1 with mismatched email → fail (no change); 1 matching email with non-empty password → update hash; never persist plaintext/default password
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 11.8, 11.9_

  - [x] 14.2 Write example tests for AdminBootstrap scenarios
    - Use `ApplicationContextRunner` with `foremen.admin.*`: bean absent when flag false/unset (11.1); no-admin create (11.4); matching-email update (11.6); mismatched-email failure (11.5); duplicate-admin failure (11.3); empty password (11.7) and empty email (11.8) failures; assert no admin password in the Liquibase seed (11.9)
    - _Requirements: 11.1, 11.3, 11.4, 11.5, 11.6, 11.7, 11.8, 11.9_

- [x] 15. Integration and wiring
  - [x] 15.1 Write SecurityConfig integration tests
    - MockMvc + real filter chain + Testcontainers Postgres + Spring Security Test: `/api/auth/login` reachable unauthenticated; `/api/auth/me` returns 401 without a token and 200 with a valid token; assert STATELESS session, CSRF disabled, and filter ordering
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 9.1, 9.3_

  - [x] 15.2 Write end-to-end auth flow integration test
    - Against a real database: login → refresh → logout → refresh-again, asserting rotation and post-logout rejection
    - _Requirements: 3.3, 7.3, 7.7, 8.2, 8.4_

  - [x] 15.3 Write ForemenControllerAdvice localization test
    - Assert an auth message code resolves per request locale (PL and RU)
    - _Requirements: 15.2_

- [x] 16. Author test-cases.md
  - Create `test-cases.md` in `.kiro/specs/FOR-03-auth-system/FOR-03-01-jwt-auth/` following the `.kiro/steering/test-cases.md` standard. This is an API-only spec: scenarios are API tests against the Dockerized backend (`docker compose up`, base URL `http://localhost:8080`, auth under `/api/auth`, ADMIN via `FOREMEN_ADMIN_*`). Group by feature (login, refresh, logout, /me, password reset, admin bootstrap, ADMIN-role prohibition), write step-by-step request/expectation scenarios, ensure repeatability via a unique-email generator (`test+{run-id}@example.com`) and/or teardown of created users, include a dedicated regression group, and provide an MD-report-with-tables template. Run artifacts are MD reports with tables, not screenshots.
  - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.7, 3.8, 3.9, 7.2, 7.3, 7.4, 7.5, 7.6, 8.1, 8.2, 8.3, 9.1, 9.2, 9.3, 11.1, 11.4, 12.1, 12.2, 13.1, 13.4, 13.6, 13.7, 15.1_

- [ ] 17. Automate API test cases (test-cases.md)
  - [ ] 17.1 Set up the API test automation harness
    - Create an executable automation project/module for the `test-cases.md` scenarios that runs HTTP requests against the Dockerized backend at `http://localhost:8080` (base URL configurable via env var). Provide a script/command to bring the stack up (`docker compose up -d`), wait for `backend` health/readiness, and tear it down afterwards. Configure the ADMIN bootstrap env (`FOREMEN_ADMIN_CREATE=true`, `FOREMEN_ADMIN_EMAIL`, `FOREMEN_ADMIN_PASSWORD`) for the run.
    - _Requirements: 3.1, 6.1, 9.1_
  - [ ] 17.2 Implement repeatability: unique-data generator and teardown
    - Implement a per-run `run-id` (timestamp/uuid) and a unique-email generator (`test+{run-id}+{seq}@example.com`); implement teardown that deactivates/removes users created during the run and revokes issued refresh tokens (via `/api/auth/logout`). Ensure the suite is re-runnable without manual DB cleanup, matching the repeatability strategy stated in `test-cases.md`.
    - _Requirements: 3.3, 8.2, 12.1_
  - [ ] 17.3 Automate the Login feature test cases
    - Implement automated checks for TC-LOGIN-01..08: success (access+refresh+expiresIn, expiresIn == access-ttl*60), blank email/password → 400, unknown email → 401 `error.auth.invalid.credentials`, wrong password → 401, INVITED → 403 `error.auth.account.not.activated`, DEACTIVATED → 403 `error.auth.account.deactivated`.
    - _Requirements: 3.2, 3.3, 3.4, 3.5, 3.7, 3.8, 3.9_
  - [ ] 17.4 Automate the Refresh + rotation and Logout test cases
    - Implement automated checks for TC-REFRESH-01..05 (rotation issues new pair + revokes old, reuse of rotated token → 401, unknown → 401 `error.auth.refresh.invalid`, expired → 401 `error.auth.refresh.expired`, blank → 400) and TC-LOGOUT-01..02 (known token → 204 then unusable, unknown token → 204 idempotent).
    - _Requirements: 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 8.1, 8.2, 8.3_
  - [ ] 17.5 Automate the Current-user, Password-reset, ADMIN-prohibition, and Localization test cases
    - Implement automated checks for TC-ME-01..03 (valid token → 200 identity+permissions, no/invalid token → 401), TC-RESET-01..05 (request ACTIVE → 200, anti-enumeration → 200, confirm invalid/used/expired → 400 `error.auth.reset.token.invalid`, short password → 400, successful confirm revokes refresh tokens), TC-NOADMIN-01..02 (create/promote to ADMIN → 403 `error.user.admin.role.forbidden`), TC-ADMIN-01 (bootstrap ADMIN can log in), and TC-I18N-01 (Accept-Language pl vs ru yields different message text). Note where a documented DB/test helper or intercepted-mail (mailhog) is required for ACTIVE-user seeding and reset-token retrieval.
    - _Requirements: 9.1, 9.2, 9.3, 11.1, 11.4, 12.1, 12.2, 13.1, 13.4, 13.6, 13.7, 15.1_
  - [ ] 17.6 Automate the Regression group and MD report generation
    - Implement automated checks for the `## Регрессия` cases (REG-01 full login→refresh→logout→refresh-again; REG-02 `/api/auth/**` public vs `/api/auth/me` authenticated; REG-03 existing non-auth endpoints still reachable). On completion, generate the MD run report matching the `test-cases.md` template (columns: #, Тест-кейс, Шаг, Запрос/Действие, Ожидание, Факт, Статус) plus the summary line (passed/failed/skipped, run-id, repeatability strategy).
    - _Requirements: 6.1, 7.3, 7.7, 8.2, 8.4, 9.1, 9.3_

- [ ] 18. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each task references specific requirements for traceability; property test tasks additionally reference the design property number they implement.
- All 31 design correctness properties are covered: Properties 1-6 (task 6.2), 7-12 (9.3), 13-14 (11.2), 15-21 (7.2), 22 (9.5), 23-24 (5.2), 25 (13.2), 26-30 (9.7), 31 (8.3).
- Property tests are jqwik `*PropertyTest.java` at ≥100 iterations, tagged `// Feature: FOR-03-01-jwt-auth, Property N: ...`.
- Checkpoints (tasks 10, 18) ensure incremental validation.
- All new auth error codes are added to BOTH PL and RU resources (task 8.2).
- Task group 17 automates the API test cases from `test-cases.md` and runs last, after all implementation and the test-cases authoring (task 16).

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2"] },
    { "id": 1, "tasks": ["1.3", "2.1", "2.2", "5.1", "8.2"] },
    { "id": 2, "tasks": ["2.3", "3.1", "4.1", "5.2", "6.1", "8.1", "8.3"] },
    { "id": 3, "tasks": ["3.2", "6.2", "7.1", "9.1", "11.1"] },
    { "id": 4, "tasks": ["7.2", "9.2", "9.4", "9.6", "11.2", "11.3", "13.1"] },
    { "id": 5, "tasks": ["9.3", "9.5", "9.7", "12.1", "13.2", "13.3", "14.1"] },
    { "id": 6, "tasks": ["12.2", "14.2", "15.1", "15.2", "15.3", "16"] },
    { "id": 7, "tasks": ["17.1", "17.2", "17.3", "17.4", "17.5", "17.6"] }
  ]
}
```
