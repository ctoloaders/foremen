/**
 * Shared Playwright helpers for the FOR-03-06 frontend-auth e2e suite.
 *
 * The tests are deliberately **hermetic**: no real backend is required. Every
 * `/api/auth/**` call the app makes (the Api_Client uses relative paths, so a
 * `**` glob catches them regardless of host) is intercepted with
 * `page.route(...)` and answered with a canned response that mirrors the
 * backend contract consumed by FOR-03-06:
 *
 *   - POST /api/auth/login        -> 200 TokenResponse
 *   - POST /api/auth/otp/request  -> 200 (empty, anti-enumeration)
 *   - POST /api/auth/otp/verify   -> 200 TokenResponse
 *   - GET  /api/auth/me           -> 200 CurrentUserResponse (+ strong ETag)
 *   - POST /api/auth/refresh      -> 200 TokenResponse
 *   - POST /api/auth/logout       -> 204
 *
 * Route-mocking (rather than a Dockerized backend) is chosen for CI
 * determinism: the design's `test-cases.md` covers the real Dockerized stack;
 * the Playwright layer here validates the *frontend* wiring (guard, hydration,
 * Return_Location round-trip, OTP auto-submit) without a live server.
 *
 * Token_Storage keys and the Return_Location key are the fixed `foremen-*`
 * keys used by `auth-store.ts` and `return-location.ts`.
 */
import type { Page, Route } from '@playwright/test'

/** Fixed Token_Storage keys (see `src/stores/auth-store.ts`). */
export const ACCESS_TOKEN_KEY = 'foremen-access-token'
export const REFRESH_TOKEN_KEY = 'foremen-refresh-token'

/** Fixed Return_Location key (see `src/lib/return-location.ts`). */
export const RETURN_LOCATION_KEY = 'foremen-return-to'

/** A canned TokenResponse mirroring the backend `{ accessToken, refreshToken, expiresIn }`. */
export const TOKENS = {
  accessToken: 'e2e-access-token',
  refreshToken: 'e2e-refresh-token',
  expiresIn: 900,
}

/** A canned CurrentUserResponse mirroring `GET /api/auth/me`. */
export const CURRENT_USER = {
  id: 1,
  name: 'E2E User',
  email: 'e2e@example.com',
  roleCode: 'ADMIN',
  permissions: [],
}

/** Strong ETag returned for `/me` so the conditional-request plumbing has a value. */
const ME_ETAG = '"e2e-me-etag-v1"'

/** Fulfills a route with a `200 application/json` body. */
async function json200(route: Route, body: unknown): Promise<void> {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })
}

/**
 * Answers `GET /api/auth/me`: `200` (with a strong ETag) when the request
 * carries an `Authorization: Bearer` header, `401` otherwise. This makes
 * hydration with a stored token land authenticated and hydration/guard with no
 * token be treated as unauthenticated.
 */
async function handleMe(route: Route): Promise<void> {
  const authorization = route.request().headers()['authorization']
  const hasBearer = typeof authorization === 'string' && authorization.startsWith('Bearer ')
  if (!hasBearer) {
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({ status: 401, error: 'Unauthorized', message: 'error.auth.token.invalid' }),
    })
    return
  }
  // A cached If-None-Match matching our ETag would 304; the app always does a
  // forced /me after a transition, so we simply answer 200 here.
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    headers: { ETag: ME_ETAG, 'Cache-Control': 'no-cache, private' },
    body: JSON.stringify(CURRENT_USER),
  })
}

/**
 * Installs deterministic handlers for the whole `/api/auth/**` surface.
 * Call this before `page.goto(...)`.
 */
export async function installAuthMocks(page: Page): Promise<void> {
  await page.route('**/api/auth/**', async (route: Route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()

    if (path.endsWith('/api/auth/me') && method === 'GET') {
      await handleMe(route)
      return
    }
    if (path.endsWith('/api/auth/login') && method === 'POST') {
      await json200(route, TOKENS)
      return
    }
    if (path.endsWith('/api/auth/otp/request') && method === 'POST') {
      // Anti-enumeration silent success. The Api_Client parses a 200 body as
      // JSON, so return an empty JSON object (not an empty string) to mirror a
      // body-less success without provoking a parse error.
      await json200(route, {})
      return
    }
    if (path.endsWith('/api/auth/otp/verify') && method === 'POST') {
      await json200(route, TOKENS)
      return
    }
    if (path.endsWith('/api/auth/refresh') && method === 'POST') {
      await json200(route, TOKENS)
      return
    }
    if (path.endsWith('/api/auth/logout') && method === 'POST') {
      await route.fulfill({ status: 204, body: '' })
      return
    }
    if (path.endsWith('/api/auth/google') && method === 'POST') {
      await json200(route, { status: 'AUTHENTICATED', tokens: TOKENS })
      return
    }

    // Any other auth endpoint (e.g. set-password) — safe generic success.
    await json200(route, {})
  })
}

/**
 * Seeds Token_Storage in `localStorage` before the app boots so
 * Session_Hydration finds a stored session and (given the mocked `/me`) lands
 * authenticated. Must be called via `page.addInitScript` semantics — this
 * helper wraps that so it runs before any app script on the next navigation.
 */
export async function seedStoredSession(page: Page): Promise<void> {
  await page.addInitScript(
    ([accessKey, refreshKey, tokens]) => {
      window.localStorage.setItem(accessKey as string, (tokens as typeof TOKENS).accessToken)
      window.localStorage.setItem(refreshKey as string, (tokens as typeof TOKENS).refreshToken)
    },
    [ACCESS_TOKEN_KEY, REFRESH_TOKEN_KEY, TOKENS] as const,
  )
}

/** Clears Token_Storage and Return_Location before the app boots. */
export async function clearStoredSession(page: Page): Promise<void> {
  await page.addInitScript(
    ([accessKey, refreshKey, returnKey]) => {
      window.localStorage.removeItem(accessKey as string)
      window.localStorage.removeItem(refreshKey as string)
      window.sessionStorage.removeItem(returnKey as string)
    },
    [ACCESS_TOKEN_KEY, REFRESH_TOKEN_KEY, RETURN_LOCATION_KEY] as const,
  )
}

/** Reads the persisted Return_Location (sessionStorage) from the page. */
export async function readReturnLocation(page: Page): Promise<string | null> {
  return page.evaluate((key) => window.sessionStorage.getItem(key), RETURN_LOCATION_KEY)
}

/** Reads the persisted access token (localStorage) from the page. */
export async function readAccessToken(page: Page): Promise<string | null> {
  return page.evaluate((key) => window.localStorage.getItem(key), ACCESS_TOKEN_KEY)
}
