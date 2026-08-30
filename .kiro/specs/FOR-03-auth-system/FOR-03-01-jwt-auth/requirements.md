# Requirements Document

## Introduction

This specification defines JWT-based authentication for the Foremen backend (FOR-03-01, the first child spec of the FOR-03 auth system). It introduces stateless authentication using signed access tokens plus database-backed refresh tokens with rotation, password hashing with bcrypt, and the Spring Security filter chain that wires them together.

Scope is strictly limited to:
- Login, refresh, logout, and current-user (`/me`) endpoints
- `JwtTokenProvider` (token generation and validation) and `JwtAuthenticationFilter` (Bearer token extraction and `SecurityContext` population)
- Database migration adding `password_hash` and `status` to the `users` table, plus new `refresh_tokens` and `password_reset_tokens` tables
- User statuses: `INVITED`, `ACTIVE`, `DEACTIVATED`
- Configurable access-token and refresh-token lifetimes via environment/config properties
- A secure bootstrap mechanism for the single ADMIN account (no plaintext/default password stored in the repository or database seed)
- A backend-enforced prohibition on creating or promoting users to the ADMIN role through the normal user-management API
- A password reset flow (request by email, confirm with emailed token and new password)

The following are explicitly OUT of scope and belong to other FOR-03 child specs: the employee invite flow (FOR-03-02), the permission evaluator (FOR-03-03), project ownership (FOR-03-04), OTP client authentication (FOR-03-05), and all frontend work (FOR-03-06 / FOR-03-07). Wholesale replacement of `permitAll()` across existing controllers belongs to FOR-03-08; this spec only configures the auth-endpoint and filter-chain rules required for login to function.

This feature reuses existing FOR-01/FOR-02 infrastructure: `UserEntity` / `RoleEntity`, the `ForemenApiException` + `ForemenControllerAdvice` error handling, i18n error messages (PL/RU), Caffeine caching, Liquibase changesets, and the jqwik + JUnit 5 + Testcontainers testing conventions used in FOR-02-03.

## Glossary

- **Access_Token**: A signed JSON Web Token carrying claims `sub` (userId), `role` (role code), and `email`, presented in the `Authorization: Bearer` header. TTL configurable via `FOREMEN_JWT_ACCESS_TTL_MINUTES` (default 30 minutes). Not persisted server-side.
- **Refresh_Token**: An opaque, randomly generated token persisted in the `refresh_tokens` table and used to obtain a new Access_Token. TTL configurable via `FOREMEN_JWT_REFRESH_TTL_DAYS` (default 7 days). Subject to rotation and revocation.
- **JwtTokenProvider**: The component responsible for generating and validating Access_Tokens using a configured signing secret.
- **JwtAuthenticationFilter**: A Spring Security filter that extracts the Access_Token from the `Authorization` header, validates it, and populates the `SecurityContext`.
- **SecurityConfig**: The Spring Security configuration class (existing stub from FOR-01-10) defining the filter chain and endpoint authorization rules.
- **AuthController**: The REST controller exposing the authentication endpoints under `/api/auth`.
- **AuthService**: The service encapsulating authentication business logic (credential verification, token issuance, refresh, logout).
- **RefreshTokenEntity**: The JPA entity mapped to the `refresh_tokens` table.
- **RefreshTokenService**: The service managing creation, rotation, revocation, and validation of Refresh_Tokens.
- **UserEntity**: The existing JPA entity for a system user (`com.foremen.dao.model.UserEntity`), extended by this spec with `passwordHash` and `status`.
- **RoleEntity**: The existing JPA entity for a role, carrying a `code` field (ADMIN, MANAGER, FOREMAN, WORKER, FINANCIER, CLIENT).
- **User_Status**: An enumerated user lifecycle state, one of `INVITED`, `ACTIVE`, `DEACTIVATED`.
- **INVITED**: User_Status meaning the account exists but has not yet set a password; login is forbidden.
- **ACTIVE**: User_Status meaning the account has a password and may authenticate.
- **DEACTIVATED**: User_Status meaning the account is disabled and may not authenticate.
- **Bcrypt**: The password hashing algorithm used with cost factor 12.
- **Admin_Bootstrap**: A conditionally-registered startup bean (present only when `FOREMEN_ADMIN_CREATE=true`) that provisions or updates the single ADMIN user with a bcrypt-hashed password directly in the database, enforcing the single-admin and matching-email invariants.
- **Password_Reset_Token**: A single-use, time-limited token (TTL 60 minutes) persisted in the `password_reset_tokens` table, issued when an ACTIVE user requests a password reset and consumed when the user confirms a new password.
- **FOREMEN_JWT_ACCESS_TTL_MINUTES**: The environment variable (config property `foremen.jwt.access-ttl-minutes`) specifying the Access_Token lifetime in minutes, defaulting to 30 when unset.
- **FOREMEN_JWT_REFRESH_TTL_DAYS**: The environment variable (config property `foremen.jwt.refresh-ttl-days`) specifying the Refresh_Token lifetime in days, defaulting to 7 when unset.
- **UserService**: The existing service handling user creation and update through the user-management API.
- **ForemenApiException**: The existing application exception carrying an HTTP status, an i18n message code, and optional parameters.
- **ForemenControllerAdvice**: The existing global exception handler translating ForemenApiException into HTTP responses.

## Requirements

### Requirement 1: User Entity Extension — password_hash and status

**User Story:** As a developer, I want the UserEntity to carry a password hash and a lifecycle status, so that authentication and account-state rules can be enforced.

#### Acceptance Criteria

1. THE UserEntity SHALL contain a `passwordHash` field of type String, nullable, mapped to column `password_hash` of length 255.
2. THE UserEntity SHALL contain a `status` field representing User_Status, not-null, mapped to column `status` of length 20, with a default value of `INVITED`.
3. THE UserEntity SHALL restrict the `status` field to one of the values `INVITED`, `ACTIVE`, or `DEACTIVATED`.
4. THE UserEntity SHALL reside in the package `com.foremen.dao.model`.

---

### Requirement 2: Database Migration — users columns and refresh_tokens table

**User Story:** As a developer, I want Liquibase changesets that add the authentication columns and the refresh token table, so that the schema supports JWT authentication.

#### Acceptance Criteria

1. THE migration SHALL add a `password_hash` column of type `VARCHAR(255)`, nullable, to the `users` table.
2. THE migration SHALL add a `status` column of type `VARCHAR(20)` with default value `INVITED`, not-null, to the `users` table.
3. THE migration SHALL create a `refresh_tokens` table containing columns `id` (primary key), `token` (not-null, unique), `user_id` (not-null, foreign key referencing `users(id)`), `expires_at` (TIMESTAMP, not-null), `revoked` (BOOLEAN, not-null, default false), and `created_date` (TIMESTAMP, not-null).
4. THE migration SHALL create a `password_reset_tokens` table containing columns `id` (primary key), `token` (not-null, unique), `user_id` (not-null, foreign key referencing `users(id)`), `expires_at` (TIMESTAMP, not-null), `used` (BOOLEAN, not-null, default false), and `created_date` (TIMESTAMP, not-null).
5. THE migration SHALL register each new changeset in `database_files/changelog.xml`.
6. THE migration SHALL include Liquibase preConditions so that re-running the changesets on an already-migrated database performs no duplicate changes.

---

### Requirement 3: Login Endpoint

**User Story:** As a user, I want to log in with my email and password, so that I receive tokens to access protected resources.

#### Acceptance Criteria

1. THE AuthController SHALL expose `POST /api/auth/login` accepting a request body containing `email` and `password`.
2. IF a login request omits `email`, supplies a blank `email` (empty or whitespace-only), omits `password`, or supplies a blank `password` (empty or whitespace-only), THEN THE AuthController SHALL reject the request with HTTP 400 status and a validation error indicating the missing or blank field, before any credential verification is attempted.
3. WHEN a login request is received with an email that matches (case-insensitively) an ACTIVE user and a password whose bcrypt hash matches the stored `passwordHash`, THE AuthService SHALL return an Access_Token and a Refresh_Token.
4. IF a login request references an email that matches no user (case-insensitive comparison), THEN THE AuthService SHALL throw a ForemenApiException with HTTP 401 status and message code `error.auth.invalid.credentials`.
5. IF a login request supplies a password whose bcrypt hash does not match the stored `passwordHash`, THEN THE AuthService SHALL throw a ForemenApiException with HTTP 401 status and message code `error.auth.invalid.credentials`.
6. IF a login request references an email that matches no user OR supplies a password whose bcrypt hash does not match the stored `passwordHash`, THEN THE AuthService SHALL perform the bcrypt hash comparison against a fixed dummy hash in the no-user case so that the response time for both failure cases differs by no more than 100 milliseconds (timing-attack resistance).
7. IF a login request references a user whose status is `INVITED`, THEN THE AuthService SHALL throw a ForemenApiException with HTTP 403 status and message code `error.auth.account.not.activated`.
8. IF a login request references a user whose status is `DEACTIVATED`, THEN THE AuthService SHALL throw a ForemenApiException with HTTP 403 status and message code `error.auth.account.deactivated`.
9. WHEN a login request succeeds, THE login response SHALL contain the Access_Token, the Refresh_Token, and the Access_Token expiry duration in seconds equal to `FOREMEN_JWT_ACCESS_TTL_MINUTES` × 60 (default 1800).

---

### Requirement 4: Token Generation and Validation (JwtTokenProvider)

**User Story:** As a developer, I want a token provider that generates and validates access tokens, so that authentication is stateless and verifiable.

#### Acceptance Criteria

1. THE JwtTokenProvider SHALL generate an Access_Token containing the claims `sub` set to the user identifier, `role` set to the user role code, and `email` set to the user email.
2. THE JwtTokenProvider SHALL set the Access_Token expiry to the value of `FOREMEN_JWT_ACCESS_TTL_MINUTES` minutes (default 30) after the issuance timestamp, within a tolerance of ±2 seconds.
3. THE JwtTokenProvider SHALL sign each Access_Token using a signing secret read from application configuration.
4. WHEN the JwtTokenProvider validates an Access_Token with a valid signature and an expiry timestamp in the future relative to the current time, THE JwtTokenProvider SHALL report the token as valid and expose its claims.
5. IF the JwtTokenProvider validates an Access_Token whose signature does not match the configured secret, THEN THE JwtTokenProvider SHALL report the token as invalid.
6. IF the JwtTokenProvider validates an Access_Token whose expiry timestamp is equal to or earlier than the current time, THEN THE JwtTokenProvider SHALL report the token as invalid.
7. IF the JwtTokenProvider validates a token that is structurally malformed (not composed of three base64url-encoded segments separated by two periods, or containing an unparseable header or payload), THEN THE JwtTokenProvider SHALL report the token as invalid without throwing an unhandled exception.
8. IF the JwtTokenProvider validates an Access_Token with a valid signature and future expiry that is missing any of the `sub`, `role`, or `email` claims, THEN THE JwtTokenProvider SHALL report the token as invalid.
9. FOR ALL users, generating an Access_Token and then validating it SHALL yield claims equal to the sub, role, and email values used at generation (round-trip property).

---

### Requirement 5: JWT Authentication Filter

**User Story:** As a developer, I want a filter that reads the bearer token and establishes the security context, so that downstream handlers know the current user.

#### Acceptance Criteria

1. WHEN an incoming request carries an `Authorization` header with value prefixed by `Bearer ` followed by a valid Access_Token, THE JwtAuthenticationFilter SHALL populate the SecurityContext with an Authentication whose principal is the user identifier from the `sub` claim and whose authority is derived from the `role` claim.
2. WHEN an incoming request carries no `Authorization` header, THE JwtAuthenticationFilter SHALL pass the request along the filter chain without populating the SecurityContext.
3. IF an incoming request carries an `Authorization` header with a `Bearer` token that the JwtTokenProvider reports as invalid, THEN THE JwtAuthenticationFilter SHALL leave the SecurityContext unauthenticated and continue the filter chain.
4. WHEN an incoming request carries an `Authorization` header whose value does not begin with `Bearer `, THE JwtAuthenticationFilter SHALL pass the request along the filter chain without populating the SecurityContext.

---

### Requirement 6: Security Configuration for Authentication

**User Story:** As a developer, I want the security configuration to permit the auth endpoints and register the JWT filter, so that login works while other endpoints require authentication.

#### Acceptance Criteria

1. THE SecurityConfig SHALL permit unauthenticated access to all paths under `/api/auth/`.
2. THE SecurityConfig SHALL register the JwtAuthenticationFilter in the filter chain before the username-password authentication filter.
3. THE SecurityConfig SHALL configure the session creation policy as stateless.
4. IF a request targets an authenticated endpoint without a valid Access_Token, THEN THE SecurityConfig SHALL cause the response to carry HTTP 401 status.
5. THE SecurityConfig SHALL leave CSRF protection disabled, consistent with the stateless Bearer-token model.

---

### Requirement 7: Refresh Token Persistence and Rotation

**User Story:** As a user, I want to refresh my access token, so that my session continues without re-entering credentials.

#### Acceptance Criteria

1. WHEN a Refresh_Token is issued, THE RefreshTokenService SHALL persist a RefreshTokenEntity with the token value, the owning user, an expiry `FOREMEN_JWT_REFRESH_TTL_DAYS` days (default 7) after issuance, and `revoked` set to false.
2. THE AuthController SHALL expose `POST /api/auth/refresh` accepting a request body containing a `refreshToken` value.
3. WHEN a refresh request supplies a Refresh_Token that exists, is not revoked, and has an expiry in the future, THE AuthService SHALL issue a new Access_Token and a new Refresh_Token, and SHALL mark the supplied Refresh_Token as revoked (rotation).
4. IF a refresh request supplies a Refresh_Token value that matches no persisted RefreshTokenEntity, THEN THE AuthService SHALL throw a ForemenApiException with HTTP 401 status and message code `error.auth.refresh.invalid`.
5. IF a refresh request supplies a Refresh_Token whose `revoked` value is true, THEN THE AuthService SHALL throw a ForemenApiException with HTTP 401 status and message code `error.auth.refresh.revoked`.
6. IF a refresh request supplies a Refresh_Token whose expiry is in the past, THEN THE AuthService SHALL throw a ForemenApiException with HTTP 401 status and message code `error.auth.refresh.expired`.
7. FOR ALL refresh operations that succeed, THE previously supplied Refresh_Token SHALL be unusable for any subsequent refresh operation.

---

### Requirement 8: Logout Endpoint

**User Story:** As a user, I want to log out, so that my refresh token can no longer be used.

#### Acceptance Criteria

1. THE AuthController SHALL expose `POST /api/auth/logout` accepting a request body containing a `refreshToken` value.
2. WHEN a logout request supplies a Refresh_Token that matches a persisted RefreshTokenEntity, THE AuthService SHALL set that RefreshTokenEntity `revoked` value to true and return HTTP 204 status.
3. WHEN a logout request supplies a Refresh_Token that matches no persisted RefreshTokenEntity, THE AuthService SHALL return HTTP 204 status without error.
4. WHEN a Refresh_Token has been revoked through logout, THE AuthService SHALL reject any subsequent refresh request using that Refresh_Token per Requirement 7.

---

### Requirement 9: Current User Endpoint

**User Story:** As an authenticated user, I want to retrieve my own identity, role, and permissions, so that the client can adapt to my access rights.

#### Acceptance Criteria

1. THE AuthController SHALL expose `GET /api/auth/me` requiring a valid Access_Token.
2. WHEN an authenticated request reaches `GET /api/auth/me`, THE AuthService SHALL return the current user identifier, name, email, role code, and the set of permissions granted to the user role.
3. IF `GET /api/auth/me` is requested without a valid Access_Token, THEN THE SecurityConfig SHALL cause the response to carry HTTP 401 status.
4. WHEN returning permissions, THE AuthService SHALL derive them from the role-resource-operation associations of the current user role.

---

### Requirement 10: Password Hashing

**User Story:** As a security-conscious operator, I want passwords stored only as bcrypt hashes, so that plaintext credentials are never persisted.

#### Acceptance Criteria

1. WHEN a password is stored, THE AuthService SHALL hash the password using bcrypt with cost factor 12 before persisting it to `passwordHash`.
2. THE system SHALL persist only the bcrypt hash of a password and SHALL store no plaintext password value in the database.
3. WHEN verifying a login password, THE AuthService SHALL compare the supplied password against the stored bcrypt hash using a bcrypt verification function.
4. FOR ALL passwords, hashing a password and then verifying the same password against the produced hash SHALL report a match, and verifying a different password against that hash SHALL report no match.

---

### Requirement 11: Admin Account Bootstrap

**User Story:** As an operator deploying the system, I want a conditionally-registered startup bean that provisions or updates the single ADMIN account directly in the database when explicitly enabled, so that I can manage the first ADMIN securely without committing a password to the repository.

#### Acceptance Criteria

1. THE Admin_Bootstrap bean SHALL be registered in the application context only when the configuration flag `FOREMEN_ADMIN_CREATE` (property `foremen.admin.create`) equals `true`, enforced via a Spring conditional (e.g. `@ConditionalOnProperty`); WHEN the flag is `false`, unset, or any value other than `true`, THE Admin_Bootstrap bean SHALL NOT be created.
2. WHEN the Admin_Bootstrap bean is active, it SHALL execute once during application startup and read the admin email from `FOREMEN_ADMIN_EMAIL` and the admin password from `FOREMEN_ADMIN_PASSWORD`.
3. THE Admin_Bootstrap SHALL verify that at most one user whose role code equals `ADMIN` (case-sensitive, exact match) exists in the database; IF more than one ADMIN user exists, THEN THE Admin_Bootstrap SHALL fail application startup with a clear error.
4. WHEN the Admin_Bootstrap is active and no ADMIN user exists, THE Admin_Bootstrap SHALL create exactly one user with the ADMIN role, email set to `FOREMEN_ADMIN_EMAIL`, `passwordHash` set to the bcrypt hash (cost factor 12) of `FOREMEN_ADMIN_PASSWORD`, and status `ACTIVE`, persisting the row directly to the database.
5. IF the Admin_Bootstrap is active, exactly one ADMIN user already exists, and `FOREMEN_ADMIN_EMAIL` differs from that existing ADMIN's email (case-insensitive comparison), THEN THE Admin_Bootstrap SHALL fail application startup with a clear error and make no change to the database.
6. WHEN the Admin_Bootstrap is active, exactly one ADMIN user already exists, its email matches `FOREMEN_ADMIN_EMAIL`, and `FOREMEN_ADMIN_PASSWORD` is a non-empty value, THE Admin_Bootstrap SHALL update that ADMIN user's `passwordHash` to the bcrypt hash (cost factor 12) of `FOREMEN_ADMIN_PASSWORD`.
7. IF the Admin_Bootstrap is active and `FOREMEN_ADMIN_PASSWORD` is unset or an empty string, THEN THE Admin_Bootstrap SHALL fail application startup with a clear error indicating the admin password is required, making no change to the database.
8. IF the Admin_Bootstrap is active and `FOREMEN_ADMIN_EMAIL` is unset or an empty string, THEN THE Admin_Bootstrap SHALL fail application startup with a clear error indicating the admin email is required, making no change to the database.
9. THE system SHALL store no plaintext ADMIN password and no default ADMIN password in the repository, in a Liquibase seed, or in the database; the password SHALL be persisted only as a bcrypt hash produced at runtime by the bean.

---

### Requirement 12: Prohibition of ADMIN Role Assignment via User-Management API

**User Story:** As a system owner, I want the API to refuse creating or promoting users to ADMIN, so that ADMIN accounts can only originate from the secure bootstrap.

#### Acceptance Criteria

1. IF a user-creation request through the user-management API specifies a role whose code equals `ADMIN` (case-sensitive, exact match), THEN THE UserService SHALL reject the request with a ForemenApiException carrying HTTP 403 status and message code `error.user.admin.role.forbidden`, create no user, and do so regardless of the caller role.
2. IF a user-update request through the user-management API changes an existing user's role from a non-ADMIN role to a role whose code equals `ADMIN` (case-sensitive, exact match), THEN THE UserService SHALL reject the request with a ForemenApiException carrying HTTP 403 status and message code `error.user.admin.role.forbidden`, persist no change, and do so regardless of the caller role.
3. THE UserService SHALL enforce the ADMIN-role prohibition in the backend service layer independently of any frontend restriction.
4. WHEN a caller whose role code equals `ADMIN` submits a request to create or promote a user to a role whose code equals `ADMIN`, THE UserService SHALL reject the request per Requirement 12.1 and 12.2.
5. THE message code `error.user.admin.role.forbidden` SHALL have localized entries in the PL and RU message resources.
6. WHEN a user-update request leaves the user's role unchanged (including the abnormal case of an existing ADMIN whose role remains ADMIN) and introduces no non-ADMIN → ADMIN transition, THE UserService SHALL allow the update to proceed without raising the ADMIN-role prohibition.

---

### Requirement 13: Password Reset

**User Story:** As a user who forgot my password, I want to reset it via an emailed reset link, so that I can regain access without an administrator.

#### Acceptance Criteria

1. THE AuthController SHALL expose `POST /api/auth/password-reset/request` accepting a request body containing an `email`.
2. WHEN a password-reset request references an email matching an ACTIVE user, THE AuthService SHALL generate a single-use, time-limited Password_Reset_Token (TTL 60 minutes), persist it associated with that user, and send it to the user's email.
3. WHEN a password-reset request references an email that matches no user or a non-ACTIVE user, THE AuthService SHALL return HTTP 200 without disclosing whether the email exists (no user enumeration) and SHALL send no email.
4. THE AuthController SHALL expose `POST /api/auth/password-reset/confirm` accepting a request body containing a `token` and a `newPassword`.
5. WHEN a password-reset confirm request supplies a Password_Reset_Token that exists, is unused, and has not expired, together with a `newPassword` meeting the password policy, THE AuthService SHALL set the user's `passwordHash` to the bcrypt hash (cost factor 12) of `newPassword`, mark the token as used, and revoke all of that user's existing Refresh_Tokens.
6. IF a password-reset confirm request supplies a token that does not exist, is already used, or has expired, THEN THE AuthService SHALL throw a ForemenApiException with HTTP 400 status and message code `error.auth.reset.token.invalid`.
7. IF a password-reset confirm request supplies a `newPassword` that violates the password policy (minimum 8 characters), THEN THE AuthController SHALL reject the request with HTTP 400 status and a validation error.
8. WHEN a password reset completes successfully, THE previously issued Refresh_Tokens of that user SHALL be unusable for subsequent refresh operations.

---

### Requirement 14: Configurable Token Lifetimes

**User Story:** As an operator, I want the access-token and refresh-token lifetimes to be configurable via environment variables, so that I can tune session duration per deployment without changing code.

#### Acceptance Criteria

1. THE system SHALL read the access token lifetime from configuration property `foremen.jwt.access-ttl-minutes` (environment variable `FOREMEN_JWT_ACCESS_TTL_MINUTES`), interpreted as a number of minutes, defaulting to 30 when unset.
2. THE system SHALL read the refresh token lifetime from configuration property `foremen.jwt.refresh-ttl-days` (environment variable `FOREMEN_JWT_REFRESH_TTL_DAYS`), interpreted as a number of days, defaulting to 7 when unset.
3. WHEN `FOREMEN_JWT_ACCESS_TTL_MINUTES` is set to a positive integer N, THE JwtTokenProvider SHALL issue Access_Tokens expiring N minutes after issuance.
4. WHEN `FOREMEN_JWT_REFRESH_TTL_DAYS` is set to a positive integer M, THE RefreshTokenService SHALL issue Refresh_Tokens expiring M days after issuance.
5. IF either lifetime property is set to a non-positive or non-numeric value, THEN THE system SHALL fail application startup with a clear configuration error.

---

### Requirement 15: Error Message Localization

**User Story:** As a user, I want authentication errors in my language, so that I understand what went wrong.

#### Acceptance Criteria

1. THE system SHALL define localized PL and RU messages for the message codes `error.auth.invalid.credentials`, `error.auth.account.not.activated`, `error.auth.account.deactivated`, `error.auth.refresh.invalid`, `error.auth.refresh.revoked`, `error.auth.refresh.expired`, `error.auth.reset.token.invalid`, and `error.user.admin.role.forbidden`.
2. WHEN a ForemenApiException is raised with an authentication message code, THE ForemenControllerAdvice SHALL resolve the message according to the request locale.
