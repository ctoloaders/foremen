/**
 * Api_Client — a shared `fetch` wrapper for the Foremen web client.
 *
 * Responsibilities delivered in this task (FOR-03-06, task 4.1):
 *  - Header assembly: `Authorization: Bearer <token>` (read from the Auth_Store
 *    at the module level, never via a React hook), `Accept-Language` (`ru`/`pl`
 *    fallback), `Content-Type: application/json` for JSON bodies, and
 *    `If-None-Match` for conditional `GET /me` requests.
 *  - Response handling: parse JSON on `2xx` (undefined for `204`), resolve a
 *    not-modified sentinel on `304` when `parse304AsSuccess` is set, and throw a
 *    typed {@link ApiError} on non-OK responses — surfacing the backend's
 *    verbatim (server-localized) message, or a generic i18n fallback when the
 *    body is not JSON, without ever throwing a parse exception.
 *  - {@link registerNavigate}: lets the app hand the client a navigation
 *    callback so the (non-React) module can redirect on forced logout.
 *
 * The `401` refresh / retry / single-flight dedup algorithm and the
 * forced-logout redirect are implemented here (task 5.1): a single shared
 * {@link RequestOptions.skipAuthRefresh}-guarded refresh serves all concurrent
 * `401`s, the original request is retried at most once with the fresh token,
 * and a refresh failure (or a missing Refresh_Token) captures the
 * Return_Location, clears the session, and redirects to `/login`.
 */

import i18n from '@/lib/i18n'
import { captureReturnLocation } from '@/lib/return-location'
import { useAuthStore } from '@/stores/auth-store'

/**
 * The typed error thrown for non-OK responses. Carries the HTTP status, the
 * message to surface (the backend's verbatim server-localized message when
 * available, otherwise a generic i18n fallback), and — when derivable from the
 * response body — the backend message `code`.
 */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly code?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/**
 * Sentinel resolved by {@link apiRequest} on a `304 Not Modified` response when
 * `parse304AsSuccess` is set (the `GET /me` conditional path). Callers reuse the
 * cached representation (Me_Cache) instead of treating `304` as an error.
 */
export const NOT_MODIFIED = Symbol('not-modified')

/** Type of the not-modified sentinel resolved on a `304` conditional response. */
export type NotModifiedSentinel = typeof NOT_MODIFIED

/**
 * Per-request options for {@link apiRequest}.
 */
export interface RequestOptions {
  /** HTTP method (defaults to `GET`). */
  method?: string
  /**
   * Request payload. When present it is serialized as JSON and a
   * `Content-Type: application/json` header is attached.
   */
  body?: unknown
  /** Extra headers, merged over (and able to override) the assembled headers. */
  headers?: Record<string, string>
  /**
   * Set true for the refresh call itself so the `401` handler does not attempt
   * to refresh on it. Consumed by the refresh/retry algorithm in task 5.1;
   * defined here so the options shape is stable.
   */
  skipAuthRefresh?: boolean
  /** Value for the `If-None-Match` header on conditional `GET /me` requests. */
  ifNoneMatch?: string
  /**
   * When true, a `304 Not Modified` response resolves with the
   * {@link NOT_MODIFIED} sentinel instead of throwing, so the caller can reuse
   * its cached representation.
   */
  parse304AsSuccess?: boolean
  /**
   * Optional callback invoked with the value of the response `ETag` header on a
   * successful (`2xx`) response, before the body is parsed. Used by the
   * `GET /me` caller to capture the strong ETag alongside the fetched
   * Current_User for later conditional revalidation (Me_Cache). The value is
   * `null` when the server did not send an `ETag`. This is the least-invasive
   * way to surface a single response header without exposing the whole
   * {@link Response} to callers.
   */
  onEtag?: (etag: string | null) => void
}

/**
 * A navigation callback registered by the app (from inside the router, where
 * `useNavigate` is available) so this non-React module can redirect on forced
 * logout.
 */
let navigate: ((to: string) => void) | null = null

/**
 * Registers the navigation callback the client uses to redirect (e.g. to
 * `/login` on forced logout). When none is registered, the refresh algorithm
 * falls back to `window.location.assign('/login')`.
 */
export function registerNavigate(fn: (to: string) => void): void {
  navigate = fn
}

/**
 * The single shared refresh operation. While a refresh is in progress every
 * concurrent `401` awaits this same promise instead of starting its own, so at
 * most one `POST /api/auth/refresh` is issued across all callers (Req 3.4).
 * Reset to `null` in a `finally` once the refresh settles.
 */
let refreshInFlight: Promise<void> | null = null

/**
 * Captures the current browser location as the Return_Location (unless it is a
 * Public_Route — {@link captureReturnLocation} already no-ops for those) so a
 * successful re-login can navigate the user back to where they were (Req 3.5,
 * 3.6, 3.8). Reads `window.location` because this module runs outside React and
 * has no router location.
 */
function captureCurrentLocation(): void {
  try {
    captureReturnLocation(window.location.pathname + window.location.search)
  } catch {
    // No window (non-browser env) — deep-link preservation degrades silently.
  }
}

/**
 * Redirects the browser to `/login` on forced logout. Prefers the registered
 * navigation callback (SPA navigation); falls back to
 * `window.location.assign('/login')` when none is registered yet (e.g. during
 * early hydration, before the router mounts) (Req 3.5, 3.6).
 */
function redirectToLogin(): void {
  if (navigate != null) {
    navigate('/login')
    return
  }
  try {
    window.location.assign('/login')
  } catch {
    // No window — nothing more we can do.
  }
}

/**
 * Forces a logout: captures the Return_Location, clears the session, and
 * redirects to `/login`. Invoked when a `401` cannot be recovered — either no
 * Refresh_Token is present or the refresh itself failed (Req 3.5, 3.6).
 */
function forceLogout(): void {
  captureCurrentLocation()
  useAuthStore.getState().clearSession()
  redirectToLogin()
}

/**
 * Runs (or reuses) the single shared refresh. The first `401` starts
 * `refreshInFlight`; concurrent `401`s reuse it (dedupe). The in-flight promise
 * is cleared in a `finally` once the refresh settles so a later expiry can
 * refresh again (Req 3.4).
 */
function runSharedRefresh(): Promise<void> {
  refreshInFlight ??= useAuthStore
    .getState()
    .refresh()
    .finally(() => {
      refreshInFlight = null
    })
  return refreshInFlight
}

/**
 * Resolves the `Accept-Language` header value from the persisted Locale:
 * `ru` when `foremen-locale` is `ru`, otherwise `pl` (PL fallback for unset or
 * any other value). Mirrors the existing `users-api.ts` convention (Req 2.3,
 * 10.4).
 */
function getAcceptLanguage(): string {
  try {
    if (localStorage.getItem('foremen-locale') === 'ru') return 'ru'
  } catch {
    // localStorage unavailable — fall through to the PL default.
  }
  return 'pl'
}

/**
 * Assembles the outbound headers for a request (Req 2.1–2.4, 13.2):
 *  - `Authorization: Bearer <token>` only when an access token is present in
 *    the Auth_Store (read via `getState()`, no React hook).
 *  - `Accept-Language` per {@link getAcceptLanguage}.
 *  - `Content-Type: application/json` only when a JSON body is present.
 *  - `If-None-Match` only when `ifNoneMatch` is supplied.
 * Caller-supplied `headers` are merged last so they can override the defaults.
 */
function buildHeaders(options: RequestOptions, hasBody: boolean): Record<string, string> {
  const headers: Record<string, string> = {
    'Accept-Language': getAcceptLanguage(),
  }

  const accessToken = useAuthStore.getState().accessToken
  if (accessToken != null) {
    headers.Authorization = `Bearer ${accessToken}`
  }

  if (hasBody) {
    headers['Content-Type'] = 'application/json'
  }

  if (options.ifNoneMatch != null) {
    headers['If-None-Match'] = options.ifNoneMatch
  }

  return { ...headers, ...options.headers }
}

/**
 * Builds an {@link ApiError} from a non-OK response. The body is parsed as the
 * `ForemenApiException`/`ErrorResponse` shape
 * `{ timestamp, status, error, message, path, fieldErrors?, code? }`; the
 * `message` is taken verbatim (server-localized via `Accept-Language`) and the
 * message `code` is used when the body exposes one. When the body is not JSON,
 * a generic i18n fallback (`auth.error.generic`) is used and no parse exception
 * is thrown (Req 2.5, 2.6, 10.3).
 */
async function buildApiError(response: Response): Promise<ApiError> {
  let message: string | undefined
  let code: string | undefined

  try {
    const body: unknown = await response.json()
    if (body != null && typeof body === 'object') {
      const record = body as Record<string, unknown>
      if (typeof record.message === 'string' && record.message.length > 0) {
        message = record.message
      }
      // The backend `ErrorResponse` does not currently expose a machine code
      // field, but derive one when a future/other body shape carries it.
      if (typeof record.code === 'string' && record.code.length > 0) {
        code = record.code
      }
    }
  } catch {
    // Non-JSON body — fall back to the generic localized message below.
  }

  message ??= i18n.t('auth.error.generic')

  return new ApiError(response.status, message, code)
}

/**
 * Issues a single HTTP request with freshly-assembled headers (so a retry picks
 * up a rotated access token) and returns the raw {@link Response}. No response
 * interpretation happens here — the caller ({@link apiRequest}) owns the `304`
 * sentinel, `401` refresh/retry, and error mapping.
 */
async function doFetch(path: string, options: RequestOptions): Promise<Response> {
  const hasBody = options.body !== undefined
  const headers = buildHeaders(options, hasBody)

  return fetch(path, {
    method: options.method ?? 'GET',
    headers,
    body: hasBody ? JSON.stringify(options.body) : undefined,
  })
}

/**
 * Interprets a settled {@link Response} into the typed result or throws an
 * {@link ApiError}:
 *  - `304` with `parse304AsSuccess` → the {@link NOT_MODIFIED} sentinel.
 *  - `204` → `undefined`.
 *  - other `2xx` → the parsed JSON body.
 *  - non-OK → an {@link ApiError} (verbatim backend message or fallback).
 */
async function handleResponse<T>(response: Response, options: RequestOptions): Promise<T> {
  if (response.status === 304 && options.parse304AsSuccess) {
    return NOT_MODIFIED as T
  }

  if (!response.ok) {
    throw await buildApiError(response)
  }

  // Surface the response ETag (e.g. for the `GET /me` Me_Cache) on success.
  options.onEtag?.(response.headers.get('ETag'))

  if (response.status === 204) {
    return undefined as T
  }

  return (await response.json()) as T
}

/**
 * Issues an HTTP request with the assembled auth/language/content headers and
 * typed response handling, including automatic single-flight refresh-and-retry
 * on `401`.
 *
 * Resolution:
 *  - `2xx` → parsed JSON body (`undefined` for `204 No Content`).
 *  - `304` with `parse304AsSuccess` → the {@link NOT_MODIFIED} sentinel
 *    (never thrown), so the caller reuses its cached representation.
 *
 * `401` handling (Req 3.1–3.8):
 *  1. For the refresh call itself (`skipAuthRefresh`), the `401` is propagated
 *     as an {@link ApiError} — the refresh path must not refresh recursively.
 *  2. When no Refresh_Token is present, the session is force-logged-out
 *     (capture Return_Location, `clearSession`, redirect to `/login`) and the
 *     original `401` is surfaced.
 *  3. Otherwise a single shared refresh runs (deduped across concurrent
 *     `401`s). On success the original request is retried exactly once with the
 *     fresh access token; a `401` on that retry forces logout (never a second
 *     refresh). On refresh failure the session is force-logged-out and the
 *     original caller is rejected.
 *
 * Rejection: a non-OK response rejects with an {@link ApiError} carrying the
 * status, the verbatim backend message (or a generic fallback), and the code
 * when derivable.
 */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await doFetch(path, options)

  if (response.status !== 401) {
    return handleResponse<T>(response, options)
  }

  // --- 401 handling ---

  // (1) The refresh call itself must never trigger another refresh; propagate
  // its 401 so the refresh path treats it as a refresh failure (Req 3.1).
  if (options.skipAuthRefresh) {
    throw await buildApiError(response)
  }

  // (2) No Refresh_Token → cannot recover; force logout and surface the 401
  // (Req 3.6, 3.8).
  if (useAuthStore.getState().refreshToken == null) {
    const error = await buildApiError(response)
    forceLogout()
    throw error
  }

  // (3) Refresh (single-flight) then retry the original request once.
  try {
    await runSharedRefresh()
  } catch {
    // Refresh failed (invalid/revoked/expired Refresh_Token): force logout and
    // reject the original caller with the original 401 (Req 3.5).
    const error = await buildApiError(response)
    forceLogout()
    throw error
  }

  // Retry exactly once. buildHeaders re-reads the store, so the fresh access
  // token is attached. This retried request never refreshes again — a 401 here
  // forces logout (Req 3.2, 3.3).
  const retryResponse = await doFetch(path, options)

  if (retryResponse.status === 401) {
    const error = await buildApiError(retryResponse)
    forceLogout()
    throw error
  }

  return handleResponse<T>(retryResponse, options)
}
