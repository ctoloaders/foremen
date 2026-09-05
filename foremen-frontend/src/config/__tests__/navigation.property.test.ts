// Feature: FOR-03-07-menu-visibility, Property 5: A nav item is visible iff unrestricted or granted
import { describe, it, expect } from 'vitest'
import * as fc from 'fast-check'

import {
  NAV_CONFIG,
  isNavItemVisible,
  type NavItemConfig,
  type NavSectionConfig,
} from '../navigation'

// --- Helpers -----------------------------------------------------------------

/**
 * Independent oracle for the visibility rule, mirroring the acceptance criteria
 * (Req 3.1/3.2/3.3): an item is visible iff it has no `requiredPermission`, or
 * the predicate grants the item's requirement. Deliberately re-derived here
 * rather than reusing `isNavItemVisible` so the property compares the
 * implementation against an independent statement of the rule.
 */
function oracleVisible(
  item: NavItemConfig,
  hasPermission: (r: string, o: string) => boolean,
): boolean {
  if (item.requiredPermission == null) return true
  return hasPermission(item.requiredPermission.resource, item.requiredPermission.operation)
}

/**
 * A section renders its header iff at least one of its items is visible (Req 3.4).
 */
function oracleSectionRenders(
  section: NavSectionConfig,
  hasPermission: (r: string, o: string) => boolean,
): boolean {
  return section.items.some((item) => oracleVisible(item, hasPermission))
}

/**
 * Builds a `hasPermission` predicate from a set of granted `RESOURCE:OPERATION`
 * keys, mirroring the Permission_Hook's exact-match set-membership semantics.
 */
function grantOnly(keys: Set<string>): (r: string, o: string) => boolean {
  return (resource: string, operation: string) => keys.has(`${resource}:${operation}`)
}

// --- Arbitraries -------------------------------------------------------------

/**
 * Every `RESOURCE:OPERATION` requirement key that appears anywhere in
 * NAV_CONFIG. The generator draws an arbitrary subset of these so the property
 * exercises every combination of granted/denied nav requirements, including
 * the all-granted (ADMIN-equivalent) and none-granted (bare authenticated)
 * extremes.
 */
const REQUIREMENT_KEYS: string[] = Array.from(
  new Set(
    NAV_CONFIG.flatMap((section) => section.items)
      .map((item) => item.requiredPermission)
      .filter((req): req is NonNullable<typeof req> => req != null)
      .map((req) => `${req.resource}:${req.operation}`),
  ),
)

/** An arbitrary subset of the real requirement keys, as a Set. */
const grantSetArb: fc.Arbitrary<Set<string>> = fc
  .subarray(REQUIREMENT_KEYS)
  .map((keys) => new Set(keys))

// --- Property 5 --------------------------------------------------------------

/**
 * Feature: FOR-03-07-menu-visibility, Property 5: A nav item is visible iff
 * unrestricted or granted.
 *
 * For any NAV_CONFIG item and any Current_User (modelled here as an arbitrary
 * grant set feeding the exact-match predicate), the navigation filter includes
 * the item if and only if the item has no `requiredPermission` OR
 * `hasPermission` grants the item's requirement; and a section renders its
 * header if and only if at least one of its items is visible.
 *
 * **Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5**
 */
describe('Feature: FOR-03-07-menu-visibility, Property 5: A nav item is visible iff unrestricted or granted', () => {
  const allItems = NAV_CONFIG.flatMap((section) => section.items)

  it('isNavItemVisible matches the unrestricted-or-granted oracle for every item and grant set', () => {
    fc.assert(
      fc.property(grantSetArb, (keys) => {
        const has = grantOnly(keys)
        for (const item of allItems) {
          expect(isNavItemVisible(item, has)).toBe(oracleVisible(item, has))
        }
      }),
      { numRuns: 100 },
    )
  })

  it('an unrestricted item is always visible, and a restricted item is visible iff its requirement is granted', () => {
    fc.assert(
      fc.property(grantSetArb, (keys) => {
        const has = grantOnly(keys)
        for (const item of allItems) {
          const visible = isNavItemVisible(item, has)
          if (item.requiredPermission == null) {
            // Req 3.2: no requirement → always visible, regardless of grants.
            expect(visible).toBe(true)
          } else {
            const { resource, operation } = item.requiredPermission
            // Req 3.1/3.3: restricted → visible iff the exact requirement is granted.
            expect(visible).toBe(has(resource, operation))
          }
        }
      }),
      { numRuns: 100 },
    )
  })

  it('a section renders its header iff at least one of its items is visible (Req 3.4)', () => {
    fc.assert(
      fc.property(grantSetArb, (keys) => {
        const has = grantOnly(keys)
        for (const section of NAV_CONFIG) {
          const visibleItems = section.items.filter((item) => isNavItemVisible(item, has))
          const headerRenders = visibleItems.length > 0
          expect(headerRenders).toBe(oracleSectionRenders(section, has))
        }
      }),
      { numRuns: 100 },
    )
  })

  it('ADMIN (grant-all) shows every item and every section (Req 3.5)', () => {
    const grantAll = () => true
    for (const item of allItems) {
      expect(isNavItemVisible(item, grantAll)).toBe(true)
    }
    for (const section of NAV_CONFIG) {
      expect(oracleSectionRenders(section, grantAll)).toBe(true)
    }
  })

  it('a bare authenticated user (no grants) sees exactly the unrestricted items, and only their sections render', () => {
    const grantNone = () => false
    for (const item of allItems) {
      expect(isNavItemVisible(item, grantNone)).toBe(item.requiredPermission == null)
    }
    for (const section of NAV_CONFIG) {
      const hasUnrestricted = section.items.some((item) => item.requiredPermission == null)
      expect(oracleSectionRenders(section, grantNone)).toBe(hasUnrestricted)
    }
  })
})
