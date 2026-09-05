// Feature: FOR-03-07-menu-visibility, Property 1: hasPermission equals matrix membership for non-ADMIN (deny by default)
import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import * as fc from 'fast-check'

import type { CurrentUser } from '@/stores/auth-store'
import { useAuthStore } from '@/stores/auth-store'
import { usePermission } from '@/hooks/usePermission'

// --- Helpers -----------------------------------------------------------------

/**
 * Seeds the Auth_Store with a Current_User, renders the hook, and invokes
 * `fn` with the resulting `hasPermission` predicate. The render is unmounted
 * before returning so no stale hook instance receives the next run's store
 * update (which would otherwise trigger act(...) warnings across property
 * iterations). The hook subscribes to `s.user`, so writing the user before
 * rendering is sufficient to drive the predicate.
 */
function withPermission<T>(
  user: CurrentUser,
  fn: (hasPermission: (r: string, o: string) => boolean) => T,
): T {
  useAuthStore.setState({ user })
  const { result, unmount } = renderHook(() => usePermission())
  try {
    return fn(result.current.hasPermission)
  } finally {
    unmount()
  }
}

/**
 * The exact-match membership oracle: mirrors the acceptance criteria
 * independently of the hook's `Set`-based implementation. Returns true iff some
 * permissions entry has the exact resource whose operations include the exact
 * operation.
 */
function oracleGrants(
  permissions: CurrentUser['permissions'],
  resource: string,
  operation: string,
): boolean {
  return permissions.some(
    (entry) =>
      entry.resource === resource && entry.operations.includes(operation),
  )
}

// --- Arbitraries -------------------------------------------------------------

/** Uppercase-style resource/operation codes, as used by the backend matrix. */
const codeArb = fc.constantFrom(
  'USERS',
  'ROLES',
  'AUDIT',
  'RESOURCES',
  'OPERATIONS',
  'PROJECTS',
  'ROOMS',
  'CREATE',
  'READ',
  'UPDATE',
  'DELETE',
  // a couple of arbitrary codes to widen the space
  'X',
  'y',
)

const permissionsArb: fc.Arbitrary<CurrentUser['permissions']> = fc.array(
  fc.record({
    resource: codeArb,
    operations: fc.array(codeArb, { maxLength: 4 }),
  }),
  { maxLength: 5 },
)

/** A non-ADMIN role code (never the exact literal 'ADMIN'). */
const nonAdminRoleArb: fc.Arbitrary<string> = fc
  .constantFrom<string>('CLIENT', 'FOREMAN', 'MANAGER', 'admin', 'Admin', 'ADMIN ', ' ADMIN', 'ADMINX')
  .filter((code) => code !== 'ADMIN')

function nonAdminUserArb(): fc.Arbitrary<CurrentUser> {
  return fc.record({
    id: fc.integer({ min: 1, max: 1_000_000 }),
    name: fc.string({ minLength: 1, maxLength: 40 }),
    email: fc.emailAddress(),
    roleCode: nonAdminRoleArb,
    permissions: permissionsArb,
  })
}

// --- Setup -------------------------------------------------------------------

beforeEach(() => {
  // Reset to a clean, unauthenticated session so runs never leak into each
  // other (repeatable without manual state cleanup).
  useAuthStore.setState({ user: null })
})

/**
 * Feature: FOR-03-07-menu-visibility, Property 1: hasPermission equals matrix
 * membership for non-ADMIN (deny by default).
 *
 * For any non-ADMIN Current_User with any generated permissions array, and for
 * any (resource, operation) pair, hasPermission returns true if and only if
 * some permissions entry has that exact resource and its operations includes
 * that exact operation; a missing resource, or a present resource without the
 * operation, yields false. Matching is by exact string equality (1.5).
 *
 * **Validates: Requirements 1.3, 1.5**
 */
describe('Feature: FOR-03-07-menu-visibility, Property 1: hasPermission equals matrix membership for non-ADMIN (deny by default)', () => {
  it('non-ADMIN: hasPermission(r, o) === exact-match membership for arbitrary queried pairs', () => {
    fc.assert(
      fc.property(
        nonAdminUserArb(),
        codeArb, // queried resource
        codeArb, // queried operation
        (user, resource, operation) => {
          withPermission(user, (hasPermission) => {
            expect(hasPermission(resource, operation)).toBe(
              oracleGrants(user.permissions, resource, operation),
            )
          })
        },
      ),
      { numRuns: 100 },
    )
  })

  it('non-ADMIN: every granted pair returns true and every ungranted pair returns false', () => {
    fc.assert(
      fc.property(nonAdminUserArb(), (user) => {
        withPermission(user, (hasPermission) => {
          // Every (resource, operation) that appears in the permissions array
          // is granted (deny-by-default is only for what is absent).
          for (const entry of user.permissions) {
            for (const op of entry.operations) {
              expect(hasPermission(entry.resource, op)).toBe(true)
            }
          }

          // A pair whose resource is absent, or whose operation is not listed
          // under the resource, is denied — exact-match, so a case-differing
          // operation on a granted resource is NOT a grant (1.5).
          for (const entry of user.permissions) {
            // An operation code that is definitely not present under this
            // resource (lowercased sentinel not in the code set).
            const absentOp = '__absent_op__'
            expect(
              oracleGrants(user.permissions, entry.resource, absentOp),
            ).toBe(false)
            expect(hasPermission(entry.resource, absentOp)).toBe(false)
          }

          // A resource that is definitely absent denies every operation.
          const absentResource = '__absent_resource__'
          expect(hasPermission(absentResource, 'READ')).toBe(false)
          expect(hasPermission(absentResource, 'CREATE')).toBe(false)
        })
      }),
      { numRuns: 100 },
    )
  })

  it('non-ADMIN: exact-match only — a case-differing resource/operation is not a grant (1.5)', () => {
    fc.assert(
      fc.property(
        // A non-ADMIN user that is granted exactly USERS:READ, plus arbitrary
        // extra permissions, so we can probe a known-granted pair and its
        // case-differing near-misses.
        nonAdminRoleArb,
        permissionsArb,
        (roleCode, extra) => {
          const user: CurrentUser = {
            id: 1,
            name: 'n',
            email: 'n@example.com',
            roleCode,
            permissions: [{ resource: 'USERS', operations: ['READ'] }, ...extra],
          }
          withPermission(user, (hasPermission) => {
            // The exact granted pair is allowed.
            expect(hasPermission('USERS', 'READ')).toBe(true)

            // Case-differing near-misses are NOT grants (unless the extra
            // permissions happen to also grant them — defer to the oracle).
            expect(hasPermission('users', 'READ')).toBe(
              oracleGrants(user.permissions, 'users', 'READ'),
            )
            expect(hasPermission('USERS', 'read')).toBe(
              oracleGrants(user.permissions, 'USERS', 'read'),
            )
          })
        },
      ),
      { numRuns: 100 },
    )
  })
})
