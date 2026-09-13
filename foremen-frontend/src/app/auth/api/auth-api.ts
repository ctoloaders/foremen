/**
 * auth-api — thin, typed functions over {@link apiRequest}, one per backend auth
 * endpoint (FOR-03-06, task 6.1).
 *
 * These are the only place the auth flows (Auth_Store, LoginPage,
 * SetPasswordPage, OtpLoginPage, GoogleSignInButton) talk to the backend auth
 * contract. Each function serializes the request body and returns the typed
 * response the design specifies; error handling (typed {@link ApiError}, `401`
 * refresh/retry, forced logout) is owned by the Api_Client.
 *
 * Endpoint paths and request/response shapes are matched to the backend
 * `AuthController` (`/api/auth/**`) and its DTOs:
 *  - `POST /api/auth/login`        `{ email, password }`  -> TokenResponse
 *  - `POST /api/auth/refresh`      `{ refreshToken }`      -> TokenResponse (skipAuthRefresh)
 *  - `POST /api/auth/logout`       `{ refreshToken }`      -> 204 (void)
 *  - `GET  /api/auth/me`           (If-None-Match)         -> CurrentUser | NotModified
 *  - `POST /api/auth/set-password` `{ token, password }`   -> TokenResponse
 *  - `POST /api/auth/otp/request`  `{ email }`             -> 200 (void)
 *  - `POST /api/auth/otp/verify`   `{ email, code }`       -> TokenResponse
 *  - `POST /api/auth/google`       `{ idToken }`           -> GoogleLoginResponse
 */

import { apiRequest, NOT_MODIFIED, type NotModifiedSentinel } from '@/lib/api-client'
import { getMeCache, setMeCache } from '@/lib/me-cache'
import type { CurrentUser, TokenResponse } from '@/stores/auth-store'

// Re-export the shared identity/token types so callers can import the whole
// frontend auth contract from a single module.
export type { CurrentUser, TokenResponse }

/**
 * Marker resolved by {@link getMe} when the server answers `304 Not Modified`:
 * the cached Current_User in Me_Cache is still current and the caller reuses it
 * rather than replacing it (Req 13.3).
 */
export type NotModified = NotModifiedSentinel

/** Re-exported so callers can compare a {@link getMe} result against it. */
export { NOT_MODIFIED }

/**
 * Discriminated response for `POST /api/auth/google`, mirroring the backend
 * `GoogleLoginResponse { status, tokens, setPasswordToken }` DTO (Req 14.3):
 *  - `AUTHENTICATED`      — the Google email is linked to an ACTIVE account;
 *    `tokens` carries the JWT pair and no `setPasswordToken` is present.
 *  - `ACTIVATION_REQUIRED` — the Google email is linked to an INVITED account;
 *    no session is issued (`tokens` absent) and `setPasswordToken` carries a
 *    freshly-minted FOR-03-02 set-password token to complete activation.
 *
 * The security invariant (tokens present iff `AUTHENTICATED`) is encoded in the
 * discriminated union so the client cannot read tokens off the
 * `ACTIVATION_REQUIRED` branch (Req 14.14).
 */
export type GoogleLoginResponse =
  | { status: 'AUTHENTICATED'; tokens: TokenResponse; setPasswordToken?: null }
  | { status: 'ACTIVATION_REQUIRED'; tokens?: null; setPasswordToken: string }

/**
 * Authenticates an employee with email + password (Req 5.3).
 * `POST /api/auth/login` `{ email, password }` -> `200` TokenResponse.
 */
export function login(email: string, password: string): Promise<TokenResponse> {
  return apiRequest<TokenResponse>('/api/auth/login', {
    method: 'POST',
    body: { email, password },
    skipAuthRefresh: true,
  })
}

/**
 * Exchanges the current Refresh_Token for a rotated token pair
 * (`POST /api/auth/refresh` `{ refreshToken }` -> `200` TokenResponse).
 *
 * Issued with `skipAuthRefresh` so the Api_Client's `401` handler never tries
 * to refresh the refresh call itself; a non-`200` surfaces as a rejected
 * promise the caller (Auth_Store.refresh / Api_Client) treats as refresh
 * failure (Req 3.1 exclusion, 3.7).
 */
export function refreshTokens(refreshToken: string): Promise<TokenResponse> {
  return apiRequest<TokenResponse>('/api/auth/refresh', {
    method: 'POST',
    body: { refreshToken },
    skipAuthRefresh: true,
  })
}

/**
 * Revokes the supplied Refresh_Token (`POST /api/auth/logout` `{ refreshToken }`
 * -> `204`). Best-effort and idempotent; the caller clears the local session
 * regardless of the outcome (Req 8.1).
 */
export function logout(refreshToken: string): Promise<void> {
  return apiRequest<void>('/api/auth/logout', {
    method: 'POST',
    body: { refreshToken },
  })
}

/**
 * Fetches the Current_User via `GET /api/auth/me`, using conditional requests
 * against Me_Cache (Req 13.1–13.4):
 *  - Sends the cached ETag as `If-None-Match` (when present) and resolves a
 *    `304` as a success sentinel instead of throwing (`parse304AsSuccess`).
 *  - On `200`: captures the response ETag (surfaced via the `onEtag` hook),
 *    writes `{ currentUser, etag }` to Me_Cache, and returns the user.
 *  - On `304`: returns the {@link NOT_MODIFIED} marker so the caller reuses
 *    `Me_Cache.currentUser` (the cache is left untouched).
 *
 * The ETag comes from the response `ETag` header, surfaced by the Api_Client's
 * `onEtag` callback (the least-invasive way to read a single response header
 * without exposing the whole `Response`).
 */
export async function getMe(): Promise<CurrentUser | NotModified> {
  const { etag } = getMeCache()

  let responseEtag: string | null = null
  const result = await apiRequest<CurrentUser | NotModified>('/api/auth/me', {
    method: 'GET',
    ifNoneMatch: etag ?? undefined,
    parse304AsSuccess: true,
    onEtag: (value) => {
      responseEtag = value
    },
  })

  if (result === NOT_MODIFIED) {
    return NOT_MODIFIED
  }

  setMeCache(result, responseEtag)
  return result
}

/**
 * Sets the password for an invited account (invite email token or Google
 * activation bridge token — handled identically), activating it and
 * auto-logging-in (`POST /api/auth/set-password` `{ token, password }` -> `200`
 * TokenResponse) (Req 6.6).
 */
export function setPassword(token: string, password: string): Promise<TokenResponse> {
  return apiRequest<TokenResponse>('/api/auth/set-password', {
    method: 'POST',
    body: { token, password },
  })
}

/**
 * Requests a passwordless OTP login code for a client email
 * (`POST /api/auth/otp/request` `{ email }` -> `200`). Always resolves on `200`
 * regardless of eligibility (backend anti-enumeration silent success)
 * (Req 7.2).
 */
export function otpRequest(email: string): Promise<void> {
  return apiRequest<void>('/api/auth/otp/request', {
    method: 'POST',
    body: { email },
  })
}

/**
 * Verifies an OTP code (`POST /api/auth/otp/verify` `{ email, code }` -> `200`
 * TokenResponse) (Req 7.10).
 */
export function otpVerify(email: string, code: string): Promise<TokenResponse> {
  return apiRequest<TokenResponse>('/api/auth/otp/verify', {
    method: 'POST',
    body: { email, code },
  })
}

/**
 * Exchanges a Google ID token for the app's session or an activation bridge
 * (`POST /api/auth/google` `{ idToken }` -> `200` {@link GoogleLoginResponse}).
 * Both `AUTHENTICATED` and `ACTIVATION_REQUIRED` are HTTP 200; the no-account
 * (`403`) and deactivated (`403`) outcomes surface as a thrown `ApiError`
 * (Req 14.3, 14.4, 14.5).
 */
export function googleExchange(idToken: string): Promise<GoogleLoginResponse> {
  return apiRequest<GoogleLoginResponse>('/api/auth/google', {
    method: 'POST',
    body: { idToken },
  })
}
