# Requirements Document

## Introduction

This specification defines the frontend authentication layer for the Foremen web client (FOR-03-06, the sixth child spec of the FOR-03 auth system). It delivers the client-side plumbing and pages that let employees and clients sign in, keep a session alive across access-token expiry, and sign out. It consumes the already-implemented backend auth contract from FOR-03-01 (JWT login, refresh, logout, `/me`) and FOR-03-02 (invite / set-password) without redesigning that contract.

The feature introduces:

- A Zustand **Auth_Store** (`src/stores/auth-store.ts`) holding the current user, the access and refresh tokens, an authentication flag, and actions for login, logout, refresh, and session hydration.
- Persistence of the access and refresh tokens in `localStorage`.
- A shared **Api_Client** (`src/lib/api-client.ts`) fetch wrapper that automatically attaches `Authorization: Bearer <accessToken>` and, on a `401` response, performs a single automatic token refresh (with refresh-token rotation) and retries the original request once; on refresh failure it clears the session and redirects to `/login`. Concurrent requests that receive `401` at the same time trigger only one refresh (dedupe / queue), and each waiting request retries with the new token.
- Session hydration on application load via `GET /api/auth/me` so a returning user with a stored token lands authenticated.
- Client-side caching of the Current_User keyed by the ETag returned from `GET /api/auth/me`, using conditional requests (`If-None-Match`) so revalidation is cheap and a `304 Not Modified` reuses the cached identity.
- A **LoginPage** (email + password → `POST /api/auth/login`, plus a **Sign in with Google** control) for employees.
- A **SetPasswordPage** (invite token from `?token=` → `POST /api/auth/set-password` → auto-login) for invited employees activating their account.
- An **OtpLoginPage** for CLIENT users (enter email → `POST /api/auth/otp/request`; enter a 6-digit code in a modern segmented one-time-code input → `POST /api/auth/otp/verify`) with paste support, auto-submit, and a resend cooldown timer.
- A **Sign in with Google** flow that obtains a Google ID token on the client and exchanges it at the backend for the application's JWT pair, succeeding only when the Google email is linked to an existing account.
- **Return-to-last-page (deep-link preservation)**: when a user is sent to `/login` because their session expired mid-work or because they deep-linked to a protected route while unauthenticated, the app captures the attempted protected location and returns there after a successful re-login.
- A basic **authenticated-only route guard** that redirects unauthenticated users to `/login`, with a fixed set of public (unauthenticated) routes: `/login`, `/auth/set-password`, `/auth/otp`.
- Logout via `POST /api/auth/logout` (revoke refresh token) plus local-storage clearing and redirect to `/login`.
- RU/PL localization of all auth UI strings and surfacing of localized backend error messages, plus accessible forms and explicit loading/disabled states.

This spec also delivers two backend additions that extend the FOR-03 `OVERVIEW.md`:

- **`GET /api/auth/me` ETag / conditional-request support** (strong `ETag`, `Cache-Control`, and `If-None-Match` → `304` handling) — see Requirement 13.
- **Google ID-token exchange endpoint** (`POST /api/auth/google`) plus Google account linking and OAuth client configuration — see Requirement 14.

The Api_Client is the delivery vehicle for attaching the JWT to outbound requests. Existing feature fetchers (for example `src/features/users/api/users-api.ts`) currently use bare `fetch` with only an `Accept-Language` header and their own local `ApiError` class, and do not attach any `Authorization` header. This spec delivers the Api_Client itself and MAY route the existing auth-adjacent calls through it, but the wholesale migration of every existing controller call onto the Api_Client is broader FOR-03-08 work and is not required here beyond what the auth pages need.

### Out of scope

The following belong to **FOR-03-07-menu-visibility** (the next spec) and are explicitly OUT of scope here:

- Permission-based route guards (route access decided by resource/operation permissions).
- Menu and action visibility driven by permissions.
- The `usePermission` hook.
- The `/403` (Forbidden) page.

This spec handles **authentication** (is the user logged in) only, not **authorization** (what the user may see or do). It MAY add an authenticated-only guard that redirects anonymous users to `/login`, but it MUST NOT implement permission/resource/operation-based visibility. Backend endpoints and DTOs are consumed as already implemented in FOR-03-01/02/05 and are not redesigned here. The one existing local `ApiError` class in `users-api.ts` and full migration of other feature fetchers onto the Api_Client are deferred to FOR-03-08.

### Backend contract consumed (already implemented — do not redesign)

- `POST /api/auth/login` `{ email, password }` → `200` `TokenResponse { accessToken, refreshToken, expiresIn }` (expiresIn in seconds); `401` invalid credentials (`error.auth.invalid.credentials`); `403` `error.auth.account.not.activated` (INVITED) or `error.auth.account.deactivated`.
- `POST /api/auth/refresh` `{ refreshToken }` → `200` `TokenResponse` (rotation: old refresh revoked); `401` invalid/revoked/expired.
- `POST /api/auth/logout` `{ refreshToken }` → `204` (idempotent).
- `GET /api/auth/me` (Bearer) → `200` `CurrentUserResponse { id, name, email, roleCode, permissions: [{ resource, operations[] }] }`; `401` if no/invalid token.
- `POST /api/auth/set-password` `{ token, password }` → `200` `TokenResponse` (auto-login); `400` invalid/expired/used token; password must be 8..72 chars.
- `POST /api/auth/otp/request` `{ email }` → `200` always (silent success / anti-enumeration).
- `POST /api/auth/otp/verify` `{ email, code }` → `200` `TokenResponse` (client TTLs); `400` invalid/expired/attempts-exceeded (`error.auth.otp.invalid` / `error.auth.otp.expired` / `error.auth.otp.attempts.exceeded`); `429` rate-limited (`error.auth.otp.rate.limited`).
- Error bodies follow the `ForemenApiException` shape and are localized server-side via `Accept-Language` (RU/PL).

### Backend additions delivered by this spec

The two capabilities below are delivered as part of this spec (FOR-03-06) and extend the FOR-03 `OVERVIEW.md`. They are consumed by the frontend flows described in Requirements 13 and 14.

- **`GET /api/auth/me` conditional requests** — the backend MUST emit a strong `ETag` (and appropriate `Cache-Control`) on `GET /api/auth/me`, honor an inbound `If-None-Match` header, and return `304 Not Modified` (empty body) when the representation is unchanged. The `ETag` MUST change whenever the user's role, the role's permissions, the user's role membership, or the user's identity/profile changes, and MUST NOT change otherwise. Consumed by Requirement 13.
- **Google ID-token exchange** — a new endpoint (e.g. `POST /api/auth/google` `{ idToken }`) that verifies the Google ID token against the configured Google client, looks up the user by the verified Google email, and returns one of three outcomes based on the matched account status: (a) when the matched account is `ACTIVE`, `200` `TokenResponse` (normal login); (b) when the matched account is `INVITED` (not yet activated — has not confirmed email ownership by setting a password), `200` with a distinct Activation_Required response carrying a freshly-minted Set_Password_Token (machine-detectable activation-required marker plus a set-password token; e.g. `{ status: "ACTIVATION_REQUIRED", setPasswordToken: "<token>" }`, exact field names left to design) and NO access/refresh session; (c) when the matched account is `DEACTIVATED`, the existing deactivated error (reusing the existing message code, no session, no token); and (d) when no account is linked to the verified email, the distinct no-account error (e.g. `403`/`404` with message code `error.auth.google.no.account`). The Set_Password_Token is equivalent to the FOR-03-02 invite token (same TTL and semantics) and is minted only because Google cryptographically verified email ownership. Requires configuration of the Google OAuth client id on the frontend (env var, e.g. `VITE_GOOGLE_CLIENT_ID`) and the backend client/secret, plus Google account linking on user records. Consumed by Requirement 14.

## Glossary

- **Auth_Store**: The Zustand store at `src/stores/auth-store.ts` holding `user`, `accessToken`, `refreshToken`, `isAuthenticated`, and the actions `login`, `logout`, `refresh`, and `hydrate`.
- **Current_User**: The object returned by `GET /api/auth/me`: `{ id, name, email, roleCode, permissions }`. In this spec the `permissions` array is stored but not consumed for visibility (deferred to FOR-03-07).
- **Access_Token**: The short-lived JWT presented in the `Authorization: Bearer` header, returned by the backend as `accessToken`.
- **Refresh_Token**: The opaque token returned as `refreshToken`, exchanged at `POST /api/auth/refresh` for a new token pair; subject to server-side rotation.
- **TokenResponse**: The backend response DTO `{ accessToken, refreshToken, expiresIn }` returned by login, refresh, set-password, and OTP-verify.
- **Token_Storage**: The `localStorage` persistence of `Access_Token` and `Refresh_Token` under fixed keys, used to survive page reloads.
- **Api_Client**: The shared fetch wrapper at `src/lib/api-client.ts` that attaches the `Authorization` header, attaches `Accept-Language`, performs single automatic refresh-and-retry on `401`, and surfaces a typed error carrying HTTP status and localized message.
- **Api_Error**: The typed error the Api_Client throws for non-OK responses, carrying the HTTP status, the backend message code (when present), and the localized message text from the response body.
- **Refresh_In_Flight**: The single shared refresh operation that the Api_Client reuses so that multiple concurrent `401` responses trigger at most one `POST /api/auth/refresh`.
- **Session_Hydration**: The application-load step that reads a stored token, calls `GET /api/auth/me`, and populates the Auth_Store, or clears the session when no valid token exists.
- **LoginPage**: The route component at `/login` presenting the employee email + password form.
- **SetPasswordPage**: The route component at `/auth/set-password` that reads an invite token from the `token` query parameter and presents a new-password form.
- **OtpLoginPage**: The route component at `/auth/otp` presenting the CLIENT two-step email-then-code form.
- **Public_Route**: A route reachable without authentication; the set is exactly `/login`, `/auth/set-password`, and `/auth/otp`.
- **Auth_Guard**: The component/logic that permits an unauthenticated user to reach only Public_Routes and otherwise redirects to `/login`.
- **Locale**: The active UI language, either `pl` or `ru`, persisted in `localStorage` under the existing key `foremen-locale` and used both for i18n string selection and the outgoing `Accept-Language` header.
- **Return_Location**: The captured attempted or last protected location (path plus query string) that a user could not reach because authentication was required; after a successful re-login the app navigates the user back to this location. It is never set to a Public_Route.
- **Me_Cache**: The client-side cache of the last Current_User together with the ETag returned by `GET /api/auth/me`, used with conditional requests so a `304 Not Modified` reuses the cached Current_User without replacing it.
- **Me_ETag**: The strong entity tag returned by `GET /api/auth/me`, stored alongside the cached Current_User and sent back as `If-None-Match` on subsequent `/me` fetches.
- **Google_Sign_In**: The client-side Google Identity flow that obtains a Google ID token, which is then exchanged at the backend for a TokenResponse.
- **Google_ID_Token**: The identity token issued by Google Identity on the client after the user authenticates with Google; sent to the backend for verification and exchange.
- **Google_Exchange_Endpoint**: The backend endpoint (e.g. `POST /api/auth/google`) that verifies a Google_ID_Token, looks up the linked account by verified email, and returns one of: a TokenResponse (ACTIVE account), an Activation_Required response with a Set_Password_Token (INVITED account), the existing deactivated error (DEACTIVATED account), or a distinct no-account error (no linked account).
- **Activation_Required**: The distinct successful (`HTTP 200`) Google_Exchange_Endpoint response returned when the matched account status is INVITED. It carries a machine-detectable activation-required marker and a Set_Password_Token, and it does NOT carry an Access_Token or Refresh_Token (no session is issued). Example shape `{ status: "ACTIVATION_REQUIRED", setPasswordToken: "<token>" }` (exact field names left to design).
- **Set_Password_Token**: The short-lived invite/set-password token carried by an Activation_Required response, equivalent to the FOR-03-02 invite token in TTL and semantics, minted only after Google cryptographically verifies email ownership. It is consumed by the SetPasswordPage at `/auth/set-password?token=<Set_Password_Token>`.

## Requirements

### Requirement 1: Auth Store and Token Storage

**User Story:** As a returning user, I want my session and tokens held in one place and persisted, so that my authentication state is consistent across the app and survives a page reload.

#### Acceptance Criteria

1. THE Auth_Store SHALL expose the state fields `user` (Current_User or null), `accessToken` (string or null), `refreshToken` (string or null), and `isAuthenticated` (boolean).
2. THE Auth_Store SHALL expose the actions `login`, `logout`, `refresh`, and `hydrate`.
3. WHEN the Auth_Store records a new TokenResponse, THE Auth_Store SHALL write the `accessToken` and `refreshToken` to Token_Storage in `localStorage` under fixed keys.
4. WHEN the Auth_Store clears the session, THE Auth_Store SHALL remove the stored `accessToken` and `refreshToken` from `localStorage` and set `user` to null, `accessToken` to null, `refreshToken` to null, and `isAuthenticated` to false.
5. WHILE a non-null `user` and a non-null `accessToken` are present in the Auth_Store, THE Auth_Store SHALL report `isAuthenticated` as true.
6. IF reading from or writing to `localStorage` raises an error (for example storage unavailable or quota exceeded), THEN THE Auth_Store SHALL continue operating from in-memory state without throwing to the caller.
7. THE Auth_Store SHALL be the single source of truth for the current Access_Token used by the Api_Client when attaching the `Authorization` header.

---

### Requirement 2: Api Client — Authorization and Language Header Attachment

**User Story:** As a developer, I want a shared request wrapper that attaches the bearer token and language, so that authenticated calls carry the JWT without each caller re-implementing it.

#### Acceptance Criteria

1. WHEN the Api_Client issues a request and a non-null Access_Token is present in the Auth_Store, THE Api_Client SHALL attach the header `Authorization: Bearer <accessToken>`.
2. WHEN the Api_Client issues a request and no Access_Token is present, THE Api_Client SHALL issue the request without an `Authorization` header.
3. THE Api_Client SHALL attach an `Accept-Language` header whose value is `ru` when the active Locale is `ru` and `pl` for every other Locale value including unset (PL fallback), consistent with the existing `foremen-locale` convention.
4. WHEN a request specifies a JSON body, THE Api_Client SHALL attach the header `Content-Type: application/json`.
5. WHEN the Api_Client receives a non-OK response, THE Api_Client SHALL throw an Api_Error carrying the HTTP status code and, when the response body follows the ForemenApiException shape, the backend message text.
6. IF a non-OK response body cannot be parsed as JSON, THEN THE Api_Client SHALL throw an Api_Error carrying the HTTP status code and a generic fallback message, without throwing an unhandled parsing exception.

---

### Requirement 3: Api Client — Automatic Refresh and Retry on 401

**User Story:** As a user with a long session, I want expired access tokens to be refreshed automatically, so that my requests succeed without me logging in again.

#### Acceptance Criteria

1. WHEN the Api_Client receives a `401` response to a request other than `POST /api/auth/refresh` and a non-null Refresh_Token is present, THE Api_Client SHALL invoke `POST /api/auth/refresh` with the stored Refresh_Token exactly once before retrying.
2. WHEN a refresh call returns `200` with a new TokenResponse, THE Api_Client SHALL store the new Access_Token and Refresh_Token in the Auth_Store and retry the original request one time with the new Access_Token attached.
3. THE Api_Client SHALL retry the original request at most one time per original request, so that a `401` on the retried request does not trigger a further refresh.
4. WHEN multiple requests receive a `401` while a refresh is already in progress, THE Api_Client SHALL reuse the single Refresh_In_Flight operation rather than starting an additional refresh, and each waiting request SHALL retry with the resulting new Access_Token.
5. IF the refresh call returns a non-`200` response (invalid, revoked, or expired Refresh_Token), THEN THE Api_Client SHALL capture the current protected location as the Return_Location, clear the session via the Auth_Store, redirect the browser to `/login`, and surface the failure to the original caller as an Api_Error.
6. IF no Refresh_Token is present when a `401` is received, THEN THE Api_Client SHALL capture the current protected location as the Return_Location, clear the session, and redirect to `/login` without attempting a refresh.
7. WHEN a refresh succeeds, THE Api_Client SHALL use the rotated Refresh_Token returned by the backend for any subsequent refresh, discarding the previous Refresh_Token.
8. WHEN the Api_Client captures a Return_Location on a forced logout, IF the current location is a Public_Route, THEN THE Api_Client SHALL NOT set the Return_Location, so that a Public_Route is never used as a return target.

---

### Requirement 4: Session Hydration on Application Load

**User Story:** As a returning user, I want the app to recognize my stored session on load, so that I am not forced to log in again while my session is still valid.

#### Acceptance Criteria

1. WHEN the application loads and a stored Access_Token exists in Token_Storage, THE Auth_Store SHALL call `GET /api/auth/me` through the Api_Client to obtain the Current_User during Session_Hydration.
2. WHEN `GET /api/auth/me` returns `200`, THE Auth_Store SHALL populate `user` with the Current_User and set `isAuthenticated` to true.
3. WHEN `GET /api/auth/me` returns `401` and the automatic refresh in the Api_Client also fails, THE Auth_Store SHALL clear the session so that the user is treated as unauthenticated.
4. WHEN the application loads and no stored Access_Token exists, THE Auth_Store SHALL treat the user as unauthenticated without calling `GET /api/auth/me`.
5. WHILE Session_Hydration is in progress, THE application SHALL present a loading indication and SHALL NOT prematurely redirect an as-yet-undetermined session to `/login`.
6. WHEN a login, set-password, OTP-verify, or Google_Sign_In flow succeeds, THE Auth_Store SHALL populate the Current_User by calling `GET /api/auth/me` so that `user` reflects the authenticated identity.

---

### Requirement 5: Employee Login Flow

**User Story:** As an employee, I want to sign in with my email and password, so that I can access the application.

#### Acceptance Criteria

1. THE LoginPage SHALL present an email field, a password field, a submit control, and a Sign-in-with-Google control at route `/login`.
2. IF the email field is empty or the password field is empty on submit, THEN THE LoginPage SHALL block submission and present a field-level validation message without calling the backend.
3. WHEN the LoginPage form is submitted with a non-empty email and password, THE LoginPage SHALL call `POST /api/auth/login` with `{ email, password }` through the Api_Client.
4. WHEN `POST /api/auth/login` returns `200` with a TokenResponse, THE Auth_Store SHALL store the tokens and hydrate the Current_User via `GET /api/auth/me`, and THE LoginPage SHALL navigate to the saved Return_Location when one is present and otherwise to the application home route (`/`).
5. IF `POST /api/auth/login` returns `401`, THEN THE LoginPage SHALL present the localized invalid-credentials message from the backend response.
6. IF `POST /api/auth/login` returns `403` (account not activated or deactivated), THEN THE LoginPage SHALL present the corresponding localized backend message.
7. WHILE a login request is in flight, THE LoginPage SHALL disable the submit control and present a loading indication.
8. WHILE the user is already authenticated, THE LoginPage SHALL redirect away from `/login` to the saved Return_Location when one is present and otherwise to the application home route.

---

### Requirement 6: Set-Password (Invite Activation) Flow

**User Story:** As an invited employee, I want to set my password from the invitation link, so that I can activate my account and sign in.

> **Note:** The `token` consumed here may originate either from a FOR-03-02 email invitation link or from the Google bridge (a Set_Password_Token minted by the Google_Exchange_Endpoint for an INVITED account — see Requirement 14). The set-password behavior below is identical regardless of token origin.

#### Acceptance Criteria

1. THE SetPasswordPage SHALL read the invite token (an invite token from FOR-03-02 or a Set_Password_Token from the Google bridge per Requirement 14) from the `token` query parameter of the `/auth/set-password` URL.
2. IF the `token` query parameter is absent or empty, THEN THE SetPasswordPage SHALL present an invalid-link message and SHALL NOT submit to the backend.
3. THE SetPasswordPage SHALL present a new-password field and a confirm-password field.
4. IF the new-password value is shorter than 8 characters or longer than 72 characters, THEN THE SetPasswordPage SHALL block submission and present a validation message reflecting the 8..72 character policy.
5. IF the confirm-password value does not equal the new-password value, THEN THE SetPasswordPage SHALL block submission and present a mismatch validation message.
6. WHEN the SetPasswordPage form is submitted with a valid password and a present token, THE SetPasswordPage SHALL call `POST /api/auth/set-password` with `{ token, password }`.
7. WHEN `POST /api/auth/set-password` returns `200` with a TokenResponse, THE Auth_Store SHALL store the tokens and hydrate the Current_User (auto-login), and THE SetPasswordPage SHALL navigate to the saved Return_Location when one is present and otherwise to the application home route.
8. IF `POST /api/auth/set-password` returns `400` (invalid, expired, or used token), THEN THE SetPasswordPage SHALL present the localized backend error message.
9. WHILE a set-password request is in flight, THE SetPasswordPage SHALL disable the submit control and present a loading indication.

---

### Requirement 7: Client OTP Login Flow

**User Story:** As a client, I want to sign in with an emailed code, so that I can access my project portal without a password.

#### Acceptance Criteria

1. THE OtpLoginPage SHALL present, at route `/auth/otp`, a first step containing an email field and a request-code control.
2. WHEN the email step is submitted with a non-empty email, THE OtpLoginPage SHALL call `POST /api/auth/otp/request` with `{ email }` and, on `200`, advance to a code-entry step regardless of whether the email is an eligible client (mirroring the backend anti-enumeration silent success).
3. IF the email field is empty on submit, THEN THE OtpLoginPage SHALL block the request-code submission and present a field-level validation message.
4. THE OtpLoginPage code-entry step SHALL present a segmented one-time-code input of exactly 6 boxes, one decimal digit per box, and a verify control, and SHALL retain the email entered in the first step for use in verification.
5. WHEN a digit is entered into a code box, THE OtpLoginPage SHALL advance focus to the next box, and WHEN Backspace is pressed in an empty code box, THE OtpLoginPage SHALL move focus to the previous box.
6. WHEN a 6-digit value is pasted into any code box, THE OtpLoginPage SHALL distribute the pasted digits across all 6 boxes.
7. WHEN all 6 code boxes contain a digit, THE OtpLoginPage SHALL submit verification automatically without requiring the verify control to be activated.
8. THE OtpLoginPage SHALL retain an explicit verify control that submits verification when activated, so that the code step is operable by keyboard and assistive technology without relying on auto-submit.
9. IF the entered code is not exactly 6 decimal digits on submit, THEN THE OtpLoginPage SHALL block verification and present a validation message.
10. WHEN the code-entry step is submitted with a 6-digit code, THE OtpLoginPage SHALL call `POST /api/auth/otp/verify` with `{ email, code }`.
11. WHEN `POST /api/auth/otp/verify` returns `200` with a TokenResponse, THE Auth_Store SHALL store the tokens and hydrate the Current_User, and THE OtpLoginPage SHALL navigate to the saved Return_Location when one is present and otherwise to the application home route.
12. IF `POST /api/auth/otp/verify` returns `400` (invalid, expired, or attempts exceeded), THEN THE OtpLoginPage SHALL present the corresponding localized backend message under the code input and keep the user on the code-entry step.
13. IF `POST /api/auth/otp/request` or `POST /api/auth/otp/verify` returns `429` (rate limited), THEN THE OtpLoginPage SHALL present the localized rate-limited message from the backend.
14. WHILE an OTP request or verify call is in flight, THE OtpLoginPage SHALL disable the active submit control and present a loading indication.
15. THE OtpLoginPage SHALL provide a control to return to the email step so that the user can request a new code.
16. THE OtpLoginPage code-entry step SHALL display the masked email entered in the first step so that the user can confirm where the code was sent.
17. THE OtpLoginPage code-entry step SHALL present a resend-code control with a cooldown timer.
18. WHILE the resend cooldown is counting down, THE OtpLoginPage SHALL disable the resend-code control, and WHEN the cooldown reaches zero, THE OtpLoginPage SHALL re-enable the resend-code control.
19. WHEN the resend-code control is activated, THE OtpLoginPage SHALL re-issue `POST /api/auth/otp/request` for the retained email and restart the cooldown timer.
20. THE OtpLoginPage code input SHALL use a numeric input mode (numeric on-screen keyboard on mobile) and SHALL expose a one-time-code autofill hint so the platform MAY offer codes from SMS or email where supported.

---

### Requirement 8: Logout

**User Story:** As a signed-in user, I want to log out, so that my session ends and my refresh token can no longer be used.

#### Acceptance Criteria

1. WHEN the user triggers logout, THE Auth_Store SHALL call `POST /api/auth/logout` with the stored `{ refreshToken }`.
2. WHEN the logout call completes, THE Auth_Store SHALL clear the session (remove stored tokens and reset user/authenticated state) regardless of the logout response status, given the endpoint is idempotent.
3. WHEN logout completes, THE application SHALL redirect the browser to `/login`.
4. IF the `POST /api/auth/logout` call fails at the network level, THEN THE Auth_Store SHALL still clear the local session and redirect to `/login`.

---

### Requirement 9: Authenticated-Only Route Guard and Public Routes

**User Story:** As a security-conscious product, I want unauthenticated users kept out of the app shell, so that only signed-in users reach protected pages.

#### Acceptance Criteria

1. THE application SHALL treat exactly `/login`, `/auth/set-password`, and `/auth/otp` as Public_Routes reachable without authentication.
2. IF an unauthenticated user navigates to any route other than a Public_Route, THEN THE Auth_Guard SHALL capture the attempted location (path plus query) as the Return_Location and redirect the browser to `/login`.
3. WHILE a user is authenticated, THE Auth_Guard SHALL permit navigation to protected routes under the application shell.
4. WHILE Session_Hydration has not yet resolved, THE Auth_Guard SHALL present a loading indication rather than redirecting to `/login`.
5. THE Auth_Guard SHALL decide access solely on authentication state (is the user logged in) and SHALL NOT evaluate resource/operation permissions, which are deferred to FOR-03-07.
6. WHEN the Auth_Guard captures a Return_Location, IF the attempted location is a Public_Route, THEN THE Auth_Guard SHALL NOT set the Return_Location, so that a Public_Route is never used as a return target.

---

### Requirement 10: Internationalization of Auth UI

**User Story:** As a Polish- or Russian-speaking user, I want the auth screens in my language, so that I understand the sign-in flow.

#### Acceptance Criteria

1. THE auth UI (LoginPage, SetPasswordPage, OtpLoginPage, the Sign-in-with-Google control, and the logout control) SHALL render all user-facing strings through the existing i18n mechanism, with a non-blank entry for every auth string in both the PL (`src/locales/pl.json`) and RU (`src/locales/ru.json`) resources.
2. WHEN the active Locale is `ru`, THE auth UI SHALL render Russian strings, and WHEN the active Locale is `pl` or unset, THE auth UI SHALL render Polish strings.
3. WHEN a backend auth call returns a ForemenApiException-shaped error, THE auth UI SHALL surface the localized message text from the backend response body rather than a hard-coded client string, so that server-localized messages (resolved via `Accept-Language`) are shown to the user.
4. THE Api_Client SHALL send the `Accept-Language` header matching the active Locale so that backend error messages are returned in the user's language.
5. THE auth UI SHALL provide non-blank PL and RU strings for the Sign-in-with-Google control label and for the "no linked account — contact administrator" message (RU: "Обратитесь к администратору для получения учётной записи"; PL: "Skontaktuj się z administratorem, aby uzyskać konto").

---

### Requirement 11: Accessibility and Interaction States of Auth Forms

**User Story:** As a user relying on assistive technology or keyboard navigation, I want the auth forms to be accessible and to indicate their state, so that I can complete sign-in reliably.

#### Acceptance Criteria

1. THE auth forms SHALL associate every input with a visible, programmatically-linked label.
2. THE password and confirm-password inputs SHALL use an input type that masks entered characters.
3. WHEN a submission is blocked by client-side validation or rejected by the backend, THE auth form SHALL present the error in a way that is programmatically associated with the relevant field or the form and announced to assistive technology.
4. WHILE a submit action is in flight, THE triggering control SHALL be disabled and expose a busy/loading state to assistive technology.
5. THE auth forms SHALL be fully operable by keyboard, including submitting via the Enter key while focus is within the form.
6. THE interactive controls of the auth forms SHALL be reachable in a logical tab order and expose accessible names.
7. THE OtpLoginPage segmented one-time-code input SHALL expose a programmatically-linked accessible label, and its auto-submit behavior SHALL NOT trap keyboard or screen-reader focus.

---

### Requirement 12: Return-to-Last-Page (Deep-Link Preservation)

**User Story:** As a user whose session expired while I was working, or who opened a protected link before signing in, I want to land back on the page I was trying to reach after I log in, so that I do not lose my place.

#### Acceptance Criteria

1. WHEN the Auth_Guard redirects an unauthenticated user from a protected route to `/login`, THE application SHALL capture the attempted protected location (path plus query string) as the Return_Location.
2. WHEN the Api_Client forces a logout because a refresh failed or a `401` could not be recovered, THE application SHALL capture the current protected location (path plus query string) as the Return_Location.
3. WHEN any re-login flow succeeds (password login, set-password, OTP verify, or Google_Sign_In) and a Return_Location is present, THE application SHALL navigate the user to the Return_Location instead of the application home route.
4. IF no Return_Location is present when a re-login flow succeeds, THEN THE application SHALL navigate the user to the application home route (`/`).
5. THE application SHALL never set a Public_Route (`/login`, `/auth/set-password`, `/auth/otp`) as the Return_Location.
6. WHEN the application navigates the user to a Return_Location after a successful re-login, THE application SHALL clear the stored Return_Location so that a later login without a fresh capture falls back to the application home route.
7. THE Return_Location behavior SHALL depend solely on authentication state and SHALL NOT evaluate resource/operation permissions, which are deferred to FOR-03-07.
8. WHEN a Google_Sign_In yields an Activation_Required response and the application redirects to the SetPasswordPage (Requirement 14), THE application SHALL preserve the captured Return_Location across that redirect so that the subsequent set-password auto-login navigates the user to the Return_Location.

---

### Requirement 13: Cached Current_User via Conditional Requests to /me

**User Story:** As a user, I want the app to remember who I am cheaply, so that it can revalidate my identity frequently without re-downloading it on every check.

#### Acceptance Criteria

1. WHEN `GET /api/auth/me` returns `200` with a strong ETag, THE application SHALL store the returned Current_User and the returned Me_ETag in the Me_Cache.
2. WHEN the application issues a subsequent `GET /api/auth/me` and a Me_ETag is present in the Me_Cache, THE Api_Client SHALL attach the header `If-None-Match: <Me_ETag>`.
3. WHEN `GET /api/auth/me` returns `304 Not Modified`, THE application SHALL reuse the cached Current_User without replacing it and without treating the `304` as an error.
4. WHEN `GET /api/auth/me` returns `200`, THE application SHALL replace the cached Current_User and the stored Me_ETag with the newly returned values.
5. WHEN a login, set-password, OTP-verify, Google_Sign_In, or logout transition occurs, THE application SHALL invalidate the Me_Cache so that the next `GET /api/auth/me` is a forced refetch without an `If-None-Match` header.
6. THE application MAY refetch `GET /api/auth/me` opportunistically or frequently, given that a `304` response is cheap.
7. THE Api_Client SHALL treat a `304 Not Modified` response to `GET /api/auth/me` as a success outcome and SHALL NOT throw an Api_Error for it.

> **Backend dependency (delivered by this spec — extends the FOR-03 `OVERVIEW.md`):** Requirement 13 requires `GET /api/auth/me` to emit a strong `ETag` and appropriate `Cache-Control`, honor an inbound `If-None-Match`, and return `304 Not Modified` when unchanged. The `ETag` MUST change whenever the user's role, the role's permissions, the user's role membership, or the user's identity/profile changes, and MUST NOT change otherwise.

---

### Requirement 14: Sign in with Google

**User Story:** As an employee whose account is linked to a Google email, I want to sign in with Google, so that I can access the application without typing my password.

#### Acceptance Criteria

1. THE LoginPage SHALL present a Sign-in-with-Google control alongside the email/password form.
2. WHEN the user activates the Sign-in-with-Google control, THE application SHALL use Google Identity on the client to obtain a Google_ID_Token.
3. WHEN a Google_ID_Token is obtained, THE application SHALL send it to the Google_Exchange_Endpoint (e.g. `POST /api/auth/google` with `{ idToken }`) through the Api_Client.
4. WHEN the Google_Exchange_Endpoint returns `200` with a TokenResponse (matched account status ACTIVE), THE Auth_Store SHALL store the tokens and hydrate the Current_User, and THE application SHALL navigate to the saved Return_Location when one is present and otherwise to the application home route (`/`).
5. WHEN the Google_Exchange_Endpoint returns `200` with an Activation_Required response (matched account status INVITED), THE application SHALL redirect the browser to the SetPasswordPage at `/auth/set-password?token=<Set_Password_Token>` using the Set_Password_Token from the response body, and SHALL NOT store an Access_Token or Refresh_Token and SHALL NOT treat the user as authenticated.
6. WHEN the application redirects to the SetPasswordPage in response to an Activation_Required response, THE application SHALL preserve any captured Return_Location so that after the user sets a password and is auto-logged-in (Requirement 6) THE application navigates to the Return_Location when one is present and otherwise to the application home route (`/`).
7. WHERE the Google_Exchange_Endpoint returns an Activation_Required response, THE application SHALL NOT present the no-linked-account message and SHALL NOT present a generic authentication-failure message, treating the Activation_Required response as a successful activation hand-off rather than an error.
8. IF the Google_Exchange_Endpoint returns the deactivated-account error (matched account status DEACTIVATED, reusing the existing deactivated message code), THEN THE application SHALL present the corresponding localized backend message and SHALL NOT store any token.
9. IF the Google_Exchange_Endpoint returns the no-linked-account error (e.g. `403`/`404` with message code `error.auth.google.no.account`), THEN THE application SHALL present the distinct "no linked account — contact administrator" message (RU: "Обратитесь к администратору для получения учётной записи"; PL: "Skontaktuj się z administratorem, aby uzyskać konto") and SHALL NOT present a generic invalid-credentials message.
10. IF the user cancels or dismisses the Google sign-in prompt, THEN THE application SHALL return to the idle login state without leaving a stuck error or loading state.
11. IF Google authentication fails on the client before a Google_ID_Token is obtained, THEN THE application SHALL present a generic authentication-failure message.
12. IF the Google_Exchange_Endpoint returns any other non-`200` error, THEN THE application SHALL present the localized backend message from the response body.
13. WHILE a Google exchange request is in flight, THE application SHALL disable the Sign-in-with-Google control and present a loading indication.
14. THE Google_Sign_In path SHALL NOT store or expose an Access_Token or Refresh_Token for any matched account whose status is not ACTIVE, so that the SetPasswordPage remains the single activation gate and the Set_Password_Token is issued only after successful Google verification of the email.

> **Backend and configuration dependency (delivered by this spec — extends the FOR-03 `OVERVIEW.md`):** Requirement 14 requires a backend Google_Exchange_Endpoint (e.g. `POST /api/auth/google`) that verifies the Google_ID_Token against the configured Google client, looks up the user by verified Google email, and returns one of the following outcomes based on the matched account status:
>
> - **ACTIVE →** `200` `TokenResponse` (normal login; issues the JWT pair).
> - **INVITED →** `200` Activation_Required response carrying a freshly-minted Set_Password_Token and NO access/refresh session (e.g. `{ status: "ACTIVATION_REQUIRED", setPasswordToken: "<token>" }`; exact field names left to design, but the response MUST include a machine-detectable activation-required marker AND a Set_Password_Token). The Set_Password_Token is equivalent to the FOR-03-02 invite token in TTL and semantics and is minted only because Google cryptographically verified email ownership.
> - **DEACTIVATED →** the existing deactivated error (reusing the existing message code; no session, no token).
> - **No linked account →** the distinct no-account error (e.g. `403` with message code `error.auth.google.no.account`, contact-admin), unchanged.
>
> Security constraint: the Google exchange path MUST NOT return an Access_Token or Refresh_Token for a non-ACTIVE account; the SetPasswordPage (Requirement 6) remains the single activation gate, and the Set_Password_Token is issued only after successful Google verification of the email. Because the SetPasswordPage now receives tokens that may originate from this Google bridge (in addition to FOR-03-02 email invites), the set-password flow in Requirement 6 is unchanged but its token source is broadened. The redirect described in Requirement 14 must preserve the Return_Location per Requirement 12. It also requires Google account linking on user records, configuration of the Google OAuth client id on the frontend (env var, e.g. `VITE_GOOGLE_CLIENT_ID`) and the backend client/secret. There is currently no Google SDK dependency in `foremen-frontend/package.json`, so a Google Identity script/library must be added.
