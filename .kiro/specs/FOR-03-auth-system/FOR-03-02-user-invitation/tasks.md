# Implementation Plan: FOR-03-02 User Invitation

## Overview

This plan implements invite-based registration for the Foremen Spring Boot backend (module `foremen-backend`, package root `com.foremen`), the second child spec of the FOR-03 auth system. It builds on FOR-03-01 and reuses that spec's token/DAO patterns, `UserEntity` lifecycle, `JwtTokenProvider`, `RefreshTokenService`, `TokenResponse`, `BCryptPasswordEncoder`, mail transport, `ForemenApiException` pipeline, and PL/RU message resources.

The plan proceeds bottom-up so each step builds on the previous one and ends by wiring the new pieces into the running application: new Gradle dependency and fail-fast configuration first, then the pure link/locale helpers with their property tests, then the entity, migration, and DAO, then the mail rendering, then `InviteService`, then the `AuthService`/`AuthController` extension and `SecurityConfig`, then the `UserService` create-hook integration, and finally the localization and integration tests. There is no orphaned code: the config, helpers, entity, and DAO all feed `InviteService`; `InviteService` is consumed by both the `UserService` create hook (issuance) and `AuthService`/`AuthController` (set-password, resend); the migration backs the entity; and the security wiring exposes the endpoints.

Test conventions:
- Property test files: `*PropertyTest.java`, jqwik `@Property(tries = 100)` minimum, tagged `// Feature: FOR-03-02-user-invitation, Property N: ...`.
- Example/unit tests: JUnit 5. Integration tests: Spring Boot + Testcontainers (postgresql) + Spring Security Test.
- Optional test sub-tasks are postfixed with `*` and may be skipped for a faster MVP; core implementation tasks are never optional.
- Each task cites the requirement numbers it satisfies and, where applicable, the design property numbers.

## Tasks

- [x] 1. Add Thymeleaf dependency and invite configuration
  - [x] 1.1 Add the Thymeleaf Gradle dependency
    - In `foremen-backend/build.gradle` add `org.springframework.boot:spring-boot-starter-thymeleaf` as `implementation` (used to render HTML invitation email bodies); confirm `spring-boot-starter-mail`, `spring-boot-starter-validation`, and jqwik are already present
    - _Requirements: 4.5_

  - [x] 1.2 Create InviteProperties and MailInviteProperties with fail-fast validation
    - Add `com.foremen.config.mail.InviteProperties` as `@Validated @ConfigurationProperties(prefix = "foremen.invite")` record with `Integer ttlHours` (compact-constructor default 72) and an `@AssertTrue` predicate enforcing the range 1..8760
    - Add `com.foremen.config.mail.MailInviteProperties` as `@Validated @ConfigurationProperties(prefix = "foremen.mail")` record with `@NotBlank String inviteBaseUrl` and an `@AssertTrue` predicate that accepts only syntactically valid absolute http/https URLs
    - Enable both via `@EnableConfigurationProperties({InviteProperties.class, MailInviteProperties.class})` on a config class (new `MailConfig` or alongside `JwtProperties`)
    - Add `foremen.invite.ttl-hours` (env `FOREMEN_INVITE_TTL_HOURS`, default 72) and `foremen.mail.invite-base-url` (env `MAIL_INVITE_BASE_URL`, default `http://localhost:3000/auth/set-password`) to `application.yml`; add the env placeholders to `.env.example`
    - _Requirements: 7.1, 7.2, 7.5, 7.6_

  - [x] 1.3 Write property tests for the config validation predicates
    - **Property 18: Invite base URL validation predicate** — **Validates: Requirements 7.2**
    - **Property 19: Invite TTL validation predicate** — **Validates: Requirements 7.6**
    - Test the pure `@AssertTrue` predicate methods directly over generated valid/invalid URL strings and integers
    - _Requirements: 7.2, 7.6_

  - [x] 1.4 Write example tests for configuration defaults and startup fail-fast
    - Use `ApplicationContextRunner`: assert `ttl-hours` defaults to 72 and `invite-base-url` defaults to the localhost set-password URL when unset (7.1, 7.5); assert startup failure for an out-of-range/non-integer TTL and for an empty/blank/non-http(s) base URL (7.2, 7.6)
    - _Requirements: 7.1, 7.2, 7.5, 7.6_

- [x] 2. Implement pure invite-link and locale helpers
  - [x] 2.1 Implement the invite-link builder
    - Add a pure helper (e.g. static `buildInviteLink(String baseUrl, String token)` in `InviteService` or a small `InviteLinkBuilder`) that URL-encodes the token (UTF-8) and appends `?token={encoded}` when the base URL contains no `?`, otherwise `&token={encoded}`
    - _Requirements: 7.3, 7.4_

  - [x] 2.2 Implement the email-locale resolver
    - Add a pure helper that maps a stored user locale string to a `Locale`: `RU` when the string equals `RU` case-insensitively, `PL` when it equals `PL` case-insensitively, and `PL` for every other value including null/empty
    - _Requirements: 4.7, 8.4_

  - [x] 2.3 Write property tests for the link builder and locale resolver
    - **Property 7: Invite-link construction round-trips the token** — **Validates: Requirements 4.3, 7.3, 7.4**
    - **Property 8: Email locale resolution falls back to Polish** — **Validates: Requirements 4.7, 8.4**
    - Generate base URLs with and without a `?`, arbitrary token strings (assert separator, encoding, and decode round-trip), and arbitrary locale strings including `pl`/`PL`/`ru`/`RU`/`en`/empty
    - _Requirements: 4.3, 4.7, 7.3, 7.4, 8.4_

- [x] 3. Add the InviteTokenEntity
  - [x] 3.1 Create InviteTokenEntity
    - Add `com.foremen.dao.model.InviteTokenEntity` extending `BaseEntity`, mirroring `PasswordResetTokenEntity`: `token` (`@Column(nullable=false, unique=true)`, holds a 36-char UUID string), `user` (`@ManyToOne(fetch = LAZY)`, `@JoinColumn(name="user_id", nullable=false)`), `expiresAt` (`@Column(name="expires_at", nullable=false)`, `Instant`), `used` (`@Column(nullable=false)`, `boolean`, defaulting to `false`)
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_

  - [x] 3.2 Write example test for entity defaults and package
    - Assert `new InviteTokenEntity().isUsed() == false` (1.5) and that the class resides in `com.foremen.dao.model` (1.7)
    - _Requirements: 1.5, 1.7_

- [x] 4. Create the Liquibase changeset for invite_tokens
  - [x] 4.1 Author changeset 013 and register it
    - Create `database_files/changesets/013-create-invite-tokens.xml` with `changeSet id="013-create-invite-tokens" author="foremen"` and `preConditions onFail="MARK_RAN"` negating `tableExists tableName="invite_tokens"`, creating the `invite_tokens` table per design: `id` BIGSERIAL PK not-null autoIncrement, `token` VARCHAR(255) not-null unique (`uk_invite_tokens_token`), `user_id` BIGINT not-null FK (`fk_invite_tokens_user` → `users(id)`), `expires_at` TIMESTAMP not-null, `used` BOOLEAN not-null default false, `created_date` TIMESTAMP not-null default `NOW()`, `created_by` VARCHAR(255) nullable, `updated_date` TIMESTAMP nullable, `updated_by` VARCHAR(255) nullable
    - Register the changeset in `database_files/changelog.xml` immediately after the `012` include
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [x] 4.2 Write Testcontainers migration idempotency test
    - Apply the changelog against a Testcontainers Postgres, assert the `invite_tokens` table, columns, and constraints (PK, unique `uk_invite_tokens_token`, FK `fk_invite_tokens_user`, not-null `expires_at`, `used` default false, `created_date` default NOW()) exist; re-apply to confirm the `MARK_RAN` precondition leaves the table and data unchanged
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

- [x] 5. Add the InviteTokenDao
  - [x] 5.1 Create InviteTokenDao
    - Add `com.foremen.dao.InviteTokenDao extends AdminDao<InviteTokenEntity, Long>` with `Optional<InviteTokenEntity> findByToken(String token)` and `List<InviteTokenEntity> findByUserIdAndUsedFalse(Long userId)`, mirroring `RefreshTokenDao`/`PasswordResetTokenDao`
    - _Requirements: 1.3, 3.5, 5.6, 6.6_

- [x] 6. Implement invitation email rendering and dispatch
  - [x] 6.1 Create the InvitationMailSender abstraction and Thymeleaf implementation
    - Add `com.foremen.service.mail.InvitationMailSender` interface with `sendSetPasswordInvitation(UserEntity user, String inviteLink)` and `sendClientPortalInvitation(UserEntity user)`
    - Add `ThymeleafInvitationMailSender` using the existing `JavaMailSender` + `MimeMessageHelper` (HTML) and a Thymeleaf `TemplateEngine`; resolve the recipient locale via the helper from task 2.2 and resolve subject/body text through the existing `MessageResolver` in that locale
    - Create Thymeleaf templates `src/main/resources/templates/mail/invite-set-password.html` (contains the `inviteLink`) and `invite-client-portal.html` (OTP-portal instruction, no set-password link)
    - _Requirements: 4.3, 4.4, 4.5, 4.6, 4.7, 8.4_

  - [x] 6.2 Write example tests for template rendering and dispatch
    - Assert both templates render for PL and RU (4.5); the set-password body contains the invite link and the client-portal body contains none (4.3, 4.4); dispatch goes through the injected `JavaMailSender`/`InvitationMailSender` (4.6)
    - _Requirements: 4.3, 4.4, 4.5, 4.6_

- [x] 7. Implement InviteService
  - [x] 7.1 Implement issuance and role-dependent email dispatch
    - Add `com.foremen.service.InviteService` (`@Service @Transactional`) with `issueInvite(UserEntity user)` and a private `generateAndSend(UserEntity)`: generate `token = UUID.randomUUID().toString()` (36-char), `expiresAt = Instant.now().plus(ttlHours, HOURS)`, `used = false`, associate with the user, persist via `InviteTokenDao`; then select the email variant by role code (`CLIENT` → client-portal via `sendClientPortalInvitation`; any other/employee → set-password via `sendSetPasswordInvitation` with the link from task 2.1). A token-persist or mail failure propagates so the enclosing transaction rolls back (3.6, 4.8)
    - _Requirements: 3.1, 3.2, 3.3, 3.5, 3.6, 3.8, 4.1, 4.2, 4.8_

  - [x] 7.2 Write property tests for issuance and email dispatch
    - **Property 1: Invite issuance invariant** — **Validates: Requirements 3.1, 3.3, 3.8, 1.1**
    - **Property 2: Invite-token expiry equals issuance plus configured TTL** — **Validates: Requirements 3.2, 6.6**
    - **Property 4: Generated invite tokens are distinct** — **Validates: Requirements 3.5**
    - **Property 5: Employee invitations send exactly one set-password email** — **Validates: Requirements 4.1**
    - **Property 6: Client invitations send exactly one client-portal email** — **Validates: Requirements 4.2, 4.4**
    - Use a mock `InviteTokenDao` capturing saved entities and a mock `InvitationMailSender` recording dispatches; jqwik generators for names, emails, and role codes partitioned into CLIENT / employee sets
    - _Requirements: 3.1, 3.2, 3.3, 3.5, 3.8, 4.1, 4.2, 4.4, 6.6_

  - [x] 7.3 Implement token validation/consume for set-password
    - Add `InviteService.consume(String token)`: lookup by token; evaluation order invalid → used → expired → owner-status. Absent → `ForemenApiException(400, "error.invite.token.invalid")`; `used == true` → `(400, "error.invite.token.used")`; `expiresAt <= now` → `(400, "error.invite.token.expired")`; owner `DEACTIVATED` → `(409, "error.invite.user.already.active")` with the token unchanged; otherwise (owner INVITED) return the entity. Each negative branch makes no state change
    - _Requirements: 5.6, 5.7, 5.8, 5.10_

  - [x] 7.4 Write property tests for consume validation
    - **Property 11: Set-password rejects unknown tokens** — **Validates: Requirements 5.6**
    - **Property 12: Set-password rejects expired tokens** — **Validates: Requirements 5.7**
    - **Property 13: Set-password rejects already-used tokens** — **Validates: Requirements 5.8**
    - **Property 14: Set-password rejects invites for deactivated owners** — **Validates: Requirements 5.10**
    - Use a mock `InviteTokenDao`; generate token states and owner statuses; assert status codes, message codes, and that no state change occurs on rejection
    - _Requirements: 5.6, 5.7, 5.8, 5.10_

  - [x] 7.5 Implement resend
    - Add `InviteService.resend(Long userId)`: `userDao.findById` absent → `ForemenApiException(404, "error.invite.user.not.found")`, no change/email; owner `ACTIVE` or `DEACTIVATED` → `ForemenApiException(409, "error.invite.user.already.active")`, no change/email; owner `INVITED` → mark every `findByUserIdAndUsedFalse(userId)` token `used = true`, then `generateAndSend(user)` (new UUID token, fresh expiry, `used = false`, role-dependent email)
    - _Requirements: 6.6, 6.7, 6.8, 6.9_

  - [x] 7.6 Write property tests for resend
    - **Property 15: Resend for INVITED users rotates the token** — **Validates: Requirements 6.6**
    - **Property 16: Resend rejects unknown users** — **Validates: Requirements 6.7**
    - **Property 17: Resend rejects non-INVITED users** — **Validates: Requirements 6.8, 6.9**
    - Use mock `UserDao`/`InviteTokenDao` and a mock `InvitationMailSender`; generate users, statuses, and prior token sets
    - _Requirements: 6.6, 6.7, 6.8, 6.9_

- [x] 8. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 9. Extend AuthService and AuthController with set-password and resend
  - [x] 9.1 Create the request DTO records
    - Add `com.foremen.controller.dto.auth.SetPasswordRequest(@NotBlank String token, @NotBlank @Size(min=8, max=72) String password)` and `ResendInviteRequest(@NotNull Long userId)`
    - _Requirements: 5.1, 5.3, 6.1, 6.3_

  - [x] 9.2 Implement AuthService.setPassword
    - Add `@Transactional TokenResponse setPassword(String token, String rawPassword)`: call `inviteService.consume(token)` for validation; set the owner's `passwordHash` to `passwordEncoder.encode(rawPassword)` (bcrypt cost 12), set status `ACTIVE`, set token `used = true`, persist both, and return `issueTokens(user)` (reusing the FOR-03-01 `JwtTokenProvider` + `RefreshTokenService` helper). Inject `InviteService`, `InviteTokenDao`, and `UserDao` as needed; all writes occur in one transaction so activation is atomic
    - _Requirements: 5.4, 5.5, 5.9_

  - [x] 9.3 Write property tests for set-password activation
    - **Property 9: Set-password activation and one-time-use invariant** — **Validates: Requirements 5.4, 5.5, 5.9**
    - Use mock `InviteTokenDao`/`UserDao`, a real `BCryptPasswordEncoder(12)`, and stub `JwtTokenProvider`/`RefreshTokenService`; generate valid token states and password lengths 8..72; assert bcrypt verify, status ACTIVE, token used, non-blank tokens returned, and that reusing the same token yields 400 `error.invite.token.used`
    - _Requirements: 5.4, 5.5, 5.9_

  - [x] 9.4 Add the AuthController endpoints
    - Extend `com.foremen.controller.AuthController`: `POST /set-password` (`@RequestBody @Valid SetPasswordRequest`) returning `200 TokenResponse` via `authService.setPassword(...)`; `POST /resend-invite` (`@RequestBody @Valid ResendInviteRequest`) returning `200` via `inviteService.resend(...)`. `@Valid` yields 400 for blank token / password out of 8..72 (5.3) and for null userId (6.3) through the existing handler, before any lookup
    - _Requirements: 5.1, 5.3, 6.1, 6.3_

  - [x] 9.5 Write property/example tests for endpoint validation
    - **Property 10: Set-password rejects invalid request fields before lookup** — **Validates: Requirements 5.3**
    - MockMvc: `/api/auth/set-password` and `/api/auth/resend-invite` reachable at their paths (5.1, 6.1); blank token or password length <8/>72 → 400 with no token lookup (5.3); null userId → 400 (6.3)
    - _Requirements: 5.1, 5.3, 6.1, 6.3_

- [x] 10. Wire security for the new endpoints
  - [x] 10.1 Update SecurityConfig
    - In `com.foremen.config.security.SecurityConfig`, add `requestMatchers(HttpMethod.POST, "/api/auth/resend-invite").hasRole("ADMIN")` ordered before the broad `/api/auth/**` `permitAll()` (which already covers `/api/auth/set-password`), keeping the existing `/api/auth/me` authenticated rule and `anyRequest().permitAll()`
    - _Requirements: 5.2, 6.2, 6.4, 6.5_

  - [x] 10.2 Write security wiring integration tests
    - MockMvc + real filter chain: `POST /api/auth/set-password` reachable unauthenticated (5.2); `POST /api/auth/resend-invite` returns 401 without a token (6.4), 403 with a non-ADMIN token (6.5), and reaches the controller with a `ROLE_ADMIN` token (6.2)
    - _Requirements: 5.2, 6.2, 6.4, 6.5_

- [x] 11. Integrate invite issuance into user creation
  - [x] 11.1 Add the afterCreate hook to the AdminService CRUD framework
    - In `com.foremen.service.AdminService`, add a `default void afterCreate(DaoModel entity) { }` no-op hook (symmetric to `validateCreate`) and invoke it in the default single-entity `create(...)` after the entity is saved and flushed and before/around the audit write, so it runs inside the same `@Transactional` create
    - _Requirements: 3.6_

  - [x] 11.2 Override afterCreate in UserService to issue the invite
    - In `com.foremen.service.UserService`, override `afterCreate(UserEntity user)` to call `inviteService.issueInvite(user)`; inject `InviteService`. Because create is transactional, a token-persist or mail failure rolls back the user insert (3.6). No change to `UserServiceMapper` (still hard-codes INVITED) or the request DTOs (still expose no `status`), so every created user is INVITED with a null `passwordHash` and gets exactly one invite (3.4, 3.7, 3.8)
    - _Requirements: 3.4, 3.6, 3.7, 3.8_

  - [x] 11.3 Write property test for the created-user invariant
    - **Property 3: Created users are INVITED with no password** — **Validates: Requirements 3.4, 3.7**
    - Exercise the create path with mock DAOs over generated create inputs; assert status INVITED and null `passwordHash`
    - _Requirements: 3.4, 3.7_

  - [x] 11.4 Write integration test for create-time atomicity
    - Force invite-token persistence to fail after the user insert and assert no user row remains (transaction rollback)
    - _Requirements: 3.6_

- [x] 12. Add invitation error and email message localization
  - [x] 12.1 Add invite message codes in PL and RU
    - Add non-blank entries to BOTH `messages.properties` (PL base) and `messages_ru.properties` (RU) for the five error codes `error.invite.token.invalid`, `error.invite.token.expired`, `error.invite.token.used`, `error.invite.user.not.found`, `error.invite.user.already.active`, and for the subject/body codes of both email templates (set-password and client-portal invitations)
    - _Requirements: 8.1, 8.2, 8.5_

  - [x] 12.2 Write property test for message localization
    - **Property 20: Invitation message codes are localized in PL and RU** — **Validates: Requirements 8.1, 8.2**
    - Read both bundles from the classpath and assert every required error and email code resolves to a non-blank value in PL and RU
    - _Requirements: 8.1, 8.2_

  - [x] 12.3 Write example test for request-locale error resolution
    - Assert `ForemenControllerAdvice` resolves an invite message code per request locale (PL and RU) and that a base-only code falls back to PL for RU (8.3, 8.5), following existing `MessageResolver` test patterns
    - _Requirements: 8.3, 8.5_

- [x] 13. End-to-end integration
  - [x] 13.1 Write the invite end-to-end integration test
    - Testcontainers Postgres + real Spring context (mock `InvitationMailSender`): create a user via `POST /api/users` → assert one invite token persisted and one email dispatched → `POST /api/auth/set-password` with the token → assert 200 with tokens, user ACTIVE, token used → re-submit the same token → assert 400 `error.invite.token.used`; also exercise `POST /api/auth/resend-invite` as ADMIN for an INVITED user and assert token rotation
    - _Requirements: 3.1, 4.1, 5.4, 5.5, 5.9, 6.6_

- [x] 14. Author test-cases.md
  - Create `test-cases.md` in `.kiro/specs/FOR-03-auth-system/FOR-03-02-user-invitation/` following the `.kiro/steering/test-cases.md` standard. This is an API-only spec: scenarios are API tests against the Dockerized backend (`docker compose up`, base URL `http://localhost:8080`, auth endpoints under `/api/auth`, first ADMIN via `FOREMEN_ADMIN_CREATE=true` / `FOREMEN_ADMIN_EMAIL` / `FOREMEN_ADMIN_PASSWORD`). Write in Russian, group by feature (invite issuance on user create, role-dependent invitation email, set-password + auto-login, resend-invite ADMIN authorization, configuration/localization), with detailed step-by-step request/expectation scenarios. State the repeatability strategy explicitly (unique-email generator `test+{run-id}@example.com` plus teardown of created users / consumed tokens so the suite re-runs without manual DB cleanup). Include a dedicated `## Регрессия` group (e.g. re-verify set-password one-time-use, ADMIN-only resend, FOR-03-01 auth paths still reachable). Provide the MD report template with tables (#, Тест-кейс, Шаг, Запрос/Действие, Ожидание, Факт, Статус) plus the summary line (passed/failed/skipped, run-id, repeatability strategy). Run artifacts are MD reports with tables, not screenshots.
  - _Requirements: 3.1, 3.7, 4.1, 4.2, 5.1, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 5.9, 5.10, 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9, 7.1, 8.1_

- [x] 15. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional test sub-tasks and can be skipped for a faster MVP; core implementation tasks are never optional.
- Each task references specific requirements for traceability; property test tasks additionally reference the design property number they implement.
- All 20 design correctness properties are covered: Properties 18-19 (task 1.3), 7-8 (2.3), 1/2/4/5/6 (7.2), 11-14 (7.4), 15-17 (7.6), 9 (9.3), 10 (9.5), 3 (11.3), 20 (12.2).
- Property tests are jqwik `*PropertyTest.java` at ≥100 iterations, tagged `// Feature: FOR-03-02-user-invitation, Property N: ...`.
- Checkpoints (tasks 8, 15) ensure incremental validation.
- All new invite error and email codes are added to BOTH PL and RU resources (task 12.1).
- `test-cases.md` (task 14) is authored on the tasks phase per the workspace `.kiro/steering/test-cases.md` standard, before the final checkpoint.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "2.1", "2.2", "3.1"] },
    { "id": 1, "tasks": ["1.3", "1.4", "2.3", "3.2", "4.1", "5.1", "11.1", "12.1"] },
    { "id": 2, "tasks": ["4.2", "6.1", "7.1", "9.1", "10.1", "12.2", "12.3"] },
    { "id": 3, "tasks": ["6.2", "7.2", "7.3", "7.5", "10.2"] },
    { "id": 4, "tasks": ["7.4", "7.6", "9.2", "9.4", "11.2"] },
    { "id": 5, "tasks": ["9.3", "9.5", "11.3", "11.4", "13.1"] },
    { "id": 6, "tasks": ["14"] }
  ]
}
```
