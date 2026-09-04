// Feature: FOR-03-06-frontend-auth, Property 7: Me_Cache always reflects the last 200 and 304 is neutral
import { describe, it, expect, beforeEach, vi } from 'vitest'
import * as fc from 'fast-check'
import type { CurrentUser } from '@/lib/me-cache'

// --- Setup ---

beforeEach(() => {
  // Me_Cache holds module-level state; reset the module registry so each
  // property run imports a freshly-initialised (empty) cache.
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

/** An etag string or null (server may omit the ETag). */
const etagArb: fc.Arbitrary<string | null> = fc.option(
  fc.string({ minLength: 1, maxLength: 32 }).map((s) => `"${s}"`),
  { nil: null },
)

/**
 * A step in the sequence: either a `200` (setMeCache with a user + etag) or a
 * `304` no-op (revalidation that leaves the cache untouched).
 */
type Step =
  | { kind: '200'; user: CurrentUser; etag: string | null }
  | { kind: '304' }

const stepArb: fc.Arbitrary<Step> = fc.oneof(
  fc
    .record({ user: currentUserArb, etag: etagArb })
    .map((r) => ({ kind: '200' as const, user: r.user, etag: r.etag })),
  fc.constant({ kind: '304' as const }),
)

/**
 * Feature: FOR-03-06-frontend-auth, Property 7: Me_Cache always reflects the
 * last 200 and 304 is neutral.
 *
 * Given any interleaving of `setMeCache(user, etag)` calls (200) and `304`
 * no-ops, the cache always reflects the most recent `setMeCache`; a `304`
 * changes nothing. Additionally, `invalidateMeCache` clears only the etag and
 * leaves `currentUser` in place.
 *
 * **Validates: Requirements 13.1, 13.3, 13.4**
 */
describe('Feature: FOR-03-06-frontend-auth, Property 7: Me_Cache always reflects the last 200 and 304 is neutral', () => {
  it('cache always reflects the most recent setMeCache; 304 is a no-op', async () => {
    const { getMeCache, setMeCache } = await import('@/lib/me-cache')

    await fc.assert(
      fc.asyncProperty(
        fc.array(stepArb, { minLength: 1, maxLength: 30 }),
        async (steps) => {
          // The cache is module-level and persists across fast-check runs
          // within this test, so seed the expected state from the current
          // snapshot rather than assuming an empty cache. A leading '304' must
          // then correctly reflect whatever the previous run left behind.
          const seed = getMeCache()
          let expectedUser: CurrentUser | null = seed.currentUser
          let expectedEtag: string | null = seed.etag

          for (const step of steps) {
            if (step.kind === '200') {
              setMeCache(step.user, step.etag)
              expectedUser = step.user
              expectedEtag = step.etag
            }
            // '304': no-op, the cache must be untouched.

            const snap = getMeCache()
            expect(snap.currentUser).toEqual(expectedUser)
            expect(snap.etag).toBe(expectedEtag)
          }
        },
      ),
      { numRuns: 100 },
    )
  })

  it('invalidateMeCache clears only the etag, leaving currentUser', async () => {
    const { getMeCache, setMeCache, invalidateMeCache } = await import(
      '@/lib/me-cache'
    )

    await fc.assert(
      fc.asyncProperty(
        currentUserArb,
        fc.string({ minLength: 1, maxLength: 32 }).map((s) => `"${s}"`),
        async (user, etag) => {
          setMeCache(user, etag)

          const before = getMeCache()
          expect(before.currentUser).toEqual(user)
          expect(before.etag).toBe(etag)

          invalidateMeCache()

          const after = getMeCache()
          // currentUser survives; only the revalidation token is cleared.
          expect(after.currentUser).toEqual(user)
          expect(after.etag).toBeNull()
        },
      ),
      { numRuns: 100 },
    )
  })
})
