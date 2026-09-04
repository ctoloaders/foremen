# Implementation Plan: FOR-03-06 Frontend Auth

## Overview

This plan implements the frontend authentication layer for the Foremen web client (`foremen-frontend`, TypeScript / React / Zustand / react-router / react-hook-form + zod) plus the two in-scope backend additions in `foremen-backend` (Java / Spring Boot): `GET /api/auth/me` ETag/conditional requests and `POST /api/auth/google`.

Work proceeds bottom-up and always ends wired into the running app: first the primitives the rest depends on (Auth_Store + Token_Storage, Return_Location, Me_Cache), then the Api_Client (headers, error surface, 401 refresh/retry/dedup), then the session-hydration action and the route restructure/Auth_Guard, then the auth-api layer, then the pages and components (Login, SetPassword, OTP + OtpCodeInput, Google), then i18n and accessibility, then the backend `/me` ETag and `/api/auth/google` endpoint (with FOR-03-02 token-mint reuse and config), and finally end-to-end wiring, the `test-cases.md` artifact, and a checkpoint. Each step builds on the previous ones; there is no orphaned code.

Test sub-tasks marked with `*` are optional (property / unit / integration / e2e tests) and validate the design's nine correctness properties plus example-based coverage. Property tests use **fast-check** (frontend) and **jqwik** (backend), run a minimum of 100 iterations, and are tagged `Feature: FOR-03-06-frontend-auth, Property {n}: {text}`.

## Tasks

- [x] 1. Auth_Store and Token_Storage
  - [x] 1.1 Implement `Auth_Store` state, derivation, and Token_Storage
    - Create `foremen-frontend/src/stores/auth-store.ts` as a Zustand store with state `user` (CurrentUser|null), `accessToken`, `refreshToken`, `isAuthenticated`, `hydrationStatus` ('pending'|'done') and the internal setters `setTokens(TokenResponse)`, `setUser(CurrentUser)`, `clearSession()`. Persist tokens to `localStorage` under fixed keys `foremen-access-token` / `foremen-refresh-token`; wrap every read/write in `try/catch` mirroring `theme-store.ts` so storage failures never throw. Recompute `isAuthenticated = user != null && accessToken != null` on every transition. `clearSession()` removes both keys and resets all session state.
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7_
  - [x] 1.2 Write property test for isAuthenticated derivation
    - **Property 1: isAuthenticated equals presence of both user and access token**
    - **Validates: Requirements 1.5**
  - [x] 1.3 Write unit tests for Token_Storage read/write and clear
    - Assert `setTokens` writes both fixed `localStorage` keys, `clearSession` removes them and resets state, and a throwing `localStorage` (stubbed) keeps the store operating from memory without throwing.
    - _Requirements: 1.3, 1.4, 1.6_

- [x] 2. Return_Location module
  - [x] 2.1 Implement Return_Location capture/consume/clear
    - Create `foremen-frontend/src/lib/return-location.ts` with `KEY = 'foremen-return-to'`, the Public_Route set `['/login','/auth/set-password','/auth/otp']`, and `captureReturnLocation(pathPlusQuery)` (no-op when the path is a Public_Route), `consumeReturnLocation()` (read + clear, returns null when absent), `clearReturnLocation()`. Persist under `sessionStorage` so it survives the `/login` redirect and a full-page reload.
    - _Requirements: 3.8, 9.6, 12.1, 12.4, 12.5, 12.6_
  - [x] 2.2 Write property test for Return_Location round-trip and public-route exclusion
    - **Property 4: Return_Location round-trips for protected paths and is never a public route**
    - **Validates: Requirements 3.8, 9.6, 12.1, 12.3, 12.4, 12.5, 12.6**

- [x] 3. Me_Cache module
  - [x] 3.1 Implement Me_Cache in-memory state
    - Create `foremen-frontend/src/lib/me-cache.ts` with in-memory `{ currentUser, etag }` and `getMeCache()`, `setMeCache(user, etag)`, `invalidateMeCache()` (clears the etag so the next `/me` is a forced fetch without `If-None-Match`).
    - _Requirements: 13.1, 13.4, 13.5_
  - [x] 3.2 Write property test for Me_Cache last-200 / neutral-304 behavior
    - **Property 7: Me_Cache always reflects the last 200 and 304 is neutral**
    - **Validates: Requirements 13.1, 13.3, 13.4**

- [x] 4. Api_Client — headers, error surface, and navigation hook
  - [x] 4.1 Implement `ApiError`, header assembly, and response handling
    - Create `foremen-frontend/src/lib/api-client.ts` exporting `ApiError(status, message, code?)`, `apiRequest<T>(path, options)`, and `registerNavigate(fn)`. Read the access token via `useAuthStore.getState()` (module-level, no React hook) and attach `Authorization: Bearer <token>` only when present; attach `Accept-Language` (`ru` when `localStorage['foremen-locale']` is `ru`, else `pl`); attach `Content-Type: application/json` when a JSON body is present; attach `If-None-Match` when `ifNoneMatch` is supplied. On 2xx parse JSON (undefined for 204); on non-OK build `ApiError` from the `ForemenApiException`/`ErrorResponse` body (verbatim `message`, `code` when derivable), falling back to i18n key `auth.error.generic` for a non-JSON body without throwing a parse exception; on `304` with `parse304AsSuccess` resolve a not-modified sentinel (never throw).
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 10.3, 10.4, 13.3, 13.7_
  - [x] 4.2 Write unit tests for header assembly and error surface
    - Assert Authorization present/absent by store token, `Accept-Language` ru/pl fallback, `Content-Type` on JSON body, `If-None-Match` pass-through, verbatim backend message on non-OK, generic fallback on non-JSON body, and `304` resolving as a success sentinel.
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 13.7_

- [x] 5. Api_Client — 401 refresh, retry, and single-flight dedup
  - [x] 5.1 Implement the 401 refresh-and-retry algorithm with a shared in-flight promise
    - In `api-client.ts` add the module-level `refreshInFlight` promise and the 401 handler: skip refresh for the refresh call itself (`skipAuthRefresh`) and propagate its 401; when no Refresh_Token is present, capture Return_Location (unless on a Public_Route), `clearSession()`, redirect to `/login` (via `registerNavigate` callback, falling back to `window.location.assign('/login')`), and reject; otherwise dedupe onto a single `useAuthStore.getState().refresh()` call, and on success retry the original request exactly once with the fresh access token (a 401 on retry forces logout, never a second refresh); on refresh failure capture Return_Location (unless public), `clearSession()`, redirect to `/login`, and reject the original caller. Add `Auth_Store.refresh()` (POST `/api/auth/refresh` with `skipAuthRefresh`, `setTokens` on the rotated pair) as part of this task so the client has a store operation to invoke.
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8_
  - [x] 5.2 Write property test for single refresh across concurrent 401s
    - **Property 2: A single refresh serves all concurrent 401s**
    - **Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.7**
  - [x] 5.3 Write property test for repeated-401 no-loop and forced logout
    - **Property 3: A repeated 401 does not loop and forces logout**
    - **Validates: Requirements 3.3, 3.5, 3.6**

- [x] 6. auth-api layer
  - [x] 6.1 Implement typed auth-api functions over `apiRequest`
    - Create `foremen-frontend/src/app/auth/api/auth-api.ts` with `login`, `refreshTokens` (skipAuthRefresh), `logout`, `getMe` (reads `Me_Cache.etag` as `ifNoneMatch`, `parse304AsSuccess`; on 200 writes `{currentUser, etag}` to Me_Cache and returns the user; on 304 returns a NotModified marker), `setPassword`, `otpRequest`, `otpVerify`, and `googleExchange` (returns `GoogleLoginResponse`). Define the shared frontend types (`CurrentUser`, `TokenResponse`, `GoogleLoginResponse` discriminated on `status`, `NotModified`).
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 14.3_

- [x] 7. Session hydration and post-auth identity refresh
  - [x] 7.1 Implement `Auth_Store.hydrate`, `login`, `logout`, and post-transition `/me`
    - In `auth-store.ts` add `hydrate()` (no stored access token → `hydrationStatus='done'`, unauthenticated, skip `/me`; token present → set tokens from storage, call `authApi.getMe()`; 200 → `setUser`+authenticated, 304 → reuse `Me_Cache.currentUser`, unrecoverable failure → `clearSession()`; always finish `hydrationStatus='done'`). Add `login(email,password)` and `logout()` (best-effort `authApi.logout(refreshToken)`, then `clearSession()` + `invalidateMeCache()`, caller redirects to `/login`). After every successful auth transition (login, set-password, otp-verify, google), invalidate Me_Cache then do a forced `authApi.getMe()` + `setUser`.
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 8.1, 8.2, 8.3, 8.4, 13.5_
  - [x] 7.2 Write unit tests for hydrate branches and logout
    - Assert hydrate branches (no token → done/unauth; 200 → authed; 304 → cached user; unrecoverable → cleared), and logout clears regardless of the logout response status and on network failure.
    - _Requirements: 4.2, 4.3, 4.4, 8.2, 8.3, 8.4_

- [x] 8. Route restructure and Auth_Guard
  - [x] 8.1 Restructure the router and implement `ProtectedLayout`
    - Edit `foremen-frontend/src/app/router.tsx` so the three Public_Routes (`/login`, `/auth/set-password`, `/auth/otp`) live outside `AppShell`, and mount a `/` route whose element is `ProtectedLayout` wrapping all existing protected routes. Create `foremen-frontend/src/app/guards/ProtectedLayout.tsx`: while `hydrationStatus === 'pending'` render a loading indicator; when unauthenticated, `captureReturnLocation(pathname+search)` then `<Navigate to="/login" replace />`; when authenticated render `<AppShell/>` (with its own `<Outlet/>`). Guard decides on authentication only (no permission checks). Add a small in-router component that calls `registerNavigate(useNavigate())` once so Api_Client can redirect.
    - _Requirements: 4.5, 9.1, 9.2, 9.3, 9.4, 9.5, 12.1, 12.7_
  - [x] 8.2 Write unit tests for the guard states
    - Assert loading while `pending`, redirect + Return_Location capture while unauthenticated, and AppShell render while authenticated.
    - _Requirements: 9.2, 9.3, 9.4_

- [x] 9. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 10. Shared auth scaffolding and useAuthRedirect
  - [x] 10.1 Implement `AuthCard` and `useAuthRedirect`
    - Create `foremen-frontend/src/app/auth/components/AuthCard.tsx` (shared layout/label/error scaffolding for the auth forms) and `foremen-frontend/src/app/auth/hooks/useAuthRedirect.ts` that resolves the post-login target via `consumeReturnLocation()` (navigate there and clear) or falls back to `/`.
    - _Requirements: 12.3, 12.4, 12.6_

- [x] 11. LoginPage
  - [x] 11.1 Implement `LoginPage`
    - Create `foremen-frontend/src/app/auth/LoginPage.tsx` at `/login` with a react-hook-form + zod form (email required + format, password required); block submission with field-level messages when invalid without calling the backend; on submit call `Auth_Store.login` then hydrate; surface `ApiError.message` at form level on 401/403; disable submit + show a spinner while in flight; if already authenticated redirect away via `useAuthRedirect`. Render the `GoogleSignInButton` alongside the form.
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 14.1_
  - [x] 11.2 Write unit tests for LoginPage validation and error surfacing
    - Assert empty-field validation blocks submission, in-flight disables submit, and 401/403 backend messages are surfaced.
    - _Requirements: 5.2, 5.5, 5.6, 5.7, 10.3_

- [x] 12. SetPasswordPage
  - [x] 12.1 Implement `SetPasswordPage`
    - Create `foremen-frontend/src/app/auth/SetPasswordPage.tsx` at `/auth/set-password`: read `token` from `?token=`; when missing/empty show an invalid-link message and do not submit; fields password + confirm with zod (8..72 chars, confirm-match); on submit call `authApi.setPassword(token, password)`; on 200 auto-login (`setTokens` + forced `/me`) then navigate via `useAuthRedirect`; on 400 surface `ApiError.message` and stay on the page; disable submit + spinner while in flight. The token may originate from a FOR-03-02 invite email or from the Google `ACTIVATION_REQUIRED` bridge — handled identically.
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9, 12.8, 14.6_
  - [x] 12.2 Write unit tests for SetPasswordPage validation and token handling
    - Assert missing-token invalid-link (no submit), 8..72 length + confirm-mismatch validation blocks, and 400 backend-error surfacing.
    - _Requirements: 6.2, 6.4, 6.5, 6.8_

- [x] 13. OTP login flow
  - [x] 13.1 Implement `OtpCodeInput`
    - Create `foremen-frontend/src/app/auth/components/OtpCodeInput.tsx`: 6 controlled single-digit boxes with `inputMode="numeric"`, `autoComplete="one-time-code"`, `pattern="[0-9]*"`; typing a digit advances focus, Backspace in an empty box retreats focus; pasting distributes up to 6 digits across boxes; auto-submit when all 6 filled without trapping focus; each box has `aria-label` "Digit N of 6" and the group has a programmatically-linked label.
    - _Requirements: 7.4, 7.5, 7.6, 7.7, 7.8, 7.20, 11.7_
  - [x] 13.2 Write property test for OTP focus advance/backspace
    - **Property 5: OTP input focus advances on entry and retreats on backspace**
    - **Validates: Requirements 7.5**
  - [x] 13.3 Write property test for OTP paste distribution
    - **Property 6: Pasting six digits fills all boxes in order**
    - **Validates: Requirements 7.6**
  - [x] 13.4 Implement `OtpLoginPage` two-step state machine
    - Create `foremen-frontend/src/app/auth/OtpLoginPage.tsx` at `/auth/otp`: email step (non-empty email → `authApi.otpRequest`, always advance on 200 for anti-enumeration; empty email → field-level validation) and code step (retains the email, shows a masked-email echo, uses `OtpCodeInput` + an explicit verify control, verifies 6 digits via `authApi.otpVerify`, on 200 auto-login + `useAuthRedirect`, on 400 inline error stays on the code step, on 429 shows the rate-limited message, back-to-email control, resend control with a configurable `OTP_RESEND_COOLDOWN_SECONDS` (default 60) cooldown that disables while counting down and re-enables at zero); disable the active submit control + spinner while a request is in flight.
    - _Requirements: 7.1, 7.2, 7.3, 7.9, 7.10, 7.11, 7.12, 7.13, 7.14, 7.15, 7.16, 7.17, 7.18, 7.19_
  - [x] 13.5 Write unit tests for OtpCodeInput and OtpLoginPage
    - Assert auto-submit fires once when all 6 boxes are filled, numeric input mode + one-time-code autofill attributes are present, and the resend cooldown disables/re-enables the control.
    - _Requirements: 7.7, 7.17, 7.18, 7.20_

- [x] 14. Google Sign-In (frontend)
  - [x] 14.1 Implement the Google Identity Services wrapper
    - Create `foremen-frontend/src/lib/google-identity.ts` that lazily injects the GIS script (`https://accounts.google.com/gsi/client`), initializes with `import.meta.env.VITE_GOOGLE_CLIENT_ID`, and yields a Google_ID_Token via the credential callback; handle prompt cancel/dismiss and pre-token client failures.
    - _Requirements: 14.2, 14.10, 14.11_
  - [x] 14.2 Implement `GoogleSignInButton`
    - Create `foremen-frontend/src/app/auth/components/GoogleSignInButton.tsx`: on credential callback disable the control + spinner and call `authApi.googleExchange(idToken)`; branch on outcome — 200 `AUTHENTICATED` → `setTokens` + hydrate + `useAuthRedirect`; 200 `ACTIVATION_REQUIRED` → store no tokens, leave Return_Location untouched, navigate to `/auth/set-password?token=<setPasswordToken>` (not an error); 403 `error.auth.google.no.account` → distinct contact-admin message (`auth.google.noAccount`); 403 deactivated → `ApiError.message`, no token; prompt cancel → idle; client pre-token failure → `auth.google.failed`; any other non-200 → `ApiError.message`. Never call `setTokens` on the non-AUTHENTICATED branches.
    - _Requirements: 14.4, 14.5, 14.6, 14.7, 14.8, 14.9, 14.10, 14.11, 14.12, 14.13, 14.14_

- [x] 15. i18n keys and accessibility pass
  - [x] 15.1 Add auth i18n keys to PL and RU resources
    - Add the full `auth.*` namespace (login, setPassword, otp emailStep/codeStep/codeInput/resend/validation, google button/noAccount/failed, guard.loading, logout, error.generic) to both `foremen-frontend/src/locales/pl.json` and `src/locales/ru.json`, every key non-blank in both, with the confirmed contact-admin wording (RU "Обратитесь к администратору для получения учётной записи"; PL "Skontaktuj się z administratorem, aby uzyskać konto"). Route all page/button/label strings through i18n.
    - _Requirements: 10.1, 10.2, 10.5_
  - [x] 15.2 Apply accessibility attributes across the auth forms
    - Ensure every input has a visible `<label htmlFor>`; password/confirm use `type="password"`; validation and backend errors use `role="alert"` + `aria-describedby` linkage; submit controls set `disabled` + `aria-busy` while in flight; forms are keyboard-operable including Enter-to-submit with a logical tab order and accessible names; OTP group is labeled and auto-submit does not trap focus.
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7_

- [x] 16. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 17. Backend: `GET /api/auth/me` ETag / conditional requests
  - [x] 17.1 Implement `MeETag.compute` and conditional `/me` handling
    - Add `MeETag.compute(CurrentUserResponse)` building a canonical string `userId|name|email|roleCode|sortedPermissions` (resources sorted; operations within each sorted), SHA-256 hex, wrapped in quotes as a strong ETag. Change `AuthController.me` to compute the ETag from `authService.currentUser(userId)` (live role graph, not the Caffeine cache), honor `If-None-Match` by returning `304 Not Modified` (empty body) when equal, and otherwise return `200` with the DTO; set `Cache-Control: no-cache, private` on both. Add `.exposedHeaders("ETag")` to `CorsConfig`.
    - _Requirements: 13.1, 13.2, 13.3, 13.4_
  - [x] 17.2 Write property test for ETag stability and change-on-change
    - **Property 8: The /me ETag is stable under content and changes on change (backend)**
    - **Validates: Requirements 13 (backend ETag dependency), 13.1, 13.2**
  - [x] 17.3 Write integration test for conditional `/me`
    - MockMvc + Testcontainers: first `/me` returns a strong `ETag`; a follow-up with `If-None-Match: <etag>` returns `304` with an empty body; after a role/permission change the ETag differs and returns `200`.
    - _Requirements: 13.1, 13.2, 13.3, 13.4_

- [x] 18. Backend: `POST /api/auth/google` DTOs, config, and token-mint reuse
  - [x] 18.1 Add Google DTOs and `GoogleProperties` config
    - Add `GoogleLoginRequest(@NotBlank String idToken)` and the discriminated `GoogleLoginResponse(status, tokens, setPasswordToken)` with `authenticated(tokens)` / `activationRequired(setPasswordToken)` factories in the auth DTO package. Add `@Validated @ConfigurationProperties("foremen.google") GoogleProperties(String clientId)`, bind `foremen.google.client-id: ${FOREMEN_GOOGLE_CLIENT_ID:}` in both `application.yml` and `application-docker.yml`, and add the `google-api-client` dependency for `GoogleIdTokenVerifier`.
    - _Requirements: 14.3, 14.4, 14.5, 14.14_
  - [x] 18.2 Add `InviteService.mintSetPasswordToken` (FOR-03-02 reuse)
    - Add a public `String mintSetPasswordToken(UserEntity user)` on FOR-03-02's `InviteService` that invalidates outstanding unused tokens and issues a fresh invite/set-password token reusing the existing generation + TTL, returning the raw token string (email dispatch optional/skippable for this path). Do not otherwise change FOR-03-02's contract.
    - _Requirements: 14.5, 14.14_

- [x] 19. Backend: Google exchange service and controller
  - [x] 19.1 Implement `AuthService.loginWithGoogle`
    - Verify the idToken with a `GoogleIdTokenVerifier` (audience = `foremen.google.client-id`; any failure → `ForemenApiException(UNAUTHORIZED, "error.auth.google.token.invalid")`), extract the verified email, and `userDao.findByEmail(email.toLowerCase())`: not found → `ForemenApiException(FORBIDDEN, "error.auth.google.no.account")`; ACTIVE → `GoogleLoginResponse.authenticated(issueTokens(user))`; INVITED → `GoogleLoginResponse.activationRequired(inviteService.mintSetPasswordToken(user))` with no session; DEACTIVATED → `error.auth.account.deactivated` (403).
    - _Requirements: 14.4, 14.5, 14.8, 14.9, 14.14_
  - [x] 19.2 Add the controller endpoint
    - Add `POST /api/auth/google` to `AuthController` (`@Valid GoogleLoginRequest` → `ResponseEntity.ok(authService.loginWithGoogle(...))`, HTTP 200 for both AUTHENTICATED and ACTIVATION_REQUIRED). Verify (and document via a code comment/test) that it is publicly reachable through the existing `/api/auth/**` `permitAll` rule and not caught by the `authenticated()`/`hasRole("ADMIN")` matchers.
    - _Requirements: 14.3, 14.4, 14.5_
  - [x] 19.3 Write property test for the Google token/status invariant
    - **Property 9: The Google login response carries tokens only for ACTIVE accounts (backend)**
    - **Validates: Requirements 14.4, 14.5, 14.14**
  - [x] 19.4 Write integration tests for `/api/auth/google`
    - MockMvc + Testcontainers with a stubbed `GoogleIdTokenVerifier`: ACTIVE → `200 AUTHENTICATED` (tokens present, setPasswordToken null); INVITED → `200 ACTIVATION_REQUIRED` (tokens null, non-empty setPasswordToken) whose token is accepted by a follow-up `POST /api/auth/set-password`; unknown email → `403 error.auth.google.no.account`; DEACTIVATED → `403 error.auth.account.deactivated`; invalid token → `401 error.auth.google.token.invalid`; unauthenticated request reaches the controller (public), not a security 401.
    - _Requirements: 14.4, 14.5, 14.8, 14.9, 14.14_

- [x] 20. Backend: Google i18n message codes
  - [x] 20.1 Add Google message codes to PL and RU bundles
    - Add `error.auth.google.no.account` and `error.auth.google.token.invalid` to both `messages.properties` (PL) and `messages_ru.properties` (RU) with the confirmed wording.
    - _Requirements: 14.9, 14.11_

- [x] 21. Final wiring and end-to-end tests
  - [x] 21.1 Wire hydration bootstrap and verify the whole flow compiles/builds
    - Ensure `main.tsx`/router bootstrap triggers `Auth_Store.hydrate()` before routes resolve and that `registerNavigate` is wired; confirm the frontend builds and the backend compiles with the new endpoints.
    - _Requirements: 4.1, 4.5, 9.4_
  - [x] 21.2 Write end-to-end tests (Playwright)
    - Password login → land on `/` or Return_Location; deep-link to a protected route while unauthenticated → `/login` → after login → returned to the deep link; OTP email → code → segmented auto-submit → authenticated; reload on a protected route with a valid stored session stays authenticated, with no token redirects to `/login`.
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 9.2, 9.4_

- [x] 22. Author test-cases.md
  - Create `test-cases.md` in the spec folder following the `.kiro/steering/test-cases.md` standard (feature grouping, step-by-step scenarios, repeatability via a per-run generator/teardown, regression group, MD report template). This spec has BOTH browser UI screens and API endpoints: author browser-engine scenarios for the UI flows (LoginPage password + Google control, SetPasswordPage invite/Google-bridge token, OtpLoginPage email→segmented code→auto-submit→authenticated, Auth_Guard redirect + Return_Location deep-link round-trip, logout) AND API tests against the Dockerized app (`docker compose up`, base URL `http://localhost:8080`, auth under `/api/auth`, admin bootstrap via `FOREMEN_ADMIN_CREATE`/`FOREMEN_ADMIN_EMAIL`/`FOREMEN_ADMIN_PASSWORD`) for `GET /api/auth/me` (strong ETag → `If-None-Match` → `304`, ETag change after role/permission change) and `POST /api/auth/google` (AUTHENTICATED / ACTIVATION_REQUIRED with follow-up set-password / deactivated / no-account branches, stubbed or test Google token). Ensure repeatability via a unique-email generator (`test+{run-id}@example.com`) and/or teardown; include a regression group and the MD report template with tables. Result artifacts are MD reports with tables.
  - _Requirements: 3.5, 4.1, 5.4, 6.7, 7.2, 7.7, 7.11, 8.1, 9.2, 12.1, 12.3, 13.1, 13.2, 13.3, 14.4, 14.5, 14.8, 14.9_

- [x] 23. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP; they implement the property / unit / integration / e2e tests that validate the design's correctness properties and example-based coverage.
- Each task references specific requirement sub-clauses for traceability.
- Checkpoints ensure incremental validation.
- Property tests validate the nine universal correctness properties from `design.md` (frontend via fast-check, backend via jqwik); unit, integration, and e2e tests validate wiring, headers, guard states, ETag conditional requests, the Google branch table, and end-to-end flows.
- No existing employee-flow contract is redesigned: the backend auth endpoints from FOR-03-01/02/05 are consumed as-is; the only backend additions are `/me` ETag support, `POST /api/auth/google`, and the `InviteService.mintSetPasswordToken` reuse.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1", "3.1", "15.1", "18.1", "20.1"] },
    { "id": 1, "tasks": ["1.2", "1.3", "2.2", "3.2", "4.1", "17.1", "18.2"] },
    { "id": 2, "tasks": ["4.2", "5.1", "6.1", "17.2", "17.3", "19.1"] },
    { "id": 3, "tasks": ["5.2", "5.3", "7.1", "10.1", "19.2"] },
    { "id": 4, "tasks": ["7.2", "8.1", "13.1", "14.1", "19.3", "19.4"] },
    { "id": 5, "tasks": ["8.2", "11.1", "12.1", "13.2", "13.3", "13.4", "14.2"] },
    { "id": 6, "tasks": ["11.2", "12.2", "13.5", "15.2", "21.1"] },
    { "id": 7, "tasks": ["21.2"] }
  ]
}
```
