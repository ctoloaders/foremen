# Implementation Plan: FOR-03-05 OTP Client Authentication

## Overview

This plan implements passwordless OTP login for CLIENT users in `foremen-backend` (Java / Spring Boot), building on the FOR-03-01 JWT infrastructure and the FOR-03-02 mail stack. Work proceeds bottom-up: entity + migration, DAO, client-TTL configuration, the OTP service (code generation, rate limiting, silent success, verify state machine), the OTP email, the controller/service endpoints and client-TTL token issuance, security verification, i18n, and tests. Each step builds on the previous ones and ends wired into the running application. Test sub-tasks marked with `*` are optional (property/unit/integration tests) and validate the design's correctness properties.

## Tasks

- [x] 1. Create OtpTokenEntity and Liquibase migration
  - [x] 1.1 Create `OtpTokenEntity`
    - Add `OtpTokenEntity` in `com.foremen.dao.model` extending `BaseEntity` with fields `email` (not-null), `code` (not-null, length 6), `expiresAt` (not-null), `used` (boolean default false), `attempts` (int default 0); mirror `RefreshTokenEntity` conventions; add `@Index` on `email`.
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_
  - [x] 1.2 Author changeset `016-create-otp-tokens.xml` and register it
    - Create `database_files/changesets/016-create-otp-tokens.xml` with `preConditions onFail="MARK_RAN"`, the `otp_tokens` table (id, email, code, expires_at, used default false, attempts default 0, created_date default NOW(), created_by, updated_date, updated_by) and the `idx_otp_tokens_email` index; add the `<include>` line after `015` in `changelog.xml`.
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_
  - [x] 1.3 Write integration test for migration and entity mapping
    - Testcontainers Postgres: apply changelog, assert `otp_tokens` columns/defaults and `idx_otp_tokens_email`, re-apply to confirm `MARK_RAN`; persist an entity and assert `created_date` populated, null email/code/expiresAt rejected.
    - _Requirements: 1.6, 2.1, 2.2, 2.5_
  - [x] 1.4 Write unit test for entity defaults
    - Assert `new OtpTokenEntity().isUsed() == false` and `getAttempts() == 0`.
    - _Requirements: 1.4, 1.5_

- [x] 2. Create OtpTokenDao
  - [x] 2.1 Create `OtpTokenDao`
    - Add `OtpTokenDao` in `com.foremen.dao` extending the shared DAO base, with `findFirstByEmailIgnoreCaseAndUsedFalseAndExpiresAtAfterOrderByCreatedDateDesc`, a companion `findFirstByEmailIgnoreCaseOrderByCreatedDateDesc`, and `countByEmailIgnoreCaseAndCreatedDateAfter`.
    - _Requirements: 3.1, 3.2, 3.3_
  - [x] 2.2 Write DAO example test
    - Testcontainers: seed rows and assert the newest unused unexpired row is returned and the in-window count is correct.
    - _Requirements: 3.1, 3.2_

- [x] 3. Add client token TTL configuration
  - [x] 3.1 Extend `JwtProperties` with client TTLs
    - Add `@Positive Integer clientAccessTtlMinutes` (default 120) and `@Positive Integer clientRefreshTtlDays` (default 30) to `JwtProperties`; add the `client-access-ttl-minutes`/`client-refresh-ttl-days` keys to `application.yml` bound to `FOREMEN_JWT_CLIENT_ACCESS_TTL_MINUTES`/`FOREMEN_JWT_CLIENT_REFRESH_TTL_DAYS`.
    - _Requirements: 7.1, 7.2, 7.5_
  - [x] 3.2 Write property test for client TTL validation predicate
    - **Property 14: Client TTL validation predicate**
    - **Validates: Requirements 7.5**
  - [x] 3.3 Write config-default example test
    - `ApplicationContextRunner`: defaults resolve to 120 / 30 when unset; non-positive value fails startup.
    - _Requirements: 7.1, 7.2, 7.5_

- [x] 4. Add TTL-aware token issuance overloads
  - [x] 4.1 Add TTL-aware `generateAccessToken` overload to `JwtTokenProvider`
    - Add `generateAccessToken(userId, roleCode, email, ttlMinutes)`; make the existing 3-arg method delegate with the employee TTL so employee behavior is unchanged.
    - _Requirements: 7.3, 7.4_
  - [x] 4.2 Add TTL-aware `issue` overload to `RefreshTokenService`
    - Add `issue(user, ttlDays)`; make the existing `issue(user)` delegate with the employee refresh TTL.
    - _Requirements: 7.3, 7.4_
  - [x] 4.3 Write property test for client TTL selection vs employee flows
    - **Property 13: Client TTL selection does not affect employee flows**
    - **Validates: Requirements 7.3, 7.4**

- [x] 5. Implement OTP code generation
  - [x] 5.1 Create `OtpCodeGenerator`
    - Add `OtpCodeGenerator` using `SecureRandom.nextInt(1_000_000)` and `String.format("%06d", value)` to produce exactly 6 digits with leading zeros preserved.
    - _Requirements: 4.5_
  - [x] 5.2 Write property test for code shape
    - **Property 1: OTP code shape**
    - **Validates: Requirements 4.5**

- [x] 6. Implement OtpService request path (rate limit, silent success, issuance)
  - [x] 6.1 Implement `OtpService.request`
    - Constants `CODE_TTL_MINUTES=15`, `MAX_ATTEMPTS=3`, `RATE_LIMIT_PER_HOUR=5`; count via `countByEmailIgnoreCaseAndCreatedDateAfter(email, now-1h)` FIRST; if `>= 5` throw `ForemenApiException(429, "error.auth.otp.rate.limited")`; then look up the user, and only for an ACTIVE CLIENT (Eligible_Email) generate + persist a token (expiresAt = now+15m, used=false, attempts=0) and dispatch the email; otherwise return silently.
    - _Requirements: 4.3, 4.4, 4.6, 5.1, 5.2, 5.3, 5.4, 5.5_
  - [x] 6.2 Write property test for OTP issuance shape
    - **Property 2: OTP issuance shape for eligible emails**
    - **Validates: Requirements 4.3**
  - [x] 6.3 Write property test for OTP code expiry
    - **Property 3: OTP code expiry equals issuance plus 15 minutes**
    - **Validates: Requirements 4.6**
  - [x] 6.4 Write property test for silent success
    - **Property 4: Silent success for non-eligible emails**
    - **Validates: Requirements 4.4**
  - [x] 6.5 Write property test for rate limiting
    - **Property 5: Rate limit is threshold-based, pre-lookup, and non-enumerating**
    - **Validates: Requirements 5.1, 5.2, 5.3, 5.5**

- [x] 7. Implement OtpService verify state machine
  - [x] 7.1 Implement `OtpService.verifyCode`
    - Fixed evaluation order: no-row → `error.auth.otp.invalid` (400); `attempts >= 3` → `error.auth.otp.attempts.exceeded` (400, no increment); `expiresAt <= now` → `error.auth.otp.expired` (400, used unchanged); `used` → `error.auth.otp.invalid` (400); code mismatch → increment attempts + `error.auth.otp.invalid` (400); else confirm Eligible_Email, set `used=true`, return the user.
    - _Requirements: 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.10_
  - [x] 7.2 Write property test for verify success + client access TTL
    - **Property 7: Verify success marks the code used and issues client-TTL tokens**
    - **Validates: Requirements 6.3, 6.9**
  - [x] 7.3 Write property test for no-matching-row rejection
    - **Property 8: Verify rejects a code that matches no active row**
    - **Validates: Requirements 6.4**
  - [x] 7.4 Write property test for wrong-code attempts increment
    - **Property 9: Wrong code increments attempts by exactly one**
    - **Validates: Requirements 6.5**
  - [x] 7.5 Write property test for locked code
    - **Property 10: Locked code after max attempts**
    - **Validates: Requirements 6.6**
  - [x] 7.6 Write property test for expired code
    - **Property 11: Expired code rejection**
    - **Validates: Requirements 6.7**
  - [x] 7.7 Write property test for single-use code
    - **Property 12: Used code is single-use**
    - **Validates: Requirements 6.8, 6.10**

- [x] 8. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. Implement OTP email
  - [x] 9.1 Create `OtpMailSender` and Thymeleaf template
    - Add `OtpMailSender` interface + `ThymeleafOtpMailSender` reusing the FOR-03-02 `JavaMailSender` + `TemplateEngine`; resolve locale RU/PL with PL fallback; add `templates/mail/otp-code.html` rendering the code and 15-minute notice; wire `OtpService` to dispatch through it.
    - _Requirements: 4.7, 9.3_
  - [x] 9.2 Write property test for email locale resolution
    - **Property 15: Email locale resolution falls back to Polish**
    - **Validates: Requirements 9.3**
  - [x] 9.3 Write example test for template rendering
    - Assert `otp-code.html` renders the code and validity notice for PL and RU.
    - _Requirements: 4.7_

- [x] 10. Wire OTP endpoints into AuthController and AuthService
  - [x] 10.1 Create OTP request DTOs
    - Add `OtpRequestRequest(@NotBlank @Email String email)` and `OtpVerifyRequest(@NotBlank @Email String email, @NotBlank String code)` in `com.foremen.controller.dto.auth`.
    - _Requirements: 4.1, 4.2, 6.1, 6.2_
  - [x] 10.2 Add `AuthService.verifyOtp` with client-TTL issuance
    - Call `otpService.verifyCode`, then issue access via the TTL-aware `generateAccessToken` with `clientAccessTtlMinutes` and refresh via `issue(user, clientRefreshTtlDays)`; return `TokenResponse` with `expiresIn = clientAccessTtlMinutes * 60`.
    - _Requirements: 6.3, 6.9, 7.3_
  - [x] 10.3 Add controller endpoints
    - Add `POST /api/auth/otp/request` (200, `@Valid`) delegating to `otpService.request`, and `POST /api/auth/otp/verify` (200 `TokenResponse`, `@Valid`) delegating to `authService.verifyOtp`.
    - _Requirements: 4.1, 4.2, 6.1, 6.2_
  - [x] 10.4 Write property test for blank-field rejection
    - **Property 6: Blank request fields are rejected before any side effect**
    - **Validates: Requirements 4.2, 6.2**

- [x] 11. Verify security configuration covers OTP endpoints
  - [x] 11.1 Confirm `/api/auth/**` permit rule covers OTP paths
    - Verify `SecurityConfig` permits `POST /api/auth/otp/request` and `POST /api/auth/otp/verify` via the existing `/api/auth/**` rule; add no new matcher; document the verification in a code comment.
    - _Requirements: 8.1, 8.2_
  - [x] 11.2 Write integration test for OTP endpoint reachability
    - `MockMvc` with the real filter chain: both OTP endpoints reachable unauthenticated.
    - _Requirements: 8.1, 8.2_

- [x] 12. Add i18n message codes
  - [x] 12.1 Add OTP message codes to PL and RU bundles
    - Add `error.auth.otp.invalid`, `error.auth.otp.expired`, `error.auth.otp.attempts.exceeded`, `error.auth.otp.rate.limited`, and the OTP email subject/body codes to both `messages.properties` (PL) and `messages_ru.properties` (RU).
    - _Requirements: 9.1, 9.2, 9.4_
  - [x] 12.2 Write property test for localization completeness
    - **Property 16: OTP message codes are localized in PL and RU**
    - **Validates: Requirements 9.1, 9.2**

- [x] 13. End-to-end integration test and rate-limit durability
  - [x] 13.1 Write end-to-end and durability integration tests
    - Testcontainers: seed an ACTIVE CLIENT → request → assert one code persisted + one email captured → verify → assert 200 tokens, `used`, `expiresIn == clientAccessTtlMinutes*60` → re-verify same code → 400 `error.auth.otp.invalid`; separately insert ≥5 rows and assert the count-based limit still applies across a fresh context (5.4).
    - _Requirements: 4.3, 5.4, 6.3, 6.9, 6.10_

- [x] 14. Implement the client-registration endpoint
  - [x] 14.1 Create client-registration DTOs
    - Add `ClientRegistrationRequest(@NotBlank String name, @NotBlank @Email String email, String phone, String locale, @NotNull Long projectId)` and `ClientRegistrationResponse(Long id, String email, Long projectId)` in `com.foremen.controller.model`. No `roleId` field.
    - _Requirements: 10.1, 10.3, 10.10_
  - [x] 14.2 Add `UserService.createClient` reusing the invite create path
    - Add a `createClient(name, email, phone, locale, RoleEntity clientRole)` helper that builds the user service-model with the given CLIENT role and delegates to the framework `create(...)`, so the existing `afterCreate` → `InviteService.issueInvite` hook issues the invite token and dispatches the client-portal email, with `status = INVITED` and null password unchanged.
    - _Requirements: 10.4, 10.5_
  - [x] 14.3 Implement `ClientRegistrationService`
    - Add `@Service @Transactional ClientRegistrationService.register(request)`: resolve CLIENT via `RoleDao.findByCode("CLIENT")` (500 `error.role.client.missing` if absent), call `userService.createClient(...)`, then `projectMemberService.assign(userId, projectId, clientRole.getId())`; return `ClientRegistrationResponse`. The whole method is one transaction so a failed assign rolls back the user creation and invite (10.7, 10.9).
    - _Requirements: 10.4, 10.6, 10.7, 10.8, 10.9_
  - [x] 14.4 Add the controller endpoint
    - Add `POST /api/users/client` to `UserController` returning 201, `@Valid`, annotated `@RequiresPermission(resource = "PROJECTS", operation = "EDIT")`, delegating to `clientRegistrationService.register`.
    - _Requirements: 10.1, 10.2, 10.10_
  - [x] 14.5 Reject CLIENT role on the generic create path (defense in depth)
    - On the generic `POST /api/users` create, reject a `roleId` resolving to code `CLIENT` (e.g. 400/409 with a clear message) so CLIENT users can only be created via `/api/users/client`.
    - _Requirements: 10.11_
  - [x] 14.6 Write property tests for client registration
    - **Property 17: Client registration fixes the CLIENT role regardless of request** — _Validates: 10.1, 10.4, 10.11_
    - **Property 18: Client registration creates exactly one membership on the supplied project** — _Validates: 10.6_
    - **Property 19: Client registration is atomic** — _Validates: 10.7, 10.9_
  - [x] 14.7 Write integration test for the endpoint
    - `MockMvc` + Testcontainers + Spring Security Test: with PROJECTS/EDIT → 201, user is CLIENT + INVITED, exactly one `project_members` row for `(userId, projectId)`, invite email captured; without the grant → 403 `error.access.denied`; missing `projectId`/blank name/email → 400; duplicate email → 409; duplicate `(email, projectId)` → 409 `error.project.member.duplicate` with no orphaned user (atomicity).
    - _Requirements: 10.2, 10.3, 10.6, 10.7, 10.8, 10.9, 10.10_

- [x] 15. Add client-registration i18n message codes
  - [x] 15.1 Add message codes to PL and RU bundles
    - Add `error.role.client.missing` (and any new client-registration error text) to both `messages.properties` (PL) and `messages_ru.properties` (RU). Reuse the existing duplicate-email and `error.project.member.duplicate` / `error.access.denied` codes already present.
    - _Requirements: 10.8, 10.9_

- [x] 16. Frontend: exclude ADMIN and CLIENT from the user-form role dropdown
  - [x] 16.1 Surface role `code` to the RoleSelect roles read path
    - Ensure the `/api/roles` fetch consumed by `RoleSelect` (and the `RoleOption` type) exposes each role's `code`, or add an exclusion query parameter to the fetch, so filtering can key on `code` (not name). Do not change the Roles management page contract.
    - _Requirements: 11.3, 11.5_
  - [x] 16.2 Filter ADMIN and CLIENT out of RoleSelect
    - Exclude roles whose `code` is `ADMIN` or `CLIENT` from the RoleSelect options in both the create and edit user forms (prefer a server-side exclusion on the fetch; client-side filter as fallback). When editing a user whose current role is ADMIN/CLIENT, still show the current role as context but do not offer it as a selectable option.
    - _Requirements: 11.1, 11.2, 11.4_
  - [x] 16.3 Write frontend tests
    - Extend the Vitest `RoleSelect`/`UsersPage` suites: ADMIN and CLIENT are absent from the create-form and edit-form dropdown options; editing a CLIENT/ADMIN user shows the current role but not as a selectable option; other `/api/roles` consumers are unaffected.
    - _Requirements: 11.1, 11.2, 11.4, 11.5_

- [x] 17. Author test-cases.md
  - Update `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature grouping, step-by-step scenarios, repeatability via unique-email generator + teardown, regression group, MD report template). This is primarily an API spec, so OTP and client-registration scenarios are API tests against the Dockerized app (`docker compose up`, backend `:8080`, auth under `/api/auth`, client registration under `/api/users/client`, admin bootstrap via `FOREMEN_ADMIN_*`). Include client-registration cases (PROJECTS/EDIT auth, auto project-member assignment, atomicity) and a note that the RoleSelect dropdown exclusion (Requirement 11) is covered by the frontend Vitest tests. Result artifacts are MD reports with tables.
  - _Requirements: 4.1, 4.3, 4.4, 5.1, 5.3, 6.3, 6.4, 6.5, 6.6, 6.7, 6.10, 7.3, 9.1, 10.2, 10.6, 10.7, 10.9, 11.1_

- [x] 18. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP; they implement the property/unit/integration tests that validate the design's correctness properties.
- Each task references specific requirement sub-clauses for traceability.
- Checkpoints ensure incremental validation.
- Property tests validate the universal correctness properties from the design (`design.md` Correctness Properties); unit and integration tests validate entity defaults, wiring, schema, security, and end-to-end flows.
- No employee-flow behavior changes: the TTL-aware overloads keep the existing employee login/refresh TTLs intact.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "3.1", "5.1", "10.1", "12.1", "14.1", "15.1", "16.1"] },
    { "id": 1, "tasks": ["1.2", "1.4", "3.2", "3.3", "4.1", "4.2", "5.2", "11.1", "12.2", "14.2", "16.2"] },
    { "id": 2, "tasks": ["1.3", "2.1", "4.3", "6.1", "9.1", "11.2", "14.3", "14.5", "16.3"] },
    { "id": 3, "tasks": ["2.2", "6.2", "6.3", "6.4", "6.5", "7.1", "9.2", "9.3", "14.4"] },
    { "id": 4, "tasks": ["7.2", "7.3", "7.4", "7.5", "7.6", "7.7", "10.2", "14.6", "14.7"] },
    { "id": 5, "tasks": ["10.3"] },
    { "id": 6, "tasks": ["10.4", "13.1"] }
  ]
}
```

Notes on the new work:
- Tasks 14–16 (client-registration endpoint, its i18n, and the frontend RoleSelect exclusion) build on FOR-03-02 (invite create path), FOR-03-03 (`@RequiresPermission`), FOR-03-04 (`ProjectMemberService`), and FOR-02-07 (users UI). They are independent of the OTP request/verify tasks and can proceed in parallel with them.
- Task 14.4 (controller endpoint) depends on 14.1–14.3. Task 16.2 depends on 16.1 surfacing the role `code`.
