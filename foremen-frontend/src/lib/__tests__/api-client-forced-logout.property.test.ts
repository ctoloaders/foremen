// Feature: FOR-03-06-frontend-auth, Property 3: A repeated 401 does not loop and forces logout
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import * as fc from 'fast-check'

/**
 * Feature: FOR-03-06-frontend-auth, Property 3: A repeated 401 does not loop
 * and forces logout.
 *
 * When a request's retry (after a refresh) also returns `401`, OR the refresh
 * call itself fails, the Api_Client SHALL NOT loop: `POST /api/auth/refresh` is
 * invoked at most once. The session SHALL be cleared (`clearSession()`), the
 * browser SHALL be redirected to `/login` (via the registered navigate stub, or
 * the `window.location` fallback when none is registered), and the original
 * caller SHALL reject with an ApiError.
 *
 * **Validates: Requirements 3.3, 3.5, 3.6**
 */

const REFRESH_PATH = '/api/auth/refresh'

function installMemoryLocalStorage(): void {
  const store: Record<string, string> = {}
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => store[key] ?? null,
    setItem: (key: string, value: string) => {
      store[key] = value
    },
    removeItem: (key: string) => {
      delete store[key]
    },
    clear: () => {
      Object.keys(store).forEach((k) => delete store[k])
    },
  })
}

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response
}

/**
 * The three ways a 401 must end in a forced logout without looping:
 *  - 'refresh-fails'     : refresh returns 401 (invalid/revoked/expired) — Req 3.5
 *  - 'retry-401'         : refresh 200 but the retried request 401s again — Req 3.3, 3.5
 *  - 'no-refresh-token'  : no Refresh_Token present, so no refresh at all — Req 3.6
 */
type Scenario = 'refresh-fails' | 'retry-401' | 'no-refresh-token'

interface FetchModel {
  fetch: (input: string, init?: RequestInit) => Promise<Response>
  refreshCalls: () => number
}

function makeFetchModel(scenario: Scenario): FetchModel {
  let refreshCalls = 0

  const fetchImpl = async (input: string): Promise<Response> => {
    const url = typeof input === 'string' ? input : String(input)

    if (url === REFRESH_PATH) {
      refreshCalls += 1
      if (scenario === 'refresh-fails') {
        return jsonResponse(401, { message: 'refresh token revoked' })
      }
      // 'retry-401': refresh succeeds and rotates the token pair.
      return jsonResponse(200, {
        accessToken: 'access-new',
        refreshToken: 'refresh-new',
        expiresIn: 3600,
      })
    }

    // Every protected request keeps returning 401 (both the initial attempt and
    // any retry), so the retried request in 'retry-401' also 401s.
    return jsonResponse(401, { message: 'expired' })
  }

  return { fetch: fetchImpl, refreshCalls: () => refreshCalls }
}

beforeEach(() => {
  installMemoryLocalStorage()
  vi.resetModules()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('Feature: FOR-03-06-frontend-auth, Property 3: A repeated 401 does not loop and forces logout', () => {
  it('forces logout via the registered navigate stub without ever refreshing more than once', async () => {
    await fc.assert(
      fc.asyncProperty(
        fc.constantFrom<Scenario>('refresh-fails', 'retry-401', 'no-refresh-token'),
        // An arbitrary protected path (leading '/'); never a public route.
        fc
          .array(
            fc
              .string({ minLength: 1, maxLength: 10 })
              .map((s) => s.replace(/[/?#]/g, ''))
              .filter((s) => s.length > 0),
            { minLength: 1, maxLength: 3 },
          )
          .map((segs) => '/' + segs.join('/')),
        async (scenario, protectedPathRaw) => {
          vi.resetModules()

          const model = makeFetchModel(scenario)
          vi.stubGlobal('fetch', model.fetch)

          // Register a navigate stub so the SPA-navigation branch is taken.
          const navigate = vi.fn<(to: string) => void>()

          // window.location.assign fallback should NOT be used when navigate is
          // registered; spy on it to assert it stays untouched.
          const assign = vi.fn<(url: string | URL) => void>()
          vi.stubGlobal('window', {
            ...globalThis.window,
            location: {
              pathname: '/dashboard',
              search: '',
              assign,
            },
          })

          const { apiRequest, registerNavigate, ApiError } = await import(
            '@/lib/api-client'
          )
          const { useAuthStore } = await import('@/stores/auth-store')
          registerNavigate(navigate)

          // Seed a session. For 'no-refresh-token' we deliberately withhold the
          // refresh token so the client cannot refresh (Req 3.6).
          if (scenario === 'no-refresh-token') {
            useAuthStore.setState({
              accessToken: 'access-0',
              refreshToken: null,
              user: { id: 1, name: 'U', email: 'u@x.io', roleCode: 'ADMIN', permissions: [] },
              isAuthenticated: true,
            })
          } else {
            useAuthStore.getState().setTokens({
              accessToken: 'access-0',
              refreshToken: 'refresh-0',
              expiresIn: 3600,
            })
            useAuthStore.getState().setUser({
              id: 1,
              name: 'U',
              email: 'u@x.io',
              roleCode: 'ADMIN',
              permissions: [],
            })
          }

          const protectedPath = protectedPathRaw

          // The original caller must reject with an ApiError.
          await expect(apiRequest(protectedPath)).rejects.toBeInstanceOf(ApiError)

          // No infinite loop: refresh is called at most once (Req 3.3).
          expect(model.refreshCalls()).toBeLessThanOrEqual(1)
          // 'no-refresh-token' never refreshes; the other two refresh exactly once.
          expect(model.refreshCalls()).toBe(scenario === 'no-refresh-token' ? 0 : 1)

          // Session cleared (Req 3.5, 3.6).
          const state = useAuthStore.getState()
          expect(state.accessToken).toBeNull()
          expect(state.refreshToken).toBeNull()
          expect(state.user).toBeNull()
          expect(state.isAuthenticated).toBe(false)

          // Redirect to /login via the registered navigate (not the fallback).
          expect(navigate).toHaveBeenCalledWith('/login')
          expect(assign).not.toHaveBeenCalled()
        },
      ),
      { numRuns: 100 },
    )
  })

  it('falls back to window.location redirect to /login when no navigate is registered', async () => {
    await fc.assert(
      fc.asyncProperty(
        fc.constantFrom<Scenario>('refresh-fails', 'retry-401', 'no-refresh-token'),
        async (scenario) => {
          vi.resetModules()

          const model = makeFetchModel(scenario)
          vi.stubGlobal('fetch', model.fetch)

          const assign = vi.fn<(url: string | URL) => void>()
          vi.stubGlobal('window', {
            ...globalThis.window,
            location: {
              pathname: '/dashboard',
              search: '',
              assign,
            },
          })

          // NOTE: registerNavigate is intentionally NOT called, so the client
          // must fall back to window.location.assign('/login') (Req 3.5, 3.6).
          const { apiRequest, ApiError } = await import('@/lib/api-client')
          const { useAuthStore } = await import('@/stores/auth-store')

          if (scenario === 'no-refresh-token') {
            useAuthStore.setState({
              accessToken: 'access-0',
              refreshToken: null,
              user: { id: 1, name: 'U', email: 'u@x.io', roleCode: 'ADMIN', permissions: [] },
              isAuthenticated: true,
            })
          } else {
            useAuthStore.getState().setTokens({
              accessToken: 'access-0',
              refreshToken: 'refresh-0',
              expiresIn: 3600,
            })
          }

          await expect(apiRequest('/some/protected')).rejects.toBeInstanceOf(ApiError)

          expect(model.refreshCalls()).toBeLessThanOrEqual(1)

          const state = useAuthStore.getState()
          expect(state.accessToken).toBeNull()
          expect(state.refreshToken).toBeNull()
          expect(state.isAuthenticated).toBe(false)

          // Fallback redirect went to /login.
          expect(assign).toHaveBeenCalledWith('/login')
        },
      ),
      { numRuns: 100 },
    )
  })
})
