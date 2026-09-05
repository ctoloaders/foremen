/**
 * Last_Allowed_Location / Denied_Location: UI-navigation state powering the
 * `/403` Forbidden_Page "go back" affordance (FOR-03-07).
 *
 * It tracks two things as the user navigates protected routes:
 *   - `lastAllowed`: the most recent protected route (full `path + query`) the
 *     Permission_Route_Guard actually granted (rendered).
 *   - `denied`: the route (full `path + query`) whose permission requirement was
 *     denied, causing the redirect to `/403`.
 *
 * It is written by the `PermissionGuard` and read by the `ForbiddenPage`.
 *
 * Unlike FOR-03-06's `return-location.ts` (which uses `sessionStorage` so it
 * survives the `/login` redirect and reload), this state is kept in
 * module-level variables: it only needs to survive within a single SPA session.
 * A full reload naturally resets it, and the guard repopulates `lastAllowed` on
 * the next granted navigation, while a cold deep-link into a forbidden route
 * correctly resolves the "go back" fallback to `/`.
 */

/** The Forbidden_Page path — never recorded as a Last_Allowed_Location. */
const FORBIDDEN_PATH = '/403'

/** The application home route, used as the Go_Back fallback target. */
const HOME_PATH = '/'

/** Last granted route (full `path + query`), or null when none yet. */
let lastAllowed: string | null = null

/** Route that triggered the last `/403` redirect (full `path + query`). */
let denied: string | null = null

/**
 * Records a route the guard just granted as the Last_Allowed_Location. Never
 * records the `/403` page itself (Req 4.4) so the Go_Back control can never
 * target the Forbidden_Page. Comparison ignores any query string.
 */
export function recordAllowedLocation(pathPlusQuery: string): void {
  const path = pathPlusQuery.split('?')[0] ?? pathPlusQuery
  if (path === FORBIDDEN_PATH) return
  lastAllowed = pathPlusQuery
}

/**
 * Records the route whose permission requirement was denied as the
 * Denied_Location (Req 4.8), so the Forbidden_Page can avoid returning the user
 * straight back into it.
 */
export function recordDeniedLocation(pathPlusQuery: string): void {
  denied = pathPlusQuery
}

/** Returns the current Last_Allowed_Location, or null when none is recorded. */
export function getLastAllowedLocation(): string | null {
  return lastAllowed
}

/** Returns the current Denied_Location, or null when none is recorded. */
export function getDeniedLocation(): string | null {
  return denied
}

/**
 * Resolves the target the Go_Back control should navigate to (Req 5.4, 5.5,
 * 5.6, 5.8):
 *   - no Last_Allowed_Location → `/`
 *   - Last_Allowed_Location equals the Denied_Location (full path+query) → `/`
 *     (avoids bouncing straight back into the just-denied route)
 *   - otherwise → the Last_Allowed_Location
 *
 * Comparison is exact string equality on the full `path + query`, so locations
 * differing only by query string are treated as distinct.
 */
export function resolveGoBackTarget(): string {
  if (lastAllowed == null) return HOME_PATH
  if (lastAllowed === denied) return HOME_PATH
  return lastAllowed
}
