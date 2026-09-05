// Feature: FOR-03-07-menu-visibility, Property 8: Go_Back target avoids the denied route and the empty case
import { describe, it, expect, beforeEach, vi } from 'vitest'
import * as fc from 'fast-check'

import {
  recordAllowedLocation,
  recordDeniedLocation,
  getLastAllowedLocation,
  getDeniedLocation,
  resolveGoBackTarget,
} from '@/lib/last-allowed-location'

// The tracking module keeps its state in module-level variables. Each test /
// property run must therefore start from a clean slate. There is no exported
// reset, but recording an allowed and a denied value fully determines the two
// tracked slots, and the property runs always begin by (re)writing them.
// For the unit tests below we additionally normalize the state explicitly.

const FORBIDDEN_PATH = '/403'
const HOME_PATH = '/'

/**
 * Resets the module's two slots to a known baseline. `denied` has no dedicated
 * clear, but writing a sentinel that no test path equals is sufficient; the
 * unit tests that care about `denied` set it explicitly first.
 */
function resetTracking(): void {
  // A granted route the module accepts (non-/403) then a denied sentinel.
  recordAllowedLocation('/__reset__')
  recordDeniedLocation('/__reset_denied__')
}

// --- Arbitraries -------------------------------------------------------------

/** A path segment free of the `?`/`#`/`/` delimiters. */
const segmentArb = fc
  .string({ minLength: 1, maxLength: 12 })
  .map((s) => s.replace(/[/?#]/g, ''))
  .filter((s) => s.length > 0)

/**
 * An arbitrary non-`/403` route path-plus-query (leading path segments plus an
 * optional query string). Filtered so the path portion is never `/403`, since
 * the module refuses to record that as a Last_Allowed_Location.
 */
const routeArb: fc.Arbitrary<string> = fc
  .tuple(
    fc.array(segmentArb, { minLength: 1, maxLength: 4 }),
    fc.option(fc.string({ maxLength: 20 }).map((q) => q.replace(/#/g, '')), { nil: '' }),
  )
  .map(([segments, query]) => {
    const path = '/' + segments.join('/')
    return query ? `${path}?${query}` : path
  })
  .filter((full) => (full.split('?')[0] ?? full) !== FORBIDDEN_PATH)

// --- Property 8 --------------------------------------------------------------

/**
 * Feature: FOR-03-07-menu-visibility, Property 8: Go_Back target avoids the
 * denied route and the empty case.
 *
 * For any pair of tracked values (Last_Allowed_Location, Denied_Location),
 * `resolveGoBackTarget()` returns `/` when the Last_Allowed_Location is null or
 * is string-equal (full path+query) to the Denied_Location, and otherwise
 * returns exactly the Last_Allowed_Location.
 *
 * **Validates: Requirements 5.4, 5.5, 5.6, 5.8**
 */
describe('Feature: FOR-03-07-menu-visibility, Property 8: Go_Back target avoids the denied route and the empty case', () => {
  it('returns exactly the last-allowed location when it differs from the denied location', () => {
    fc.assert(
      fc.property(routeArb, routeArb, (allowed, denied) => {
        // Only exercise the "different" branch here; equality is covered below.
        fc.pre(allowed !== denied)

        recordDeniedLocation(denied)
        recordAllowedLocation(allowed)

        // Full path+query is preserved verbatim (5.8) and returned (5.4).
        expect(getLastAllowedLocation()).toBe(allowed)
        expect(getDeniedLocation()).toBe(denied)
        expect(resolveGoBackTarget()).toBe(allowed)
      }),
      { numRuns: 100 },
    )
  })

  it('returns `/` when the last-allowed location string-equals the denied location (5.6, 5.8)', () => {
    fc.assert(
      fc.property(routeArb, (location) => {
        recordDeniedLocation(location)
        recordAllowedLocation(location)

        // Last_Allowed_Location === Denied_Location → home fallback (avoid loop).
        expect(resolveGoBackTarget()).toBe(HOME_PATH)
      }),
      { numRuns: 100 },
    )
  })

  it('locations differing only by query string are distinct, so Go_Back returns the allowed one (5.8)', () => {
    fc.assert(
      fc.property(
        // A shared path with two different query strings.
        segmentArb,
        fc.string({ maxLength: 10 }).map((q) => q.replace(/[#?]/g, '')),
        fc.string({ maxLength: 10 }).map((q) => q.replace(/[#?]/g, '')),
        (segment, qa, qb) => {
          fc.pre(qa !== qb)
          const path = `/${segment}`
          const allowed = `${path}?${qa}`
          const denied = `${path}?${qb}`

          recordDeniedLocation(denied)
          recordAllowedLocation(allowed)

          // Same path but different query → not equal → returns the allowed one.
          expect(resolveGoBackTarget()).toBe(allowed)
        },
      ),
      { numRuns: 100 },
    )
  })
})

// --- Example / unit tests ----------------------------------------------------

describe('last-allowed-location module (example cases)', () => {
  beforeEach(() => {
    resetTracking()
  })

  it('recordAllowedLocation stores the full path+query and getLastAllowedLocation returns it', () => {
    recordAllowedLocation('/users?page=2')
    expect(getLastAllowedLocation()).toBe('/users?page=2')
  })

  it('recordAllowedLocation ignores the /403 path (Req 4.4) — the prior allowed value is kept', () => {
    recordAllowedLocation('/users')
    recordAllowedLocation('/403')
    expect(getLastAllowedLocation()).toBe('/users')
  })

  it('recordAllowedLocation ignores /403 even with a query string', () => {
    recordAllowedLocation('/roles')
    recordAllowedLocation('/403?from=/users')
    expect(getLastAllowedLocation()).toBe('/roles')
  })

  it('recordDeniedLocation stores the denied route and getDeniedLocation returns it (Req 4.8)', () => {
    recordDeniedLocation('/audit?tab=diff')
    expect(getDeniedLocation()).toBe('/audit?tab=diff')
  })

  it('resolveGoBackTarget returns the last-allowed location in the normal case (Req 5.4)', () => {
    recordDeniedLocation('/audit')
    recordAllowedLocation('/users')
    expect(resolveGoBackTarget()).toBe('/users')
  })

  it('resolveGoBackTarget returns `/` when the last-allowed equals the denied location (Req 5.6)', () => {
    recordDeniedLocation('/users')
    recordAllowedLocation('/users')
    expect(resolveGoBackTarget()).toBe('/')
  })

  it('resolveGoBackTarget returns `/` when there is no last-allowed location (fresh module, Req 5.5)', async () => {
    // The module keeps `lastAllowed` in a module-level variable with no reset,
    // so a fresh import gives the pristine null state (as on a cold deep-link
    // into a forbidden route). getLastAllowedLocation is null → home fallback.
    vi.resetModules()
    const fresh = await import('@/lib/last-allowed-location')

    expect(fresh.getLastAllowedLocation()).toBeNull()
    expect(fresh.resolveGoBackTarget()).toBe('/')
  })
})
