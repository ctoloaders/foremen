# Requirements Document

## Introduction

This specification defines passwordless OTP (one-time-password) authentication for CLIENT users of the Foremen backend (FOR-03-05, the fifth child spec of the FOR-03 auth system). Clients never set a password; instead, on each new login they request a short-lived numeric code by email, then exchange that code for a JWT access/refresh token pair. Because client access is intended to be low-friction and long-lived, client tokens use dedicated, longer lifetimes (access 2 hours, refresh 30 days) than employee tokens (access 30 minutes, refresh 7 days).

The flow is:

1. A client submits `POST /api/auth/otp/request` with an `email`.
2. IF the email maps to an ACTIVE user whose role code is `CLIENT`, THE OtpService generates a cryptographically-random 6-digit code (TTL 15 minutes), persists it, and emails it via the existing mail infrastructure.
3. For any other email (unknown, non-CLIENT, or non-ACTIVE), the endpoint returns HTTP 200 without sending a code and without disclosing eligibility (no user enumeration), mirroring the FOR-03-01 password-reset non-enumeration behavior.
4. A per-email rate limit (5 requests per hour) is enforced by counting `otp_tokens` rows created in the trailing hour, applied uniformly before user lookup so that rate limiting cannot be used to probe whether an email is an eligible client.
5. The client submits `POST /api/auth/otp/verify` with `{ email, code }`. On a valid, unexpired, unused code with attempts remaining that belongs to an ACTIVE CLIENT, THE OtpService marks the code used and issues a client-TTL JWT access token plus a client-TTL refresh token, returning a TokenResponse. A wrong code increments the attempt counter; after 3 failed attempts the code is locked; expired and already-used codes are rejected.

This spec also adds a dedicated client-registration endpoint. Employee accounts are created through the generic `POST /api/users` CRUD path, but a CLIENT account is created only through a separate controller method that never accepts a role in the request (the role is fixed to `CLIENT` by the server) and instead requires a `projectId`. On success, the endpoint both creates the CLIENT user (status `INVITED`, no password, with a client-portal invitation email as in FOR-03-02) and immediately records that user as a member of the given project under the CLIENT project role, reusing the FOR-03-04 `ProjectMemberService`. The caller that will invoke this endpoint from the project module is out of scope here; this spec delivers the endpoint and its auto-membership behavior.

Finally, this spec adjusts the existing users-management role dropdown (FOR-02-07 frontend) so that the `ADMIN` and `CLIENT` roles are not selectable when creating or editing a user through the generic user form. `ADMIN` is a single seeded account and `CLIENT` accounts are created exclusively through the new client-registration endpoint, so neither should be assignable through the general form.

Scope:
- `otp_tokens` table + Liquibase changeset, `OtpTokenEntity`, `OtpTokenDao`
- `POST /api/auth/otp/request` (code generation, TTL, persistence, rate limiting, silent success, email dispatch)
- `POST /api/auth/otp/verify` (validation branches, attempts increment, client-TTL token issuance)
- Client-specific token lifetime configuration properties with fail-fast validation
- The OTP email template (Thymeleaf) and its PL/RU localization
- New PL/RU message codes for OTP errors and the OTP email
- A dedicated client-registration endpoint (server-fixed CLIENT role, required `projectId`, automatic project membership via `ProjectMemberService`), guarded by the `PROJECTS`/`EDIT` permission
- A frontend change to the FOR-02-07 user form: exclude `ADMIN` and `CLIENT` from the assignable-role dropdown

The following are explicitly OUT of scope and belong to other FOR-03 child specs: the permission evaluator (FOR-03-03), project-ownership infrastructure itself (FOR-03-04, whose `ProjectMemberService` this spec only reuses), the wholesale `permitAll()` migration (FOR-03-08), and the client OTP-login page (FOR-03-06). Email-based matching of an already-created project to a client, and the project-module code that will call the client-registration endpoint, are out of scope. This spec defines only the backend API contract the frontend consumes plus the one targeted user-form dropdown adjustment.

This feature depends on FOR-03-01 (JWT infrastructure) and reuses its components as-is: `JwtTokenProvider`, `RefreshTokenService`, `TokenResponse`, `JwtProperties`-style configuration, `UserEntity`/`RoleEntity`/`UserDao`/`RoleDao`, `ForemenApiException` + `ForemenControllerAdvice`, `MessageResolver`, the Spring Security `/api/auth/**` permit rule, Liquibase conventions, and the jqwik + JUnit 5 + Testcontainers testing conventions. FOR-03-02 already established the `JavaMailSender` + Thymeleaf mail infrastructure (`InvitationMailSender`, `templates/mail/`) and the invite-token issuance used when an administrator creates a user; this spec reuses that mail stack and invite flow rather than reinventing them. The client-registration endpoint additionally reuses the FOR-03-03 `@RequiresPermission` guard (resource `PROJECTS`, operation `EDIT`) and the FOR-03-04 `ProjectMemberService.assign(userId, projectId, projectRoleId)` for automatic membership. The role-dropdown adjustment builds on the FOR-02-07 users UI (`RoleSelect` component and the `/api/roles` fetch it consumes).

## Glossary

- **OTP_Code**: A cryptographically-secure random one-time password consisting of exactly 6 numeric characters (`0`-`9`), with leading zeros preserved (for example `007413`), emailed to a client and exchanged for tokens. TTL fixed at 15 minutes.
- **OtpTokenEntity**: The JPA entity mapped to the `otp_tokens` table, residing in package `com.foremen.dao.model` and extending `BaseEntity`. Stores the recipient email, the OTP_Code, expiry, a used flag, and an attempts counter.
- **OtpTokenDao**: The repository interface for `OtpTokenEntity`, providing lookup and rate-limit-count query methods.
- **OtpService**: The service encapsulating OTP business logic: code generation, rate-limit enforcement, silent-success eligibility, persistence, email dispatch, and code verification.
- **OtpMailSender**: The component that renders and dispatches the OTP email through the reused `JavaMailSender` + Thymeleaf infrastructure.
- **Client_User**: A UserEntity whose associated RoleEntity `code` equals `CLIENT` (case-sensitive, exact match).
- **Eligible_Email**: An email that (case-insensitively) matches exactly one UserEntity that is both a Client_User and has status `ACTIVE`. Only an Eligible_Email results in a code being generated and sent.
- **Silent_Success**: The behavior in which `POST /api/auth/otp/request` returns HTTP 200 with no body-disclosed distinction, regardless of whether the email is an Eligible_Email, so that a caller cannot determine whether an email exists or is a client (no user enumeration).
- **Rate_Limit_Window**: The trailing one-hour period ending at the current instant used to count prior OTP requests for a given email.
- **Rate_Limit_Max**: The maximum number of OTP requests permitted per email within the Rate_Limit_Window; fixed at 5.
- **Client_Access_Token**: An Access_Token (as defined in FOR-03-01) issued through the OTP verify flow using the client access TTL.
- **Client_Refresh_Token**: A Refresh_Token (as defined in FOR-03-01) issued through the OTP verify flow using the client refresh TTL.
- **FOREMEN_JWT_CLIENT_ACCESS_TTL_MINUTES**: The environment variable (config property `foremen.jwt.client-access-ttl-minutes`) specifying the Client_Access_Token lifetime in minutes, defaulting to 120 when unset.
- **FOREMEN_JWT_CLIENT_REFRESH_TTL_DAYS**: The environment variable (config property `foremen.jwt.client-refresh-ttl-days`) specifying the Client_Refresh_Token lifetime in days, defaulting to 30 when unset.
- **JwtTokenProvider**: The existing FOR-03-01 component that generates and validates Access_Tokens.
- **RefreshTokenService**: The existing FOR-03-01 service that issues, rotates, and revokes Refresh_Tokens.
- **TokenResponse**: The existing FOR-03-01 response DTO `{accessToken, refreshToken, expiresIn}` returned to clients.
- **AuthController**: The existing REST controller exposing authentication endpoints under `/api/auth`, extended by this spec with the two OTP endpoints.
- **AuthService**: The existing service encapsulating authentication business logic, extended by this spec to complete OTP verification and token issuance.
- **UserEntity**: The existing JPA entity for a system user (`com.foremen.dao.model.UserEntity`), carrying `status`, `locale`, and a `role` association.
- **User_Status**: The FOR-03-01 enumerated user lifecycle state, one of `INVITED`, `ACTIVE`, `DEACTIVATED`.
- **ForemenApiException**: The existing application exception carrying an HTTP status, an i18n message code, and optional parameters.
- **ForemenControllerAdvice**: The existing global exception handler translating ForemenApiException into HTTP responses.
- **MessageResolver**: The existing component resolving i18n message codes against `messages.properties` (PL base) and `messages_ru.properties` (RU) per request locale.
- **Client_Registration_Endpoint**: The dedicated controller method that creates a CLIENT user and assigns it to a project, distinct from the generic `POST /api/users` CRUD create. It never accepts a role in its request; the server fixes the role to `CLIENT`.
- **CLIENT_Role**: The seeded RoleEntity whose `code` equals `CLIENT`, resolved by the server (via `RoleDao.findByCode("CLIENT")`) rather than supplied by the caller.
- **ProjectMemberService**: The existing FOR-03-04 service providing `assign(userId, projectId, projectRoleId)`, which persists a `project_members` row and rejects a duplicate `(userId, projectId)` with HTTP 409.
- **ProjectMember_Assignment**: The `project_members` row created for the newly registered client, linking the client user to the supplied `projectId` under the CLIENT project role.
- **RequiresPermission**: The existing FOR-03-03 method annotation enforced by the permission interceptor; the Client_Registration_Endpoint carries `@RequiresPermission(resource = "PROJECTS", operation = "EDIT")`.
- **RoleSelect**: The existing FOR-02-07 frontend component (`src/features/users/components/RoleSelect.tsx`) that renders the assignable-role dropdown in the user create/edit form, backed by the paginated `/api/roles` fetch.
- **Assignable_Role**: A role that MAY be selected in the RoleSelect dropdown — every system role except `ADMIN` and `CLIENT`.

## Requirements

### Requirement 1: OtpTokenEntity

**User Story:** As a developer, I want an OtpTokenEntity that carries an email, a code, expiry, a used flag, and an attempts counter, so that OTP issuance and verification state can be persisted.

#### Acceptance Criteria

1. THE OtpTokenEntity SHALL contain an `email` field of type String, not-null, mapped to column `email`.
2. THE OtpTokenEntity SHALL contain a `code` field of type String, not-null, mapped to column `code`, holding the 6-character OTP_Code with leading zeros preserved.
3. THE OtpTokenEntity SHALL contain an `expiresAt` field representing the code expiry instant, not-null, mapped to column `expires_at`.
4. THE OtpTokenEntity SHALL contain a `used` boolean field, not-null, mapped to column `used`, with a default value of false.
5. THE OtpTokenEntity SHALL contain an `attempts` integer field, not-null, mapped to column `attempts`, with a default value of 0.
6. THE OtpTokenEntity SHALL inherit `id`, `createdDate`, `createdBy`, `updatedDate`, and `updatedBy` from BaseEntity, and its `createdDate` SHALL be populated on first persist.
7. THE OtpTokenEntity SHALL reside in the package `com.foremen.dao.model`.

---

### Requirement 2: Database Migration — otp_tokens table

**User Story:** As a developer, I want a Liquibase changeset that creates the otp_tokens table, so that the schema supports OTP authentication.

#### Acceptance Criteria

1. THE migration SHALL create an `otp_tokens` table containing columns `id` (primary key), `email` (VARCHAR, not-null), `code` (VARCHAR, not-null), `expires_at` (TIMESTAMP, not-null), `used` (BOOLEAN, not-null, default false), `attempts` (INTEGER, not-null, default 0), and `created_date` (TIMESTAMP, not-null), plus the BaseEntity audit columns `created_by`, `updated_date`, and `updated_by`.
2. THE migration SHALL create an index on the `email` column of the `otp_tokens` table to support per-email lookup and rate-limit counting.
3. THE migration SHALL be authored as the next free changeset number after the existing changesets, which is `016`, named `016-create-otp-tokens.xml`.
4. THE migration SHALL register the new changeset in `database_files/changelog.xml` after changeset `015`.
5. THE migration SHALL include Liquibase preConditions with `onFail="MARK_RAN"` so that re-running the changeset on a database where `otp_tokens` already exists performs no duplicate changes.

---

### Requirement 3: OtpTokenDao

**User Story:** As a developer, I want a DAO for OtpTokenEntity, so that OTP issuance, verification, and rate limiting can query the store.

#### Acceptance Criteria

1. THE OtpTokenDao SHALL provide a method that returns the most recent unused, unexpired OtpTokenEntity for a given email, or none when no such row exists.
2. THE OtpTokenDao SHALL provide a method `countByEmailAndCreatedDateAfter` that returns the number of OtpTokenEntity rows whose `email` matches (case-insensitively) and whose `createdDate` is after a supplied cutoff instant.
3. THE OtpTokenDao SHALL reside in package `com.foremen.dao` and follow the existing DAO conventions used by RefreshTokenDao.

---

### Requirement 4: OTP Request Endpoint

**User Story:** As a client, I want to request a login code by email, so that I can authenticate without a password.

#### Acceptance Criteria

1. THE AuthController SHALL expose `POST /api/auth/otp/request` accepting a request body containing an `email`.
2. IF an OTP request omits `email` or supplies a blank `email` (empty or whitespace-only), THEN THE AuthController SHALL reject the request with HTTP 400 status and a validation error indicating the missing or blank field, before any rate-limit check or user lookup is attempted.
3. WHEN an OTP request references an Eligible_Email and the email is within the rate limit, THE OtpService SHALL generate an OTP_Code, persist an OtpTokenEntity for that email with `expiresAt` set to 15 minutes after issuance, `used` false, and `attempts` 0, and send the code to the email address, then return HTTP 200.
4. WHEN an OTP request references an email that is not an Eligible_Email (unknown, non-CLIENT, or non-ACTIVE) and the email is within the rate limit, THE OtpService SHALL return HTTP 200 without generating or persisting an OTP_Code and without sending any email, disclosing no distinction from the eligible case (Silent_Success, no user enumeration).
5. THE OtpService SHALL generate each OTP_Code using a cryptographically-secure random source such that the code consists of exactly 6 characters, every character is a decimal digit, and leading zeros are preserved.
6. THE persisted OTP_Code TTL SHALL be exactly 15 minutes, so that `expiresAt` equals the issuance instant plus 15 minutes within a tolerance of ±5 seconds.
7. THE OtpService SHALL dispatch the OTP email through the reused JavaMailSender plus Thymeleaf infrastructure established in FOR-03-02.

---

### Requirement 5: OTP Request Rate Limiting

**User Story:** As a system operator, I want per-email rate limiting on OTP requests, so that the endpoint cannot be abused to spam recipients or enumerate accounts.

#### Acceptance Criteria

1. THE OtpService SHALL enforce a per-email limit of Rate_Limit_Max (5) OTP requests within the Rate_Limit_Window (the trailing one hour), computed by counting `otp_tokens` rows whose email matches and whose `createdDate` falls within the Rate_Limit_Window.
2. THE OtpService SHALL evaluate the rate limit for the requested email BEFORE performing the Eligible_Email user lookup, so that the rate-limit outcome depends only on the request count and never on whether the email is an eligible client.
3. IF the count of OTP requests for the email within the Rate_Limit_Window is greater than or equal to Rate_Limit_Max, THEN THE OtpService SHALL reject the request with HTTP 429 status and message code `error.auth.otp.rate.limited`, generate no OTP_Code, persist no OtpTokenEntity, and send no email, regardless of whether the email is an Eligible_Email.
4. THE rate-limit enforcement SHALL be backed by the `otp_tokens` table count and SHALL survive an application restart (no reliance on in-memory counters).
5. WHEN the count of OTP requests for the email within the Rate_Limit_Window is below Rate_Limit_Max, THE OtpService SHALL proceed to the Silent_Success eligibility branch of Requirement 4.

---

### Requirement 6: OTP Verify Endpoint

**User Story:** As a client, I want to submit my email and the emailed code, so that I receive tokens to access protected resources.

#### Acceptance Criteria

1. THE AuthController SHALL expose `POST /api/auth/otp/verify` accepting a request body containing an `email` and a `code`.
2. IF an OTP verify request omits `email`, supplies a blank `email`, omits `code`, or supplies a blank `code`, THEN THE AuthController SHALL reject the request with HTTP 400 status and a validation error, before any code lookup is attempted.
3. WHEN an OTP verify request supplies an email and code that match a persisted OtpTokenEntity that is unused, has an `expiresAt` in the future, has `attempts` less than 3, and whose email is an Eligible_Email, THE OtpService SHALL mark that OtpTokenEntity `used` as true and THE AuthService SHALL return a Client_Access_Token and a Client_Refresh_Token in a TokenResponse.
4. IF an OTP verify request supplies an email and code that match no persisted unused OtpTokenEntity, THEN THE OtpService SHALL treat the code as wrong per Requirement 6.5 and, when no matching row exists at all, SHALL throw a ForemenApiException with HTTP 400 status and message code `error.auth.otp.invalid`, making no token issuance.
5. IF an OTP verify request supplies an email that matches an unused, unexpired OtpTokenEntity with `attempts` less than 3 but supplies a code that does not match that entity's `code`, THEN THE OtpService SHALL increment that entity's `attempts` by 1 and throw a ForemenApiException with HTTP 400 status and message code `error.auth.otp.invalid`, issuing no tokens.
6. IF an OTP verify request references an OtpTokenEntity whose `attempts` is greater than or equal to 3, THEN THE OtpService SHALL reject the verification with HTTP 400 status and message code `error.auth.otp.attempts.exceeded`, issue no tokens, and make no further attempts increment.
7. IF an OTP verify request references an OtpTokenEntity whose `expiresAt` is equal to or earlier than the current time, THEN THE OtpService SHALL reject the verification with HTTP 400 status and message code `error.auth.otp.expired`, issue no tokens, and leave the entity `used` value unchanged.
8. IF an OTP verify request references an OtpTokenEntity whose `used` value is already true, THEN THE OtpService SHALL reject the verification with HTTP 400 status and message code `error.auth.otp.invalid`, issue no tokens.
9. WHEN an OTP verify request succeeds, THE TokenResponse `expiresIn` value SHALL equal the client access TTL in seconds, that is `FOREMEN_JWT_CLIENT_ACCESS_TTL_MINUTES` × 60 (default 7200).
10. WHEN an OTP_Code has been marked used through a successful verification, THE OtpService SHALL reject any subsequent verification using the same code per Requirement 6.8.

---

### Requirement 7: Client Token Lifetime Selection and Configuration

**User Story:** As an operator, I want client access and refresh token lifetimes to be separately configurable and applied to OTP logins, so that clients get long-lived sessions without changing employee session durations.

#### Acceptance Criteria

1. THE system SHALL read the client access token lifetime from configuration property `foremen.jwt.client-access-ttl-minutes` (environment variable `FOREMEN_JWT_CLIENT_ACCESS_TTL_MINUTES`), interpreted as a number of minutes, defaulting to 120 when unset.
2. THE system SHALL read the client refresh token lifetime from configuration property `foremen.jwt.client-refresh-ttl-days` (environment variable `FOREMEN_JWT_CLIENT_REFRESH_TTL_DAYS`), interpreted as a number of days, defaulting to 30 when unset.
3. WHEN tokens are issued through the OTP verify flow, THE AuthService SHALL set the Client_Access_Token expiry to the client access TTL and the Client_Refresh_Token expiry to the client refresh TTL, independently of the employee access and refresh TTLs.
4. WHEN tokens are issued through the employee login and refresh flows of FOR-03-01, THE system SHALL continue to use the employee access TTL (`foremen.jwt.access-ttl-minutes`) and employee refresh TTL (`foremen.jwt.refresh-ttl-days`), unaffected by the client TTLs.
5. IF either client lifetime property is set to a non-positive or non-numeric value, THEN THE system SHALL fail application startup with a clear configuration error.

---

### Requirement 8: Security Configuration for OTP Endpoints

**User Story:** As a developer, I want the OTP endpoints to be publicly reachable, so that unauthenticated clients can request and verify codes.

#### Acceptance Criteria

1. THE SecurityConfig SHALL permit unauthenticated access to `POST /api/auth/otp/request` and `POST /api/auth/otp/verify`, covered by the existing `/api/auth/**` permit-all rule from FOR-03-01.
2. THE SecurityConfig SHALL require no additional authorization rule for the OTP endpoints beyond the existing `/api/auth/**` permit-all rule, and this spec SHALL verify and state that no new matcher is needed.

---

### Requirement 9: Error and Email Message Localization

**User Story:** As a client, I want OTP errors and the OTP email in my language, so that I understand the flow.

#### Acceptance Criteria

1. THE system SHALL define localized PL and RU messages for the message codes `error.auth.otp.invalid`, `error.auth.otp.expired`, `error.auth.otp.attempts.exceeded`, and `error.auth.otp.rate.limited`, with a non-blank entry for each code in both `messages.properties` (PL base) and `messages_ru.properties` (RU).
2. THE system SHALL define localized PL and RU message codes for the OTP email subject and body, with a non-blank entry for each in both resources.
3. WHEN dispatching the OTP email, THE OtpMailSender SHALL resolve the recipient locale from the user's `locale`, normalized case-insensitively to RU when the value equals `RU`, to PL when the value equals `PL`, and to PL for every other value including empty (PL fallback).
4. WHEN a ForemenApiException is raised with an OTP message code, THE ForemenControllerAdvice SHALL resolve the message according to the request locale.

---

### Requirement 10: Client Registration Endpoint

**User Story:** As a project manager, I want a dedicated endpoint to register a client for a specific project, so that the client is created with the CLIENT role and immediately attached to that project without me choosing a role.

#### Acceptance Criteria

1. THE AuthController (or the designated controller) SHALL expose a dedicated client-registration endpoint, distinct from the generic `POST /api/users` CRUD create, that accepts a request body containing `name`, `email`, `projectId`, and OPTIONALLY `phone` and `locale`, and SHALL NOT accept a role identifier in the request body.
2. THE Client_Registration_Endpoint SHALL require the permission `resource = PROJECTS`, `operation = EDIT` via `@RequiresPermission`, so that only callers holding PROJECTS/EDIT (or an ADMIN via bypass) may register a client; a caller lacking this permission SHALL receive HTTP 403 with message code `error.access.denied`.
3. IF a client-registration request omits or supplies a blank `name` or `email`, or omits `projectId`, THEN THE endpoint SHALL reject the request with HTTP 400 and a validation error, before any user creation or membership assignment.
4. WHEN a valid client-registration request is received, THE server SHALL resolve the CLIENT_Role by its `code` `CLIENT` (never from the request) and create a UserEntity with that role, status `INVITED`, no password, the supplied `name`, `email`, and optional `phone`/`locale`, exactly as the FOR-03-02 invite flow creates users.
5. WHEN the CLIENT user is created, THE server SHALL dispatch the FOR-03-02 client-portal invitation email to the client's address, consistent with how the existing invite flow emails a newly created CLIENT.
6. WHEN the CLIENT user has been created, THE server SHALL create a ProjectMember_Assignment for that user on the supplied `projectId` under the CLIENT project role by invoking `ProjectMemberService.assign(userId, projectId, clientRoleId)`.
7. THE client creation and the ProjectMember_Assignment SHALL occur within a single transaction, so that a failure to assign the membership rolls back the user creation and no orphaned CLIENT user remains.
8. IF the supplied `email` already belongs to an existing user, THEN THE endpoint SHALL reject the request per the existing user-creation uniqueness behavior (HTTP 409 / the established duplicate-email error) and SHALL create no ProjectMember_Assignment.
9. IF a ProjectMember_Assignment already exists for the resolved user and the supplied `projectId`, THEN THE endpoint SHALL surface the `ProjectMemberService` duplicate outcome (HTTP 409 `error.project.member.duplicate`) and SHALL NOT leave a partially created client.
10. WHEN client registration succeeds, THE endpoint SHALL return the created client's identity (at least its `id`, `email`, and the assigned `projectId`) with an appropriate success status (HTTP 201).
11. THE Client_Registration_Endpoint SHALL be the only path through which a user with the CLIENT role is created; the generic `POST /api/users` create SHALL NOT be used to create CLIENT users (enforced by Requirement 11's dropdown exclusion on the frontend and, where feasible, server-side rejection of a CLIENT role on the generic create path).

---

### Requirement 11: Assignable-Role Dropdown Exclusion (Frontend)

**User Story:** As an administrator using the users page, I want the role dropdown to exclude ADMIN and CLIENT, so that I cannot accidentally assign a role that is managed elsewhere.

#### Acceptance Criteria

1. THE RoleSelect dropdown in the user create form SHALL exclude the `ADMIN` and `CLIENT` roles, offering only Assignable_Roles for selection.
2. THE RoleSelect dropdown in the user edit form SHALL likewise exclude the `ADMIN` and `CLIENT` roles from the selectable options.
3. THE exclusion SHALL be based on the role `code` (`ADMIN`, `CLIENT`), not on a display name or list position, so that renaming or reordering roles does not defeat the filter.
4. WHEN a user whose current role is `ADMIN` or `CLIENT` is opened in the edit form, THE form SHALL display that user's current role for context but SHALL NOT offer `ADMIN` or `CLIENT` as a newly selectable option in the dropdown list.
5. THE exclusion SHALL not remove `ADMIN` or `CLIENT` from any other consumer of `/api/roles` (for example the Roles management page); it applies only to the user-form RoleSelect.
