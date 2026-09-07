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

describe('NAV_CONFIG Catalog and Dictionaries sections (FOR-04-15)', () => {
  /** Locate a section by its titleKey. */
  const sectionByTitle = (titleKey: string): NavSectionConfig | undefined =>
    NAV_CONFIG.find((section) => section.titleKey === titleKey)

  const CATALOG_PATHS = ['/catalog/works', '/catalog/prices']

  const DICTIONARY_PATHS = [
    '/measurement-units',
    '/currencies',
    '/vat-rates',
    '/room-types',
    '/work-categories',
    '/delivery-categories',
    '/delivery-statuses',
    '/material-categories',
    '/offer-packages',
  ]

  // The eleven new paths introduced by FOR-04-15.
  const NEW_PATHS = [...CATALOG_PATHS, ...DICTIONARY_PATHS]

  /** All items belonging to the two new sections. */
  const newItems = (): NavItemConfig[] => [
    ...(sectionByTitle('nav.sections.catalog')?.items ?? []),
    ...(sectionByTitle('nav.sections.dictionaries')?.items ?? []),
  ]

  it('the Catalog section exists after the first unnamed section and before warehouse (Req 1.1)', () => {
    const firstUnnamedIndex = NAV_CONFIG.findIndex((s) => s.titleKey === null)
    const catalogIndex = NAV_CONFIG.findIndex((s) => s.titleKey === 'nav.sections.catalog')
    const warehouseIndex = NAV_CONFIG.findIndex((s) => s.titleKey === 'nav.sections.warehouse')

    expect(catalogIndex).toBeGreaterThan(-1)
    expect(catalogIndex).toBeGreaterThan(firstUnnamedIndex)
    expect(catalogIndex).toBeLessThan(warehouseIndex)
  })

  it('the Dictionaries section exists after warehouse and before system (Req 2.1)', () => {
    const warehouseIndex = NAV_CONFIG.findIndex((s) => s.titleKey === 'nav.sections.warehouse')
    const dictionariesIndex = NAV_CONFIG.findIndex(
      (s) => s.titleKey === 'nav.sections.dictionaries'
    )
    const systemIndex = NAV_CONFIG.findIndex((s) => s.titleKey === 'nav.sections.system')

    expect(dictionariesIndex).toBeGreaterThan(-1)
    expect(dictionariesIndex).toBeGreaterThan(warehouseIndex)
    expect(dictionariesIndex).toBeLessThan(systemIndex)
  })

  it('the Catalog and Dictionaries sections appear in the required relative order (Catalog before Dictionaries)', () => {
    const catalogIndex = NAV_CONFIG.findIndex((s) => s.titleKey === 'nav.sections.catalog')
    const dictionariesIndex = NAV_CONFIG.findIndex(
      (s) => s.titleKey === 'nav.sections.dictionaries'
    )
    expect(catalogIndex).toBeLessThan(dictionariesIndex)
  })

  it('the Catalog section lists Work Catalog then Work Prices with the expected paths (Req 1.2, 1.4)', () => {
    const catalog = sectionByTitle('nav.sections.catalog')
    expect(catalog).toBeDefined()
    expect(catalog!.items.map((i) => i.path)).toEqual(CATALOG_PATHS)
  })

  it('the Dictionaries section lists the nine reference paths in the required order (Req 2.2, 2.4)', () => {
    const dictionaries = sectionByTitle('nav.sections.dictionaries')
    expect(dictionaries).toBeDefined()
    expect(dictionaries!.items.map((i) => i.path)).toEqual(DICTIONARY_PATHS)
  })

  it('the eleven new paths match the expected set exactly', () => {
    const actualNewPaths = newItems().map((i) => i.path)
    expect(actualNewPaths).toHaveLength(11)
    expect(new Set(actualNewPaths)).toEqual(new Set(NEW_PATHS))
  })

  it('every new item has a non-empty icon (Req 1.3, 2.2)', () => {
    newItems().forEach((item) => {
      expect(typeof item.icon).toBe('string')
      expect(item.icon.length).toBeGreaterThan(0)
    })
  })

  it('every new item sets bottomNav: false (Req 1.3, 2.2)', () => {
    newItems().forEach((item) => {
      expect(item.bottomNav).toBe(false)
    })
  })

  it('every new item has a path starting with / (Req 7.1)', () => {
    newItems().forEach((item) => {
      expect(item.path.startsWith('/')).toBe(true)
    })
  })

  it('every new item requiredPermission.operation is READ (Req 2.3, 7.1)', () => {
    newItems().forEach((item) => {
      expect(item.requiredPermission).toBeDefined()
      expect(item.requiredPermission!.operation).toBe('READ')
    })
  })

  it('each new item requiredPermission resource matches the design mapping', () => {
    const expectedResourceByPath: Record<string, string> = {
      '/catalog/works': 'WORK_CATALOG',
      '/catalog/prices': 'WORK_PRICES',
      '/measurement-units': 'MEASUREMENT_UNITS',
      '/currencies': 'CURRENCIES',
      '/vat-rates': 'VAT_RATES',
      '/room-types': 'ROOM_TYPES',
      '/work-categories': 'WORK_CATEGORIES',
      '/delivery-categories': 'DELIVERY_CATEGORIES',
      '/delivery-statuses': 'DELIVERY_STATUSES',
      '/material-categories': 'MATERIAL_CATEGORIES',
      '/offer-packages': 'OFFER_PACKAGES',
    }
    newItems().forEach((item) => {
      expect(item.requiredPermission!.resource).toBe(expectedResourceByPath[item.path])
    })
  })
})

describe('NAV_CONFIG Projects/Rooms binding (FOR-04-15, Req 4.1-4.3)', () => {
  const firstSection = NAV_CONFIG.find((s) => s.titleKey === null)

  it('the first unnamed section exists and is the source of Projects/Rooms', () => {
    expect(firstSection).toBeDefined()
  })

  it('the Projects item stays in the first section with {PROJECTS, READ} (Req 4.1, 4.3)', () => {
    const projects = firstSection!.items.find((i) => i.path === '/projects')
    expect(projects).toBeDefined()
    expect(projects!.requiredPermission).toEqual({ resource: 'PROJECTS', operation: 'READ' })
  })

  it('the Rooms item stays in the first section with {ROOMS, READ} (Req 4.2, 4.3)', () => {
    const rooms = firstSection!.items.find((i) => i.path === '/rooms')
    expect(rooms).toBeDefined()
    expect(rooms!.requiredPermission).toEqual({ resource: 'ROOMS', operation: 'READ' })
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
