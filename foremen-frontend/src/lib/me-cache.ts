/**
 * Me_Cache — an in-memory cache of the last `GET /api/auth/me` result together
 * with its strong ETag, used for cheap conditional revalidation via
 * `If-None-Match`.
 *
 * The cache is intentionally in-memory (module state, not persisted): the ETag
 * is a revalidation optimization, not durable session state. A stale persisted
 * ETag across reloads would risk a `304` against an empty cache, so session
 * hydration always performs a forced (no `If-None-Match`) fetch instead.
 *
 * The canonical shared frontend auth types (`CurrentUser`, etc.) are introduced
 * by the auth-api layer (task 6.1). To keep this module self-contained and
 * importable before that layer exists, it defines a minimal `CurrentUser`
 * shape here matching the `GET /api/auth/me` contract
 * (`CurrentUserResponse { id, name, email, roleCode, permissions }`).
 */

/** A single resource/operations permission entry from `GET /api/auth/me`. */
export interface MeCachePermission {
  resource: string
  operations: string[]
}

/**
 * Minimal Current_User shape consistent with the `GET /api/auth/me` contract
 * and the auth-api layer's `CurrentUser` (see design, task 6.1). Defined here so
 * this module is self-consistent and importable on its own.
 */
export interface CurrentUser {
  id: number
  name: string
  email: string
  roleCode: string
  permissions: MeCachePermission[]
}

/** The `{ currentUser, etag }` pair held by the cache. */
export interface MeCache {
  currentUser: CurrentUser | null
  etag: string | null
}

/** Module-level in-memory cache state. */
const cache: MeCache = {
  currentUser: null,
  etag: null,
}

/**
 * Returns the current Me_Cache snapshot: the last cached Current_User and its
 * ETag (either may be `null` when nothing has been cached yet).
 */
export function getMeCache(): MeCache {
  return { currentUser: cache.currentUser, etag: cache.etag }
}

/**
 * Records the result of a `200` `GET /api/auth/me` response: the fetched
 * Current_User and its strong ETag. `etag` may be `null` when the server did
 * not send one, in which case subsequent fetches are forced (no
 * `If-None-Match`).
 */
export function setMeCache(user: CurrentUser, etag: string | null): void {
  cache.currentUser = user
  cache.etag = etag
}

/**
 * Invalidates the cached ETag so the next `GET /api/auth/me` is a forced fetch
 * (issued without an `If-None-Match` header) rather than a conditional request.
 * The cached Current_User is left in place; only the revalidation token is
 * cleared. Called after every auth transition (login, set-password, otp-verify,
 * google) and on logout.
 */
export function invalidateMeCache(): void {
  cache.etag = null
}
