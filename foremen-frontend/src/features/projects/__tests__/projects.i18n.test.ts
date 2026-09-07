import { describe, expect, it } from 'vitest'
import plJson from '@/locales/pl.json'
import ruJson from '@/locales/ru.json'

/**
 * i18n parity for the `projects.*` catalog subtree (FOR-04-13).
 *
 * Verifies that the Polish and Russian catalogs expose an identical set of
 * `projects.*` keys (including the five status labels, the Google address
 * component text, and the Client block text) and that every leaf value is a
 * non-empty string so no raw key is ever rendered to the user.
 *
 * Validates: Requirements 8.13, 9.6
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

const plProjects = flatten((pl as Record<string, unknown>).projects ?? {}, 'projects')
const ruProjects = flatten((ru as Record<string, unknown>).projects ?? {}, 'projects')

const plKeys = Object.keys(plProjects).sort()
const ruKeys = Object.keys(ruProjects).sort()

describe('projects.* i18n parity between pl.json and ru.json', () => {
  it('has a non-empty projects.* subtree in both locales', () => {
    expect(plKeys.length).toBeGreaterThan(0)
    expect(ruKeys.length).toBeGreaterThan(0)
  })

  it('exposes an identical set of projects.* keys in both locales', () => {
    const onlyInPl = plKeys.filter((k) => !(k in ruProjects))
    const onlyInRu = ruKeys.filter((k) => !(k in plProjects))

    expect(onlyInPl, `Keys present only in pl.json: ${onlyInPl.join(', ')}`).toEqual([])
    expect(onlyInRu, `Keys present only in ru.json: ${onlyInRu.join(', ')}`).toEqual([])
    expect(plKeys).toEqual(ruKeys)
  })

  it('includes the five status labels in both locales', () => {
    const statuses = ['DRAFT', 'ACTIVE', 'ON_HOLD', 'COMPLETED', 'CANCELLED']
    for (const status of statuses) {
      const key = `projects.status.${status}`
      expect(plProjects[key], `Missing ${key} in pl.json`).toBeTypeOf('string')
      expect(ruProjects[key], `Missing ${key} in ru.json`).toBeTypeOf('string')
    }
  })

  it('includes the Google address component text in both locales', () => {
    const addressKeys = [
      'projects.form.addressLabel',
      'projects.form.addressPlaceholder',
      'projects.form.addressSearching',
      'projects.form.addressEmpty',
      'projects.form.addressError',
    ]
    for (const key of addressKeys) {
      expect(plProjects[key], `Missing ${key} in pl.json`).toBeTypeOf('string')
      expect(ruProjects[key], `Missing ${key} in ru.json`).toBeTypeOf('string')
    }
  })

  it('includes the Client block text in both locales', () => {
    const clientKeys = [
      'projects.form.client.title',
      'projects.form.client.description',
      'projects.form.client.existingTab',
      'projects.form.client.newTab',
      'projects.form.client.name',
      'projects.form.client.email',
      'projects.form.client.phone',
    ]
    for (const key of clientKeys) {
      expect(plProjects[key], `Missing ${key} in pl.json`).toBeTypeOf('string')
      expect(ruProjects[key], `Missing ${key} in ru.json`).toBeTypeOf('string')
    }
  })

  it.each(plKeys)('pl.json value for "%s" is a non-empty string', (key) => {
    const value = plProjects[key]
    expect(typeof value, `Value for "${key}" in pl.json is not a string`).toBe('string')
    expect((value as string).trim().length, `Empty value for "${key}" in pl.json`).toBeGreaterThan(0)
  })

  it.each(ruKeys)('ru.json value for "%s" is a non-empty string', (key) => {
    const value = ruProjects[key]
    expect(typeof value, `Value for "${key}" in ru.json is not a string`).toBe('string')
    expect((value as string).trim().length, `Empty value for "${key}" in ru.json`).toBeGreaterThan(0)
  })
})
