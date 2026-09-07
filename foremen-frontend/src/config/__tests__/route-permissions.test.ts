import { describe, it, expect } from 'vitest'

import { requirementForPath } from '../route-permissions'

/**
 * Route_Requirement_Map consistency for the FOR-04-15 Catalog and Dictionaries
 * paths.
 *
 * After the two new sections are added to `NAV_CONFIG`, each new `path` must
 * resolve — via `requirementForPath` — to its item's `{ resource, READ }`
 * requirement, so the deep-link route guard and the menu agree (Req 6.1). The
 * interim `extra[...]` entries the FOR-04-02..14 specs added carry the identical
 * requirement, so the merged map is unambiguous.
 *
 * Validates: Requirements 6.1, 6.2
 */

/** Each new FOR-04-15 path mapped to the resource its READ requirement targets. */
const EXPECTED_RESOURCE_BY_PATH: Record<string, string> = {
  // Catalog section
  '/catalog/works': 'WORK_CATALOG',
  '/catalog/prices': 'WORK_PRICES',
  // Dictionaries section
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

const NEW_PATHS = Object.keys(EXPECTED_RESOURCE_BY_PATH)

describe('route-permissions: FOR-04-15 Catalog and Dictionaries paths (Req 6.1, 6.2)', () => {
  it.each(NEW_PATHS)('requirementForPath("%s") resolves to its { resource, READ }', (path) => {
    const requirement = requirementForPath(path)
    expect(requirement, `Expected a requirement for ${path}`).toBeDefined()
    expect(requirement).toEqual({
      resource: EXPECTED_RESOURCE_BY_PATH[path],
      operation: 'READ',
    })
  })

  it('every new path resolves to a READ operation (deep-link guard agrees with the menu)', () => {
    NEW_PATHS.forEach((path) => {
      expect(requirementForPath(path)?.operation).toBe('READ')
    })
  })

  it('the Projects and Rooms paths keep their {PROJECTS/ROOMS, READ} requirement', () => {
    expect(requirementForPath('/projects')).toEqual({ resource: 'PROJECTS', operation: 'READ' })
    expect(requirementForPath('/rooms')).toEqual({ resource: 'ROOMS', operation: 'READ' })
  })
})
