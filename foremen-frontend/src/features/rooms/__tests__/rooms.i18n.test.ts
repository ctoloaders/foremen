import { describe, expect, it } from 'vitest'
import plJson from '@/locales/pl.json'
import ruJson from '@/locales/ru.json'

/**
 * i18n parity for the `rooms.*` catalog subtree (FOR-04-14).
 *
 * Verifies that the Polish and Russian catalogs expose an identical set of
 * `rooms.*` keys (including the metric labels with their units, the source-flag
 * labels "Calculated"/"Manual", and the opening type labels DOOR/WINDOW) and
 * that every leaf value is a non-empty string so no raw i18n key is ever
 * rendered to the user.
 *
 * Validates: Requirements 8.12, 9.5
 */

type LocaleCatalog = Record<string, unknown>

/**
 * Flatten a nested object into dot-notation leaf paths mapped to their values.
 * Only leaf (non-object) values are recorded.
 */
function flatten(obj: unknown, prefix = ''): Record<string, unknown> {
  if (obj === null || typeof obj !== 'object') {
    return prefix ? { [prefix]: obj } : {}
  }
  const out: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(obj as Record<string, unknown>)) {
    const path = prefix ? `${prefix}.${key}` : key
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      Object.assign(out, flatten(value, path))
    } else {
      out[path] = value
    }
  }
  return out
}

const pl = plJson as LocaleCatalog
const ru = ruJson as LocaleCatalog

const plRooms = flatten((pl as Record<string, unknown>).rooms ?? {}, 'rooms')
const ruRooms = flatten((ru as Record<string, unknown>).rooms ?? {}, 'rooms')

const plKeys = Object.keys(plRooms).sort()
const ruKeys = Object.keys(ruRooms).sort()

describe('rooms.* i18n parity between pl.json and ru.json', () => {
  it('has a non-empty rooms.* subtree in both locales', () => {
    expect(plKeys.length).toBeGreaterThan(0)
    expect(ruKeys.length).toBeGreaterThan(0)
  })

  it('exposes an identical set of rooms.* keys in both locales', () => {
    const onlyInPl = plKeys.filter((k) => !(k in ruRooms))
    const onlyInRu = ruKeys.filter((k) => !(k in plRooms))

    expect(onlyInPl, `Keys present only in pl.json: ${onlyInPl.join(', ')}`).toEqual([])
    expect(onlyInRu, `Keys present only in ru.json: ${onlyInRu.join(', ')}`).toEqual([])
    expect(plKeys).toEqual(ruKeys)
  })

  it('includes the metric labels (with units) in both locales', () => {
    const metricKeys = [
      'rooms.table.floorArea',
      'rooms.table.wallArea',
      'rooms.table.perimeter',
      'rooms.table.ceilingHeight',
      'rooms.form.floorArea',
      'rooms.form.wallArea',
      'rooms.form.perimeter',
      'rooms.form.doorArea',
      'rooms.form.windowArea',
      'rooms.form.ceilingHeight',
      'rooms.form.internalCorners',
    ]
    for (const key of metricKeys) {
      expect(plRooms[key], `Missing ${key} in pl.json`).toBeTypeOf('string')
      expect(ruRooms[key], `Missing ${key} in ru.json`).toBeTypeOf('string')
    }
  })

  it('includes the source-flag labels in both locales', () => {
    const sourceKeys = ['rooms.source.calculated', 'rooms.source.manual']
    for (const key of sourceKeys) {
      expect(plRooms[key], `Missing ${key} in pl.json`).toBeTypeOf('string')
      expect(ruRooms[key], `Missing ${key} in ru.json`).toBeTypeOf('string')
    }
  })

  it('includes the opening type labels in both locales', () => {
    const openingTypeKeys = ['rooms.openingType.DOOR', 'rooms.openingType.WINDOW']
    for (const key of openingTypeKeys) {
      expect(plRooms[key], `Missing ${key} in pl.json`).toBeTypeOf('string')
      expect(ruRooms[key], `Missing ${key} in ru.json`).toBeTypeOf('string')
    }
  })

  it('includes the localized empty state in both locales', () => {
    expect(plRooms['rooms.list.empty'], 'Missing rooms.list.empty in pl.json').toBeTypeOf('string')
    expect(ruRooms['rooms.list.empty'], 'Missing rooms.list.empty in ru.json').toBeTypeOf('string')
  })

  it.each(plKeys)('pl.json value for "%s" is a non-empty string', (key) => {
    const value = plRooms[key]
    expect(typeof value, `Value for "${key}" in pl.json is not a string`).toBe('string')
    expect((value as string).trim().length, `Empty value for "${key}" in pl.json`).toBeGreaterThan(0)
  })

  it.each(ruKeys)('ru.json value for "%s" is a non-empty string', (key) => {
    const value = ruRooms[key]
    expect(typeof value, `Value for "${key}" in ru.json is not a string`).toBe('string')
    expect((value as string).trim().length, `Empty value for "${key}" in ru.json`).toBeGreaterThan(0)
  })
})
