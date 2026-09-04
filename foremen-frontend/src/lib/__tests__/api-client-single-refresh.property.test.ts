// Feature: FOR-03-06-frontend-auth, Property 2: A single refresh serves all concurrent 401s
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import * as fc from 'fast-check'

/**
 * Feature: FOR-03-06-frontend-auth, Property 2: A single refresh serves all
 * concurrent 401s.
 *
 * For any number N (2..10) of concurrent `apiRequest` calls whose first attempt
 * receives `401` and whose retry (after a successful refresh) succeeds, the
 * Api_Client SHALL invoke `POST /api/auth/refresh` exactly once regardless of
 * N (single-flight dedup), and every original request SHALL be retried exactly
 * once with the rotated access token and ultimately resolve.
 *
 * **Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.7**
 */

// --- Test helpers -----------------------------------------------------------

const REFRESH_PATH = '/api/auth/refresh'

/** A minimal in-memory localStorage stub (jsdom's is replaced per Req/task). */
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

/** Builds a JSON `Response`-like object for the fetch stub. */
function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as unknown as Response
}

/**
 * A fetch stub modelling the concurrent-401 scenario:
 *  - Every protected request returns `401` on its FIRST call and `200` on the
 *    (single) retry — keyed per URL so N distinct requests each flip once.
 *  - The refresh endpoint returns a fresh, rotated token pair. It counts its
 *    own invocations so the test can assert "exactly one refresh".
 */
interface FetchModel {
  fetch: (input: string, init?: RequestInit) => Promise<Response>
  refreshCalls: () => number
  attemptsFor: (url: string) => number
  latestAccessToken: () => string | null
}

function makeFetchModel(): FetchModel {
  let refreshCalls = 0
  // Per-URL attempt counter: first attempt 401, subsequent attempts 200.
  const attempts = new Map<string, number>()

  let rotation = 0
  let latestAccess: string | null = null

  const fetchImpl = async (input: string, init?: RequestInit): Promise<Response> => {
    const url = typeof input === 'string' ? input : String(input)

    if (url === REFRESH_PATH) {
      refreshCalls += 1
      rotation += 1
      latestAccess = `access-${rotation}`
      return jsonResponse(200, {
        accessToken: latestAccess,
        refreshToken: `refresh-${rotation}`,
        expiresIn: 3600,
      })
    }

    const n = (attempts.get(url) ?? 0) + 1
    attempts.set(url, n)

    // The very first attempt for this URL is a 401; the retry (attempt 2+)
    // must carry the rotated bearer token and succeed.
    if (n === 1) {
      return jsonResponse(401, { message: 'expired' })
    }

    const authHeader =
      init?.headers != null
        ? (init.headers as Record<string, string>).Authorization
        : undefined
    return jsonResponse(200, { url, ok: true, sawAuth: authHeader ?? null })
  }

  return {
    fetch: fetchImpl,
    refreshCalls: () => refreshCalls,
    attemptsFor: (url: string) => attempts.get(url) ?? 0,
    latestAccessToken: () => latestAccess,
  }
}

// --- Setup ------------------------------------------------------------------

beforeEach(() => {
  installMemoryLocalStorage()
  // registerNavigate uses window; keep a no-op navigate available.
  vi.resetModules()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('Feature: FOR-03-06-frontend-auth, Property 2: A single refresh serves all concurrent 401s', () => {
  it('N concurrent 401s trigger exactly one refresh; every request retries once and resolves', async () => {
    await fc.assert(
      fc.asyncProperty(fc.integer({ min: 2, max: 10 }), async (n) => {
        // Fresh module registry per run so refreshInFlight and the store start clean.
        vi.resetModules()

        const model = makeFetchModel()
        vi.stubGlobal('fetch', model.fetch)
        // Register a navigate stub so no window.location.assign is invoked.
        const navigate = vi.fn<(to: string) => void>()

        const { apiRequest, registerNavigate } = await import('@/lib/api-client')
        const { useAuthStore } = await import('@/stores/auth-store')
        registerNavigate(navigate)

        // Seed an authenticated session with a refresh token present.
        useAuthStore.getState().setTokens({
          accessToken: 'access-0',
          refreshToken: 'refresh-0',
          expiresIn: 3600,
        })

        // Fire N concurrent requests to distinct paths (so each flips 401->200
        // independently) that will all 401 on their first attempt.
        const paths = Array.from({ length: n }, (_, i) => `/api/resource/${i}`)
        const results = await Promise.all(
          paths.map((p) => apiRequest<{ url: string; ok: boolean }>(p)),
        )

        // Exactly one refresh regardless of N (single-flight dedup) — Req 3.4.
        expect(model.refreshCalls()).toBe(1)

        // Every original request was attempted exactly twice: initial 401 + one
        // retry (Req 3.3 — at most one retry per request).
        for (const p of paths) {
          expect(model.attemptsFor(p)).toBe(2)
        }

        // Every request ultimately resolved with its success payload (Req 3.2).
        expect(results).toHaveLength(n)
        results.forEach((r, i) => {
          expect(r.ok).toBe(true)
          expect(r.url).toBe(paths[i])
        })

        // Retries carried the rotated access token (Req 3.2, 3.7): the store
        // now holds the token minted by the single refresh.
        expect(useAuthStore.getState().accessToken).toBe(model.latestAccessToken())
        expect(useAuthStore.getState().refreshToken).toBe('refresh-1')

        // The concurrent path is a success path — no forced logout / redirect.
        expect(navigate).not.toHaveBeenCalled()
        expect(useAuthStore.getState().isAuthenticated).toBe(
          useAuthStore.getState().user != null,
        )
      }),
      { numRuns: 100 },
    )
  })
})
