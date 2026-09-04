// Feature: FOR-03-06-frontend-auth, Property 1: isAuthenticated equals presence of both user and access token
import { describe, it, expect, beforeEach, vi } from 'vitest'
import * as fc from 'fast-check'
import type { CurrentUser, TokenResponse } from '@/stores/auth-store'

// --- Mocks ---

beforeEach(() => {
  // In-memory localStorage so token writes/reads never touch the real one.
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

  // Reset module registry so the store is freshly created each run.
  vi.resetModules()
})

// --- Arbitraries ---

const currentUserArb: fc.Arbitrary<CurrentUser> = fc.record({
  id: fc.integer({ min: 1, max: 1_000_000 }),
  name: fc.string({ minLength: 1, maxLength: 40 }),
  email: fc.emailAddress(),
  roleCode: fc.constantFrom('ADMIN', 'CLIENT', 'FOREMAN'),
  permissions: fc.array(
    fc.record({
      resource: fc.string({ minLength: 1, maxLength: 20 }),
      operations: fc.array(fc.constantFrom('READ', 'WRITE', 'DELETE'), {
        maxLength: 3,
      }),
    }),
    { maxLength: 4 },
  ),
})

const tokenResponseArb: fc.Arbitrary<TokenResponse> = fc.record({
  accessToken: fc.string({ minLength: 1, maxLength: 64 }),
  refreshToken: fc.string({ minLength: 1, maxLength: 64 }),
  expiresIn: fc.integer({ min: 1, max: 86_400 }),
})

/**
 * Feature: FOR-03-06-frontend-auth, Property 1: isAuthenticated equals presence
 * of both user and access token
 *
 * For any combination of (user present/absent) x (access token present/absent),
 * after applying the store setters, `isAuthenticated` SHALL be true if and only
 * if both `user` and `accessToken` are non-null.
 *
 * **Validates: Requirements 1.5**
 */
describe('Feature: FOR-03-06-frontend-auth, Property 1: isAuthenticated equals presence of both user and access token', () => {
  it('isAuthenticated === (user != null && accessToken != null) across all four combinations', async () => {
    const { useAuthStore } = await import('@/stores/auth-store')

    await fc.assert(
      fc.asyncProperty(
        fc.boolean(), // apply a user?
        fc.boolean(), // apply an access token?
        currentUserArb,
        tokenResponseArb,
        async (hasUser, hasToken, user, tokens) => {
          // Start from a clean, unauthenticated session for every run.
          useAuthStore.getState().clearSession()

          if (hasUser) {
            // setUser sets user via a store setter.
            useAuthStore.getState().setUser(user)
          }

          if (hasToken) {
            // setTokens sets accessToken (and refreshToken) via a store setter.
            useAuthStore.getState().setTokens(tokens)
          }

          const state = useAuthStore.getState()
          const expectedUser = hasUser ? user : null
          const expectedToken = hasToken ? tokens.accessToken : null

          // Sanity: the setters produced the state combination we asked for.
          expect(state.user).toEqual(expectedUser)
          expect(state.accessToken).toBe(expectedToken)

          // The property under test.
          expect(state.isAuthenticated).toBe(
            state.user != null && state.accessToken != null,
          )
          // And it matches the intended combination.
          expect(state.isAuthenticated).toBe(hasUser && hasToken)
        },
      ),
      { numRuns: 100 },
    )
  })

  it('setting a token after a user, and clearing, keeps the derivation consistent at each step', async () => {
    const { useAuthStore } = await import('@/stores/auth-store')

    await fc.assert(
      fc.asyncProperty(
        currentUserArb,
        tokenResponseArb,
        async (user, tokens) => {
          const s = useAuthStore.getState

          s().clearSession()
          expect(s().isAuthenticated).toBe(false)

          // user only -> not authenticated (no access token yet)
          s().setUser(user)
          expect(s().isAuthenticated).toBe(false)

          // user + token -> authenticated
          s().setTokens(tokens)
          expect(s().isAuthenticated).toBe(true)

          // clear -> not authenticated
          s().clearSession()
          expect(s().isAuthenticated).toBe(false)
        },
      ),
      { numRuns: 100 },
    )
  })
})
