# Requirements Document

## Introduction

This specification defines invite-based registration for the Foremen backend (FOR-03-02, the second child spec of the FOR-03 auth system). It builds directly on FOR-03-01 (JWT authentication) and covers the flow whereby an administrator creates a user through the user-management API, the system issues a time-limited invite token and emails a role-appropriate invitation, and the invited user sets a password (or, for clients, is directed to the OTP portal) through a public set-password endpoint that activates the account and immediately logs the user in.

Scope is strictly limited to the backend:
- A new `invite_tokens` table (Liquibase migration) and the `InviteTokenEntity` / `InviteTokenDao` that back it, with an invite-token TTL of 72 hours.
- Invite-token generation and role-dependent invitation email dispatch triggered when a user is created through the user-management API (`POST /api/users`).
- A public `POST /api/auth/set-password` endpoint that validates an invite token, applies the password policy, sets the user's password and status, consumes the token, and returns JWT access and refresh tokens (auto-login).
- An admin-only `POST /api/auth/resend-invite` endpoint that regenerates the invite token and resends the invitation email.
- Role-dependent Thymeleaf email templates (employee set-password invitation versus client portal invitation) reusing the FOR-03-01 mail infrastructure.
- PL and RU localization of the new error message codes.

This feature reuses existing FOR-03-01 infrastructure and does not reinvent it: the `UserEntity.status` (`User_Status` INVITED/ACTIVE/DEACTIVATED) and `passwordHash` fields, the server-controlled always-INVITED user-create path (the user-management create and update request DTOs expose no `status` field, and the `UserServiceMapper` hard-codes `status` = INVITED on create regardless of any client input), the `AdminService` CRUD framework `validateCreate` hook used by `UserService`, the `BCryptPasswordEncoder` (cost factor 12) bean, `JwtTokenProvider` (access-token generation), `RefreshTokenService` (refresh-token issuance), the `TokenResponse` DTO, the `MailSender` / `SmtpMailSender` abstraction, the `ForemenApiException` + `ForemenControllerAdvice` error pipeline, the PL/RU message resources (`messages.properties` / `messages_ru.properties`), and the numbered Liquibase changeset pattern (`changelog.xml` + changesets with `preConditions onFail="MARK_RAN"`; the latest existing changeset is `012`). Because creating or promoting a user to ADMIN through the user-management API is already prohibited by FOR-03-01 (which returns HTTP 403 before any invite logic runs), the invite flow defined here only ever handles CLIENT users or non-client/non-admin (employee) users; an ADMIN user is never observed by this flow.

The following are explicitly OUT of scope and belong to other FOR-03 child specs: the permission evaluator (FOR-03-03), project ownership (FOR-03-04), OTP client authentication (FOR-03-05, including generation and verification of the client login code), the wholesale `permitAll()` migration and `@RequiresPermission` annotations across existing controllers (FOR-03-08), and ALL frontend work. In particular, the set-password page UI at `/auth/set-password?token=...` and the client OTP-login page belong to FOR-03-06 (frontend auth); this spec defines only the backend API contract that the frontend will consume. Where a CLIENT is invited, this spec sends an OTP-portal invitation email only — the OTP mechanism itself is FOR-03-05 and is not implemented here.

## Glossary

- **Invite_Token**: A single-use, time-limited token (UUID value, TTL 72 hours) persisted in the `invite_tokens` table, issued when a user is created through the user-management API and consumed when the invited user sets a password.
- **InviteTokenEntity**: The JPA entity mapped to the `invite_tokens` table, residing in package `com.foremen.dao.model`.
- **InviteTokenDao**: The data-access interface for `InviteTokenEntity`, residing in package `com.foremen.dao`.
- **InviteService**: The service encapsulating invite-token generation, invitation email dispatch, invite-token validation, and resend logic.
- **UserService**: The existing service (from FOR-01/FOR-02, extended in FOR-03-01) handling user creation and update through the user-management API via the `AdminService` CRUD-framework `validateCreate` / `validateUpdate` hooks; the user-create path always yields `status` = INVITED because the `UserServiceMapper` hard-codes it (see UserServiceMapper).
- **UserServiceMapper**: The MapStruct mapper that maps the user-management create and update request DTOs to a UserEntity; it hard-codes `status` = INVITED on create and never reads a `status` value from client input (the request DTOs expose no `status` field). This is the authoritative source of the INVITED status.
- **AuthController**: The existing REST controller (from FOR-03-01) exposing authentication endpoints under `/api/auth`, extended by this spec with `set-password` and `resend-invite`.
- **AuthService**: The existing service (from FOR-03-01) encapsulating authentication business logic, reused here for token issuance during set-password.
- **UserEntity**: The existing JPA entity for a system user (`com.foremen.dao.model.UserEntity`), carrying `email`, `passwordHash`, `status`, and a single `role` association.
- **RoleEntity**: The existing JPA entity for a role, carrying a `code` field (ADMIN, MANAGER, FOREMAN, WORKER, FINANCIER, CLIENT).
- **User_Status**: The existing enumerated user lifecycle state, one of `INVITED`, `ACTIVE`, `DEACTIVATED`.
- **INVITED**: User_Status meaning the account exists but has not yet set a password; login is forbidden until the account is activated.
- **ACTIVE**: User_Status meaning the account has a password and may authenticate.
- **Employee_Role**: A role whose code is neither `CLIENT` nor `ADMIN` (i.e. any non-client, non-admin role, including the built-in `MANAGER`, `FOREMAN`, `WORKER`, and `FINANCIER` roles as well as any custom role created via the roles admin).
- **Client_Role**: The role whose code equals `CLIENT`.
- **Set_Password_Invitation**: The invitation email variant sent to Employee_Role users, instructing the user to follow a link to set a password.
- **Client_Portal_Invitation**: The invitation email variant sent to Client_Role users, instructing the user to log in via an emailed OTP code (the OTP mechanism itself is FOR-03-05).
- **Invite_Link**: The URL embedded in a Set_Password_Invitation, of the form `{invite-base-url}?token={token}`, where `{invite-base-url}` is a configurable base URL (property `foremen.mail.invite-base-url`, environment variable `MAIL_INVITE_BASE_URL`, default `http://localhost:3000/auth/set-password`).
- **MailSender**: The existing mail abstraction (from FOR-03-01, `com.foremen.service.mail.MailSender`) with the `SmtpMailSender` Spring Mail implementation, reused and extended for invitation emails.
- **JwtTokenProvider**: The existing FOR-03-01 component that generates Access_Tokens.
- **RefreshTokenService**: The existing FOR-03-01 service that issues Refresh_Tokens.
- **TokenResponse**: The existing FOR-03-01 response record `{ accessToken, refreshToken, expiresIn }`.
- **BCryptPasswordEncoder**: The existing FOR-03-01 bean using bcrypt cost factor 12.
- **Password_Policy**: The rule requiring a password of at least 8 characters.
- **ForemenApiException**: The existing application exception carrying an HTTP status, an i18n message code, and optional parameters.
- **ForemenControllerAdvice**: The existing global exception handler translating ForemenApiException into HTTP responses.
- **AdminService**: The existing CRUD framework base service exposing the `validateCreate` / `validateUpdate` hooks and the default `create` path.
- **INVITE_TOKEN_TTL_HOURS**: The invite-token lifetime in hours; a configurable value (property `foremen.invite.ttl-hours`, environment variable `FOREMEN_INVITE_TTL_HOURS`) defaulting to 72 when unset.

## Requirements

### Requirement 1: Invite Token Entity

**User Story:** As a developer, I want an entity that records invite tokens with their owner, expiry, and consumption state, so that the invite lifecycle can be tracked and enforced.

#### Acceptance Criteria

1. THE InviteTokenEntity SHALL contain a `token` field of type String, mapped to a not-null and unique column, holding a canonical UUID string value of exactly 36 characters (32 lowercase hexadecimal digits and 4 hyphens).
2. WHEN a UUID string exceeding 36 characters or a null value is assigned to the `token` field, THEN THE persistence layer SHALL reject the persist operation and retain no InviteTokenEntity record.
3. THE InviteTokenEntity SHALL contain a `user` association referencing the owning UserEntity via a not-null `user_id` foreign key, using a @ManyToOne association with LAZY fetch, consistent with RefreshTokenEntity and PasswordResetTokenEntity.
4. THE InviteTokenEntity SHALL contain an `expiresAt` field of type Instant, not-null, mapped to column `expires_at`.
5. THE InviteTokenEntity SHALL contain a `used` field of type boolean, mapped to a not-null column, defaulting to false when a new instance is created and before it is explicitly set.
6. WHEN an InviteTokenEntity is persisted for the first time, THE InviteTokenEntity SHALL populate its `created_date` column, inherited from BaseEntity, with the creation timestamp.
7. THE InviteTokenEntity SHALL reside in the package `com.foremen.dao.model`.

---

### Requirement 2: Database Migration — invite_tokens table

**User Story:** As a developer, I want a Liquibase changeset that creates the invite_tokens table, so that the schema supports invite-based registration.

#### Acceptance Criteria

1. THE migration SHALL create an `invite_tokens` table containing the following columns with exact types and constraints, mirroring the `refresh_tokens` (011) and `password_reset_tokens` (012) changesets: `id` (BIGSERIAL, autoIncrement, primary key, not-null); `token` (VARCHAR(255), not-null, unique with uniqueConstraintName `uk_invite_tokens_token`); `user_id` (BIGINT, not-null, foreign key `fk_invite_tokens_user` referencing `users(id)`); `expires_at` (TIMESTAMP, not-null); `used` (BOOLEAN, not-null, defaultValueBoolean false); `created_date` (TIMESTAMP, not-null, defaultValueComputed `NOW()`); `created_by` (VARCHAR(255), nullable); `updated_date` (TIMESTAMP, nullable); and `updated_by` (VARCHAR(255), nullable).
2. THE migration SHALL define the new changeset in a file named `013-create-invite-tokens.xml`, placed sequentially after `012-create-password-reset-tokens.xml`, with changeSet attribute `id="013-create-invite-tokens"` and `author="foremen"`.
3. THE migration SHALL register the new changeset in `database_files/changelog.xml` such that the changeset is included in the executed migration set on application startup.
4. THE migration SHALL include a Liquibase precondition `<preConditions onFail="MARK_RAN"><not><tableExists tableName="invite_tokens"/></not></preConditions>` so that WHEN the changeset runs against a database where `invite_tokens` already exists, THE migration SHALL mark the changeset as ran without executing the createTable statement, leaving the existing table and its data unchanged.

---

### Requirement 3: Invite Token Generation on User Creation

**User Story:** As an administrator, I want an invite token generated automatically when I create a user, so that the invited user receives a way to activate the account.

#### Acceptance Criteria

1. WHEN a user is created through the user-management API (`POST /api/users`) and persisted with User_Status INVITED, THE InviteService SHALL generate exactly one Invite_Token whose `token` value is a randomly generated UUID string of 36 characters.
2. WHEN an Invite_Token is generated, THE InviteService SHALL set its `expiresAt` to the token creation instant plus INVITE_TOKEN_TTL_HOURS hours (default 72, valid range 1 to 8760), within a tolerance of ±5 seconds.
3. WHEN an Invite_Token is generated, THE InviteService SHALL persist it with `used` set to false and associated with the created UserEntity.
4. WHEN a user is created through the user-management API with User_Status INVITED, THE created UserEntity SHALL retain a null `passwordHash`.
5. FOR ALL user-creation requests that generate an Invite_Token, THE generated `token` value SHALL be unique across the `invite_tokens` table.
6. IF Invite_Token persistence fails after the UserEntity row has been persisted, THEN THE InviteService SHALL roll back the user-creation transaction so that neither the UserEntity nor the Invite_Token is retained, and SHALL return an error response indicating that invite-token generation failed.
7. THE user-management create request DTO and the user-management update request DTO SHALL NOT expose a `status` field, and THE UserServiceMapper SHALL set the created UserEntity `status` to INVITED unconditionally regardless of any client-supplied value, so that every user created through the user-management API is persisted with User_Status INVITED.
8. FOR ALL users created through the user-management API, because the created User_Status is always INVITED per criterion 7, THE InviteService SHALL generate exactly one Invite_Token, subject to the role-dependent invitation rules in Requirement 4.

---

### Requirement 4: Role-Dependent Invitation Email

**User Story:** As an invited user, I want an invitation email whose content matches my role, so that I know how to gain access.

#### Acceptance Criteria

1. WHEN an Invite_Token is generated for a user whose role code is an Employee_Role (any role code that is neither `CLIENT` nor `ADMIN`, including the built-in `MANAGER`, `FOREMAN`, `WORKER`, and `FINANCIER` roles and any custom role), THE InviteService SHALL send exactly one Set_Password_Invitation email to the user's email address.
2. WHEN an Invite_Token is generated for a user whose role code equals `CLIENT`, THE InviteService SHALL send exactly one Client_Portal_Invitation email to the user's email address.
3. WHEN a Set_Password_Invitation email is sent, THE email body SHALL contain an Invite_Link of the form `{invite-base-url}?token={token}` where `{token}` is the exact string value of the generated Invite_Token.
4. WHEN a Client_Portal_Invitation email is sent, THE email body SHALL instruct the recipient to log in using an OTP code delivered to the same email address, and SHALL NOT contain a set-password Invite_Link.
5. THE InviteService SHALL render invitation email bodies using Thymeleaf templates.
6. THE InviteService SHALL dispatch invitation emails through the existing MailSender abstraction.
7. WHEN an invitation email is rendered, THE InviteService SHALL select the email language from the recipient user's locale field, and IF that locale field is empty or not one of the supported locales (`PL`, `RU`), THEN THE InviteService SHALL render the email body in `PL`.
8. IF the MailSender abstraction fails to dispatch an invitation email, THEN THE InviteService SHALL return an error indicating that email delivery failed and SHALL retain the generated Invite_Token so the invitation can be resent.

---

### Requirement 5: Set-Password Endpoint

**User Story:** As an invited employee, I want to set my password using the invite link, so that my account becomes active and I am immediately logged in.

#### Acceptance Criteria

1. THE AuthController SHALL expose `POST /api/auth/set-password` accepting a request body containing a `token` and a `password`.
2. THE AuthController SHALL permit unauthenticated access to `POST /api/auth/set-password`.
3. IF a set-password request omits `token`, supplies a blank `token` (empty or whitespace-only), omits `password`, or supplies a `password` shorter than 8 characters or longer than 72 characters, THEN THE AuthController SHALL reject the request with HTTP 400 status and a validation error indicating the invalid field, before any token lookup is attempted, and SHALL make no change to any user or token.
4. WHEN a set-password request supplies an Invite_Token that exists, has `used` equal to false, and has an `expiresAt` strictly later than the current server time, AND the owning user's User_Status is INVITED, together with a `password` between 8 and 72 characters inclusive meeting the Password_Policy, THE AuthService SHALL within a single atomic transaction set the owning user's `passwordHash` to the bcrypt hash (cost factor 12) of the supplied `password`, set the owning user's User_Status to ACTIVE, and set the Invite_Token `used` to true.
5. WHEN a set-password request completes successfully, THE AuthService SHALL return an Access_Token and a Refresh_Token for the owning user (TokenResponse {accessToken, refreshToken, expiresIn}) with HTTP 200 status, using the FOR-03-01 JwtTokenProvider and RefreshTokenService, so that the user is immediately authenticated.
6. IF a set-password request supplies a `token` value that matches no persisted InviteTokenEntity, THEN THE AuthService SHALL throw a ForemenApiException with HTTP 400 status and message code `error.invite.token.invalid`, and SHALL make no change to any user or token.
7. IF a set-password request supplies an Invite_Token whose `expiresAt` is equal to or earlier than the current server time, THEN THE AuthService SHALL throw a ForemenApiException with HTTP 400 status and message code `error.invite.token.expired`, and SHALL make no change to the owning user or the token.
8. IF a set-password request supplies an Invite_Token whose `used` value is true, THEN THE AuthService SHALL throw a ForemenApiException with HTTP 400 status and message code `error.invite.token.used`, and SHALL make no change to the owning user or the token.
9. FOR ALL set-password requests that succeed, THE consumed Invite_Token SHALL cause any subsequent set-password request presenting the same token value to be rejected per criterion 8 (HTTP 400, message code `error.invite.token.used`).
10. IF a set-password request supplies a valid unused unexpired Invite_Token whose owning user's User_Status is DEACTIVATED, THEN THE AuthService SHALL reject the request with a ForemenApiException carrying HTTP 409 status and message code `error.invite.user.already.active`, make no change to the owning user, and leave the Invite_Token `used` value unchanged (false).

---

### Requirement 6: Resend Invite Endpoint

**User Story:** As an administrator, I want to resend an invitation, so that a user whose invite expired or was lost can still activate the account.

#### Acceptance Criteria

1. THE AuthController SHALL expose `POST /api/auth/resend-invite` accepting a request body identifying the target user by a non-blank `userId`.
2. THE AuthController SHALL restrict `POST /api/auth/resend-invite` to callers whose authenticated principal holds the `ROLE_ADMIN` authority granted by the FOR-03-01 JwtAuthenticationFilter (from the caller's role claim), enforced via Spring Security authorization rather than the FOR-03-03 permission evaluator, which is not implemented in this spec.
3. IF a resend-invite request omits `userId` or supplies a blank (empty or whitespace-only) `userId`, THEN THE AuthController SHALL reject the request with HTTP 400 status and a validation error, before any user lookup is attempted.
4. IF a resend-invite request is made by an unauthenticated caller (no valid Access_Token presented), THEN THE system SHALL reject the request with HTTP 401 status and make no change and send no email.
5. IF a resend-invite request is made by an authenticated caller whose principal does not hold the `ROLE_ADMIN` authority, THEN THE system SHALL reject the request with HTTP 403 status and make no change and send no email.
6. WHEN a resend-invite request references a user whose User_Status is INVITED, THE InviteService SHALL mark any existing unused Invite_Token for that user as used (setting its `used` field to true so it can no longer activate the account per Requirement 5), generate a new Invite_Token (randomly generated UUID string value, `expiresAt` set to the creation instant plus INVITE_TOKEN_TTL_HOURS hours within a tolerance of ±5 seconds, `used` set to false), and send the role-dependent invitation email per Requirement 4.
7. IF a resend-invite request references a `userId` that matches no user, THEN THE system SHALL respond with a ForemenApiException carrying HTTP 404 status and message code `error.invite.user.not.found`, and SHALL make no change and send no email.
8. IF a resend-invite request references a user whose User_Status is ACTIVE, THEN THE InviteService SHALL reject the request with a ForemenApiException carrying HTTP 409 status and message code `error.invite.user.already.active`, and SHALL make no change and send no email.
9. IF a resend-invite request references a user whose User_Status is DEACTIVATED, THEN THE InviteService SHALL reject the request with a ForemenApiException carrying HTTP 409 status and message code `error.invite.user.already.active`, and SHALL make no change and send no email.

---

### Requirement 7: Invitation Base URL and Token Lifetime Configuration

**User Story:** As an operator, I want the set-password link base URL and invite-token lifetime to be configurable, so that invitation links point to the correct frontend and tokens expire per deployment policy.

#### Acceptance Criteria

1. THE system SHALL read the invitation base URL from configuration property `foremen.mail.invite-base-url` (environment variable `MAIL_INVITE_BASE_URL`), defaulting to `http://localhost:3000/auth/set-password` when unset.
2. IF the configured invitation base URL is empty, blank, or not a syntactically valid absolute HTTP or HTTPS URL, THEN THE system SHALL fail application startup with a configuration error indicating the invalid invitation base URL property.
3. WHEN a Set_Password_Invitation email is composed, THE InviteService SHALL construct the Invite_Link by appending the query parameter `token={token}` to the configured invitation base URL, using `?token={token}` when the configured URL contains no `?` character and `&token={token}` when it already contains a `?` character.
4. WHEN a Set_Password_Invitation email is composed, THE InviteService SHALL URL-encode the token value before appending it as the `token` query parameter.
5. THE system SHALL read the invite-token lifetime from configuration property `foremen.invite.ttl-hours` (environment variable `FOREMEN_INVITE_TTL_HOURS`), interpreted as an integer number of hours in the range 1 to 8760 inclusive, defaulting to 72 when unset.
6. IF the invite-token lifetime property is set to a non-numeric value, a non-integer value, or an integer outside the range 1 to 8760 inclusive, THEN THE system SHALL fail application startup with a configuration error indicating the invalid invite-token lifetime property.

---

### Requirement 8: Error Message Localization

**User Story:** As an invited user, I want invitation errors in my language, so that I understand what went wrong.

#### Acceptance Criteria

1. THE system SHALL define localized message text in Polish (base `messages.properties`) and Russian (`messages_ru.properties`) for each of the message codes `error.invite.token.invalid`, `error.invite.token.expired`, `error.invite.token.used`, `error.invite.user.not.found`, and `error.invite.user.already.active`, such that every listed code resolves to a non-empty value in both files.
2. THE system SHALL define localized text in Polish (base `messages.properties`) and Russian (`messages_ru.properties`) for the subject and body message codes of both the Set_Password_Invitation and Client_Portal_Invitation email templates, such that each subject and body code resolves to a non-empty value in both files.
3. WHEN a ForemenApiException carrying an invitation message code is raised during an HTTP request, THE ForemenControllerAdvice SHALL resolve the message using the request locale held in LocaleContextHolder (derived from the `foremen-language` header, defaulting to Polish when the header is absent or its value is not one of PL or RU).
4. WHEN an invitation email is composed server-side, THE system SHALL resolve the email subject and body using the recipient user's stored `locale` field (UserEntity.locale) rather than any HTTP request locale, and SHALL fall back to Polish when the stored `locale` value is neither PL nor RU.
5. IF a required invitation message code has no defined value for the resolved locale, THEN THE system SHALL fall back to the Polish (base `messages.properties`) value for that code.
