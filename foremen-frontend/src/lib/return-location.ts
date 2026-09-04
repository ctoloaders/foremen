/**
 * Return_Location: captures the protected location a user could not reach
 * (because authentication was required) so the app can navigate back there
 * after a successful (re-)login.
 *
 * Stored under `sessionStorage` (per-tab, cleared on tab close) so it survives
 * both the `/login` redirect and a full-page reload during Session_Hydration —
 * a module-level variable would be lost on reload.
 *
 * Every `sessionStorage` access is wrapped in `try/catch` (mirroring
 * `theme-store.ts`) so storage failures (unavailable / quota / private mode)
 * never throw to the caller.
 */

/** Fixed `sessionStorage` key under the `foremen-*` convention. */
const KEY = 'foremen-return-to'

/**
 * The set of Public_Routes reachable without authentication. A Public_Route is
 * never stored as a Return_Location so it can never be used as a return target.
 */
const PUBLIC_ROUTES = ['/login', '/auth/set-password', '/auth/otp'] as const

/**
 * Returns true when `pathPlusQuery` targets a Public_Route. The comparison is
 * made against the path portion only, so query strings and hashes do not
 * defeat the exclusion.
 */
function isPublicRoute(pathPlusQuery: string): boolean {
  const path = pathPlusQuery.split(/[?#]/)[0] ?? pathPlusQuery
  return (PUBLIC_ROUTES as readonly string[]).includes(path)
}

/**
 * Captures the attempted protected location (path plus query string) as the
 * Return_Location. No-op when the location is a Public_Route (Req 3.8, 9.6,
 * 12.5). Silently ignores storage errors.
 */
export function captureReturnLocation(pathPlusQuery: string): void {
  if (isPublicRoute(pathPlusQuery)) {
    // Never store a Public_Route as a return target.
    return
  }
  try {
    sessionStorage.setItem(KEY, pathPlusQuery)
  } catch {
    // sessionStorage unavailable — deep-link preservation degrades silently.
  }
}

/**
 * Reads and clears the stored Return_Location, returning it once (Req 12.4,
 * 12.6). Returns null when none is stored or storage is unavailable.
 */
export function consumeReturnLocation(): string | null {
  try {
    const value = sessionStorage.getItem(KEY)
    if (value === null) return null
    sessionStorage.removeItem(KEY)
    return value
  } catch {
    // sessionStorage unavailable — nothing to return.
    return null
  }
}

/**
 * Clears any stored Return_Location without returning it. Silently ignores
 * storage errors.
 */
export function clearReturnLocation(): void {
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    // sessionStorage unavailable — nothing to clear.
  }
}
