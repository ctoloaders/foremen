import { describe, it, expect } from 'vitest'

import { NAV_CONFIG, type NavItemConfig, type NavSectionConfig } from '../navigation'

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

  it('has exactly 5 items with bottomNav: true', () => {
    const bottomNavItems = allItems.filter((item) => item.bottomNav)
    expect(bottomNavItems).toHaveLength(5)
  })

  it('all paths are unique', () => {
    const paths = allItems.map((item) => item.path)
    const uniquePaths = new Set(paths)
    expect(uniquePaths.size).toBe(paths.length)
  })
})
