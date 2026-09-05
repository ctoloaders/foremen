// Feature: FOR-03-07-menu-visibility, Property 2: ADMIN is always granted
// Feature: FOR-03-07-menu-visibility, Property 3: ADMIN bypass requires an exact code match
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import * as fc from 'fast-check'
import { act, renderHook } from '@testing-library/react'
import type { CurrentUser } from '@/stores/auth-store'
import { useAuthStore } from '@/stores/auth-store'
import { usePermission } from '@/hooks/usePermission'

// --- Mocks ---

beforeEach(() => {
  // In-memory localStorage so the store's clearSession/setUser token handling
  // never touches the real one and each property run starts isolated.
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
  useAuthStore.getState().clearSession()
})

afterEach(() => {
  useAuthStore.getState().clearSession()
  vi.unstubAllGlobals()
})

const ADMIN_ROLE_CODE = 'ADMIN'

// --- Arbitraries ---

/**
 * An arbitrary resource/operation code segment. Kept non-empty and free of the
 * `:` key delimiter so a generated pair can never collide with the internal
 * `"<RESOURCE>:<OPERATION>"` membership key.
 */
const codeArb = fc
  .string({ minLength: 1, maxLength: 20 })
  .map((s) => s.replace(/:/g, ''))
  .filter((s) => s.length > 0)

/** A single `{ resource, operations[] }` permission entry. */
const permissionEntryArb = fc.record({
  resource: codeArb,
  operations: fc.array(codeArb, { maxLength: 4 }),
})

/** An arbitrary permissions array, including the empty case. */
const permissionsArb = fc.array(permissionEntryArb, { maxLength: 5 })

/**
 * Builds a Current_User with the given roleCode and permissions. Only the
 * fields the Permission_Hook reads (`roleCode`, `permissions`) matter; the rest
 * are fixed placeholders.
 */
function makeUser(
  roleCode: string,
  permissions: CurrentUser['permissions'],
): CurrentUser {
  return {
    id: 1,
    name: 'Test User',
    email: 'test@example.com',
    roleCode,
    permissions,
  }
}

/**
 * Sets the given user on the Auth_Store (wrapped in `act` so the store update
 * is committed), renders `usePermission`, evaluates the predicate against each
 * `(resource, operation)` pair, then unmounts so no subscriber leaks into the
 * next property run. Returns the boolean results in order.
 */
function evaluateWithUser(
  user: CurrentUser,
  pairs: [resource: string, operation: string][],
): boolean[] {
  act(() => {
    useAuthStore.getState().setUser(user)
  })
  const { result, unmount } = renderHook(() => usePermission())
  const results = pairs.map(([resource, operation]) =>
    result.current.hasPermission(resource, operation),
  )
  unmount()
  return results
}

/**
 * Near-miss variants of the exact literal `ADMIN` that MUST NOT be bypassed:
 * case-differing, whitespace-padded, or with extra characters. Excludes the
 * exact literal itself.
 */
const adminNearMissArb: fc.Arbitrary<string> = fc
  .oneof(
    // Case variations
    fc.constantFrom('admin', 'Admin', 'aDmin', 'ADMIn', 'adMIN'),
    // Whitespace padding
    fc.constantFrom(' ADMIN', 'ADMIN ', ' ADMIN ', '\tADMIN', 'ADMIN\n'),
    // Extra characters
    fc.constantFrom('ADMINX', 'XADMIN', 'ADMIN1', 'SUPERADMIN', 'ADMIN_ROLE'),
    // A random string, filtered to never be the exact literal
    fc.string({ minLength: 0, maxLength: 15 }),
  )
  .filter((code) => code !== ADMIN_ROLE_CODE)

/**
 * Feature: FOR-03-07-menu-visibility, Property 2: ADMIN is always granted
 *
 * For any `(resource, operation)` pair and any permissions array (including
 * empty or one that would otherwise deny), when `roleCode` equals the exact
 * literal `ADMIN`, `hasPermission` returns `true`.
 *
 * **Validates: Requirements 1.2**
 */
describe('Feature: FOR-03-07-menu-visibility, Property 2: ADMIN is always granted', () => {
  it('returns true for every (resource, operation) pair regardless of the permissions array', () => {
    fc.assert(
      fc.property(
        permissionsArb,
        codeArb,
        codeArb,
        (permissions, resource, operation) => {
          const [granted] = evaluateWithUser(
            makeUser(ADMIN_ROLE_CODE, permissions),
            [[resource, operation]],
          )

          // ADMIN is granted every pair without consulting the array.
          expect(granted).toBe(true)
        },
      ),
      { numRuns: 100 },
    )
  })

  it('grants a pair for ADMIN even when the permissions array is empty', () => {
    fc.assert(
      fc.property(codeArb, codeArb, (resource, operation) => {
        const [granted] = evaluateWithUser(makeUser(ADMIN_ROLE_CODE, []), [
          [resource, operation],
        ])

        expect(granted).toBe(true)
      }),
      { numRuns: 100 },
    )
  })
})

/**
 * Feature: FOR-03-07-menu-visibility, Property 3: ADMIN bypass requires an exact code match
 *
 * For any `roleCode` that is a near-miss of `ADMIN` (case-differing,
 * whitespace-padded, or with extra characters) evaluated against an empty
 * `permissions` array, `hasPermission` returns `false` for every pair; only the
 * exact literal `ADMIN` is bypassed.
 *
 * **Validates: Requirements 1.6**
 */
describe('Feature: FOR-03-07-menu-visibility, Property 3: ADMIN bypass requires an exact code match', () => {
  it('denies every pair for a near-miss roleCode against an empty permissions array', () => {
    fc.assert(
      fc.property(
        adminNearMissArb,
        codeArb,
        codeArb,
        (roleCode, resource, operation) => {
          const [granted] = evaluateWithUser(makeUser(roleCode, []), [
            [resource, operation],
          ])

          // A near-miss is NOT bypassed and has no grants, so every pair denies.
          expect(granted).toBe(false)
        },
      ),
      { numRuns: 100 },
    )
  })

  it('does not treat a near-miss roleCode as ADMIN: only explicitly granted pairs pass', () => {
    fc.assert(
      fc.property(
        adminNearMissArb,
        codeArb,
        codeArb,
        (roleCode, resource, operation) => {
          // Grant exactly this one pair to the near-miss role.
          const [grantedPair, ungrantedPair] = evaluateWithUser(
            makeUser(roleCode, [{ resource, operations: [operation] }]),
            [
              [resource, operation],
              [`${resource}X`, `${operation}X`],
            ],
          )

          // The granted pair passes on its own matrix membership (not a bypass);
          // a different, ungranted pair still denies — proving no ADMIN bypass.
          expect(grantedPair).toBe(true)
          expect(ungrantedPair).toBe(false)
        },
      ),
      { numRuns: 100 },
    )
  })

  it('grants the exact literal ADMIN as the boundary sibling of the near-miss cases', () => {
    fc.assert(
      fc.property(codeArb, codeArb, (resource, operation) => {
        const [granted] = evaluateWithUser(makeUser(ADMIN_ROLE_CODE, []), [
          [resource, operation],
        ])

        expect(granted).toBe(true)
      }),
      { numRuns: 100 },
    )
  })
})
