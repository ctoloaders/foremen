// Feature: FOR-03-07-menu-visibility, Property 4: No current user denies everything
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook } from '@testing-library/react'
import * as fc from 'fast-check'

import { usePermission } from '@/hooks/usePermission'
import { useAuthStore } from '@/stores/auth-store'

// --- Setup ---

beforeEach(() => {
  // In-memory localStorage so the Auth_Store's token writes/reads (via
  // clearSession) never touch the real one.
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

  // The deny-by-default case under test: no Current_User in the Auth_Store
  // (unauthenticated or not yet hydrated).
  useAuthStore.setState({ user: null })
})

afterEach(() => {
  useAuthStore.setState({ user: null })
  vi.unstubAllGlobals()
})

// --- Arbitraries ---

/**
 * Arbitrary Resource_Code / Operation_Code strings. Kept broad (any string,
 * including empty) because the property must hold for EVERY pair, not just the
 * well-known matrix codes.
 */
const codeArb = fc.string({ maxLength: 30 })

/**
 * Feature: FOR-03-07-menu-visibility, Property 4: No current user denies
 * everything
 *
 * For any `(resource, operation)` pair, when there is no `Current_User` in the
 * Auth_Store, `hasPermission` returns `false`.
 *
 * **Validates: Requirements 1.4**
 */
describe('usePermission — Property 4: No current user denies everything', () => {
  it('returns false for every (resource, operation) pair when there is no Current_User', () => {
    fc.assert(
      fc.property(codeArb, codeArb, (resource, operation) => {
        // Render the hook with useAuthStore holding a null user.
        const { result } = renderHook(() => usePermission())
        expect(result.current.hasPermission(resource, operation)).toBe(false)
      }),
      { numRuns: 100 },
    )
  })
})
