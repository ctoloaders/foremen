import { describe, it, expect } from 'vitest'

import {
  NAV_CONFIG,
  isNavItemVisible,
  type NavItemConfig,
  type NavSectionConfig,
} from '../navigation'

/**
 * A permissive predicate used to enumerate every bottom-nav item regardless of
 * its `requiredPermission` — i.e. what an ADMIN (bypass) would see.
 */
const grantAll = (): boolean => true

/**
 * A deny-everything predicate — an authenticated user with no grants at all.
 */
const grantNone = (): boolean => false

/**
 * A limited-role predicate: grants only the given `(resource, operation)` keys.
 */
function grantOnly(...keys: string[]): (r: string, o: string) => boolean {
  const set = new Set(keys)
  return (resource: string, operation: string) => set.has(`${resource}:${operation}`)
}

describe('NAV_CONFIG', () => {
  const allItems: NavItemConfig[] = NAV_CONFIG.flatMap((section) => section.items)

  it('each section has an items array', () => {
    NAV_CONFIG.forEach((section: NavSectionConfig) => {
      expect(Array.isArray(section.items)).toBe(true)
      expect(section.items.length).toBeGreaterThan(0)
    })
  })

  it('each item has a path that starts with /', () => {
    allItems.forEach((item) => {
      expect(item.path).toBeDefined()
      expect(item.path.startsWith('/')).toBe(true)
    })
  })

  it('each item has a non-empty labelKey', () => {
    allItems.forEach((item) => {
      expect(item.labelKey).toBeDefined()
      expect(item.labelKey.length).toBeGreaterThan(0)
    })
  })

  it('each item has a non-empty icon string', () => {
    allItems.forEach((item) => {
      expect(item.icon).toBeDefined()
      expect(typeof item.icon).toBe('string')
      expect(item.icon.length).toBeGreaterThan(0)
    })
  })

  it('each item has a boolean bottomNav flag', () => {
    allItems.forEach((item) => {
      expect(typeof item.bottomNav).toBe('boolean')
    })
  })

  it('all paths are unique', () => {
    const paths = allItems.map((item) => item.path)
    const uniquePaths = new Set(paths)
    expect(uniquePaths.size).toBe(paths.length)
  })
})

describe('NAV_CONFIG permission-filtered bottom-nav visibility (FOR-03-07)', () => {
  const bottomNavItems = NAV_CONFIG.flatMap((section) => section.items).filter(
    (item) => item.bottomNav
  )

  /** The bottom-nav items visible for a given predicate. */
  const visibleBottomNav = (has: (r: string, o: string) => boolean) =>
    bottomNavItems.filter((item) => isNavItemVisible(item, has))

  it('ADMIN (grant-all) sees every bottomNav item', () => {
    // The full set of bottomNav items — no fixed count assumption, just "all".
    expect(visibleBottomNav(grantAll)).toEqual(bottomNavItems)
    // There is at least one bottomNav item to make the comparison meaningful.
    expect(bottomNavItems.length).toBeGreaterThan(0)
  })

  it('a limited role sees a strict subset of the bottomNav items', () => {
    // A role granted only USERS:READ sees the unrestricted items plus /users,
    // but none of the other permission-gated bottom-nav items.
    const has = grantOnly('USERS:READ')
    const visible = visibleBottomNav(has)

    // It is a subset...
    expect(visible.length).toBeLessThan(bottomNavItems.length)
    visible.forEach((item) => expect(bottomNavItems).toContain(item))

    // ...it includes the unrestricted dashboard and the granted /users item...
    const visiblePaths = visible.map((i) => i.path)
    expect(visiblePaths).toContain('/')
    expect(visiblePaths).toContain('/users')

    // ...and excludes a permission-gated item the role was not granted.
    expect(visiblePaths).not.toContain('/projects')
  })

  it('an authenticated role with no grants still sees the unrestricted items only', () => {
    const visible = visibleBottomNav(grantNone)
    // Only items without a requiredPermission survive (e.g. the dashboard).
    visible.forEach((item) => expect(item.requiredPermission).toBeUndefined())
    // Every unrestricted bottomNav item is present.
    const unrestricted = bottomNavItems.filter((i) => i.requiredPermission == null)
    expect(visible).toEqual(unrestricted)
  })

  it('isNavItemVisible: unrestricted item is always visible; restricted item follows the predicate', () => {
    const unrestricted: NavItemConfig = {
      path: '/',
      labelKey: 'nav.dashboard',
      icon: 'layout-dashboard',
      bottomNav: true,
    }
    const restricted: NavItemConfig = {
      path: '/users',
      labelKey: 'nav.users',
      icon: 'users',
      bottomNav: true,
      requiredPermission: { resource: 'USERS', operation: 'READ' },
    }

    expect(isNavItemVisible(unrestricted, grantNone)).toBe(true)
    expect(isNavItemVisible(restricted, grantNone)).toBe(false)
    expect(isNavItemVisible(restricted, grantOnly('USERS:READ'))).toBe(true)
    expect(isNavItemVisible(restricted, grantAll)).toBe(true)
  })
})
