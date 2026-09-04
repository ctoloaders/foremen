import { create } from 'zustand'

import * as authApi from '@/app/auth/api/auth-api'
import { NOT_MODIFIED, apiRequest } from '@/lib/api-client'
import { getMeCache, invalidateMeCache } from '@/lib/me-cache'

/**
 * Session-hydration lifecycle status. `'pending'` until the app has resolved
 * whether a stored session is valid; `'done'` once resolution completes.
 */
export type HydrationStatus = 'pending' | 'done'

/**
 * The authenticated identity returned by `GET /api/auth/me`.
 * In FOR-03-06 the `permissions` array is stored but not consumed for
 * visibility (that is deferred to FOR-03-07).
 */
export interface CurrentUser {
  id: number
  name: string
  email: string
  roleCode: string
  permissions: { resource: string; operations: string[] }[]
}

/**
 * The backend token DTO returned by login, refresh, set-password, and
 * OTP-verify. `expiresIn` is the access-token lifetime in seconds.
 */
export interface TokenResponse {
  accessToken: string
  refreshToken: string
  expiresIn: number
}

/**
 * Fixed `localStorage` keys for Token_Storage (the `foremen-*` convention).
 * These are the only keys the Auth_Store reads or writes for tokens.
 */
export const ACCESS_TOKEN_KEY = 'foremen-access-token'
export const REFRESH_TOKEN_KEY = 'foremen-refresh-token'

/**
 * Reads a single token from localStorage. Silently returns `null` on error
 * (storage unavailable, private browsing) so reads never throw (Req 1.6).
 * Empty strings are normalized to `null` so a blank stored value is treated
 * as "no token".
 */
function readToken(key: string): string | null {
  try {
    const value = localStorage.getItem(key)
    return value != null && value !== '' ? value : null
  } catch {
    // localStorage unavailable — operate in memory-only mode
    return null
  }
}

/**
 * Writes a single token to localStorage. Silently ignores errors
 * (quota exceeded, private browsing) so writes never throw (Req 1.6).
 */
function writeToken(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    // localStorage unavailable — operate in memory-only mode
  }
}

/**
 * Removes a single token from localStorage. Silently ignores errors
 * so clearing the session never throws (Req 1.6).
 */
function removeToken(key: string): void {
  try {
    localStorage.removeItem(key)
  } catch {
    // localStorage unavailable — operate in memory-only mode
  }
}

/**
 * Derives the `isAuthenticated` flag. A session is authenticated only when
 * both a user and an access token are present (Req 1.5).
 */
function deriveIsAuthenticated(
  user: CurrentUser | null,
  accessToken: string | null,
): boolean {
  return user != null && accessToken != null
}

export interface AuthState {
  // Session state (Req 1.1)
  user: CurrentUser | null
  accessToken: string | null
  refreshToken: string | null
  isAuthenticated: boolean
  hydrationStatus: HydrationStatus

  // Public action used by the Api_Client 401 refresh path (Req 3.2, 3.7).
  refresh: () => Promise<void>

  // Session hydration on application load (Req 4.1–4.5).
  hydrate: () => Promise<void>

  // Employee email + password sign-in (Req 5.3, 5.4).
  login: (email: string, password: string) => Promise<void>

  // Sign-out: best-effort backend revoke, then local clear (Req 8.1–8.4).
  logout: () => Promise<void>

  // Reusable post-transition identity refresh: invalidate Me_Cache then force a
  // `GET /api/auth/me` and record the user. Called after every successful auth
  // transition — login (internally), set-password, otp-verify, google — so the
  // pages can share the same identity-population step (Req 4.6, 13.5).
  refreshIdentity: () => Promise<void>

  // Internal setters used by the auth flows and Api_Client.
  setTokens: (tokens: TokenResponse) => void
  setUser: (user: CurrentUser) => void
  clearSession: () => void
}

export const useAuthStore = create<AuthState>((set, get) => ({
  // Initial state is unauthenticated; hydration has not yet run.
  user: null,
  accessToken: null,
  refreshToken: null,
  isAuthenticated: false,
  hydrationStatus: 'pending',

  /**
   * Exchanges the stored Refresh_Token for a fresh token pair via
   * `POST /api/auth/refresh`, then records the rotated pair with
   * {@link AuthState.setTokens} (Req 3.2, 3.7).
   *
   * The refresh call is issued with `skipAuthRefresh` so the Api_Client's
   * `401` handler never tries to refresh the refresh call itself; a non-`200`
   * (invalid / revoked / expired Refresh_Token) surfaces as a rejected promise
   * that the Api_Client treats as refresh failure (forced logout). This action
   * only rotates tokens — clearing the session on failure is the caller's job.
   */
  refresh: async () => {
    const { refreshToken } = get()
    const tokens = await apiRequest<TokenResponse>('/api/auth/refresh', {
      method: 'POST',
      body: { refreshToken },
      skipAuthRefresh: true,
    })
    get().setTokens(tokens)
  },

  /**
   * Session_Hydration on application load (Req 4.1–4.5).
   *
   * 1. No stored Access_Token → treat the user as unauthenticated and skip the
   *    `/me` call; finish with `hydrationStatus = 'done'` (Req 4.4).
   * 2. Access_Token present → adopt the stored tokens into state, then call
   *    `authApi.getMe()` through the Api_Client (which attaches the bearer
   *    token and any cached `If-None-Match`):
   *      - `200` → `setUser` + authenticated (Req 4.2).
   *      - `304` (NOT_MODIFIED) → reuse the cached `Me_Cache.currentUser`
   *        (Req 13.3); if the cache is somehow empty, treat it as unrecoverable.
   *      - unrecoverable failure (e.g. `401` whose refresh also failed) →
   *        `clearSession()` so the user is treated as unauthenticated (Req 4.3).
   * 3. Always finish with `hydrationStatus = 'done'` so the Auth_Guard can stop
   *    showing its loading state (Req 4.5).
   */
  hydrate: async () => {
    const accessToken = readToken(ACCESS_TOKEN_KEY)

    // No stored session — resolve as unauthenticated without touching /me.
    if (accessToken == null) {
      set({ hydrationStatus: 'done' })
      return
    }

    // Adopt the persisted tokens so the Api_Client attaches the bearer token
    // (and can refresh on 401) during the /me call.
    const refreshToken = readToken(REFRESH_TOKEN_KEY)
    set({ accessToken, refreshToken })

    try {
      const result = await authApi.getMe()
      if (result === NOT_MODIFIED) {
        const cachedUser = getMeCache().currentUser
        if (cachedUser != null) {
          get().setUser(cachedUser)
        } else {
          // 304 with an empty cache is unrecoverable — force a clean state.
          get().clearSession()
        }
      } else {
        get().setUser(result)
      }
    } catch {
      // 401 whose automatic refresh also failed (or any other unrecoverable
      // /me error): the stored session is not usable.
      get().clearSession()
    } finally {
      set({ hydrationStatus: 'done' })
    }
  },

  /**
   * Employee email + password sign-in (Req 5.3, 5.4). Exchanges the credentials
   * for a token pair via `POST /api/auth/login`, records the tokens, then
   * populates the Current_User through {@link AuthState.refreshIdentity}. Any
   * backend error (invalid credentials, not-activated, deactivated) surfaces to
   * the caller as a rejected promise carrying the typed `ApiError` so the
   * LoginPage can present the localized message.
   */
  login: async (email: string, password: string) => {
    const tokens = await authApi.login(email, password)
    get().setTokens(tokens)
    await get().refreshIdentity()
  },

  /**
   * Post-transition identity refresh shared by every successful auth transition
   * (login, set-password, otp-verify, google) (Req 4.6, 13.5).
   *
   * Invalidates the Me_Cache ETag so the following `GET /api/auth/me` is a
   * forced fetch (no `If-None-Match`), then records the returned Current_User.
   * A forced fetch always returns `200`, so the `NOT_MODIFIED` branch is not
   * expected here; it is handled defensively by falling back to the cached user
   * when present.
   */
  refreshIdentity: async () => {
    invalidateMeCache()
    const result = await authApi.getMe()
    if (result === NOT_MODIFIED) {
      const cachedUser = getMeCache().currentUser
      if (cachedUser != null) {
        get().setUser(cachedUser)
      }
      return
    }
    get().setUser(result)
  },

  /**
   * Sign-out (Req 8.1–8.4). Best-effort revoke of the stored Refresh_Token via
   * `POST /api/auth/logout` (swallowing any network/HTTP failure since the
   * endpoint is idempotent), then always clears the local session and
   * invalidates the Me_Cache. The caller is responsible for redirecting to
   * `/login`.
   */
  logout: async () => {
    const { refreshToken } = get()
    if (refreshToken != null) {
      try {
        await authApi.logout(refreshToken)
      } catch {
        // Best-effort: the endpoint is idempotent and the local session is
        // cleared regardless of the logout response or a network failure.
      }
    }
    get().clearSession()
    invalidateMeCache()
  },

  /**
   * Records a new TokenResponse: persists both tokens to Token_Storage under
   * the fixed keys and updates in-memory state, recomputing isAuthenticated
   * (Req 1.3, 1.5).
   */
  setTokens: (tokens: TokenResponse) => {
    writeToken(ACCESS_TOKEN_KEY, tokens.accessToken)
    writeToken(REFRESH_TOKEN_KEY, tokens.refreshToken)
    set((state) => ({
      accessToken: tokens.accessToken,
      refreshToken: tokens.refreshToken,
      isAuthenticated: deriveIsAuthenticated(state.user, tokens.accessToken),
    }))
  },

  /**
   * Sets the current user and recomputes isAuthenticated (Req 1.5).
   */
  setUser: (user: CurrentUser) => {
    set((state) => ({
      user,
      isAuthenticated: deriveIsAuthenticated(user, state.accessToken),
    }))
  },

  /**
   * Clears the session: removes both stored tokens and resets all session
   * state to unauthenticated (Req 1.4).
   */
  clearSession: () => {
    removeToken(ACCESS_TOKEN_KEY)
    removeToken(REFRESH_TOKEN_KEY)
    set({
      user: null,
      accessToken: null,
      refreshToken: null,
      isAuthenticated: false,
    })
  },
}))
